package com.lore.zzal.attendance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** No database or provider: tests the handoff contract with a synchronized outbox fake. */
class CustomBundleDispatcherTest {
    @org.springframework.context.annotation.Configuration
    @org.springframework.scheduling.annotation.EnableScheduling
    static class SchedulingOnly { }
    @TempDir Path output;
    MockEnvironment enabled() {
        var env = new MockEnvironment().withProperty("app.zzal.custom.dispatch.output-root", output.toString());
        for (String flag : List.of("app.zzal.custom.dispatch.enabled", "app.zzal.custom.enabled",
                "app.zzal.generation.real", "app.zzal.generation.real-postprocess", "app.zzal.attendance.cli-enabled"))
            env.withProperty(flag,"true");
        return env;
    }
    static class Transactions extends AbstractPlatformTransactionManager {
        int commits;
        protected Object doGetTransaction(){return new Object();}
        protected void doBegin(Object transaction, TransactionDefinition definition){}
        protected void doCommit(DefaultTransactionStatus status){commits++;}
        protected void doRollback(DefaultTransactionStatus status){}
    }
    static class Outbox extends JdbcTemplate {
        Outbox(){super(mock(javax.sql.DataSource.class));}
        String state="PENDING",token,error;
        int queries;
        @Override @SuppressWarnings("unchecked")
        public synchronized <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(sql).contains("FOR UPDATE OF j SKIP LOCKED", "claim_token IS NULL", "error_code IS NULL");
            queries++;
            if(!"PENDING".equals(state)||token!=null||error!=null)return List.of();
            assertThat(args[0]).isEqualTo(AttendanceService.PROGRAM);
            assertThat(args[1]).isEqualTo(BundleCommandAdapter.SHEET_REFERENCE_POLICY);
            token=(String)args[2];
            return (List<T>)(List<?>)List.of(9L);
        }
    }
    CustomBundleDispatcher dispatcher(Outbox db, RealCustomBundleService generator) {
        return new CustomBundleDispatcher(db,new TransactionTemplate(new Transactions()),generator,enabled());
    }
    @Test void defaultOffCreatesNeitherDispatcherNorSchedulerAndIncompleteFlagsFailClosed() {
        new ApplicationContextRunner().withUserConfiguration(CustomBundleDispatcher.class).run(context -> {
            assertThat(context).doesNotHaveBean(CustomBundleDispatcher.class);
            assertThat(context).doesNotHaveBean("customBundleDispatchScheduler");
        });
        var db=new Outbox();var generation=mock(RealCustomBundleService.class);
        new ApplicationContextRunner().withUserConfiguration(CustomBundleDispatcher.class,SchedulingOnly.class)
                .withBean(JdbcTemplate.class,()->db)
                .withBean(TransactionTemplate.class,()->new TransactionTemplate(new Transactions()))
                .withBean(RealCustomBundleService.class,()->generation)
                .withPropertyValues("app.zzal.custom.dispatch.enabled=true","app.zzal.custom.enabled=true",
                        "app.zzal.generation.real=true","app.zzal.generation.real-postprocess=true",
                        "app.zzal.attendance.cli-enabled=true","app.zzal.custom.dispatch.output-root="+output)
                .run(context->{
                    assertThat(context).hasSingleBean(CustomBundleDispatcher.class);
                    assertThat(context).hasBean("customBundleDispatchScheduler");
                    var schedules=context.getBean(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class);
                    assertThat(schedules.getScheduledTasks()).hasSize(1);
                    assertThat(db.queries).isZero();verifyNoInteractions(generation);
                });
        for (String flag : List.of("app.zzal.custom.enabled", "app.zzal.generation.real",
                "app.zzal.generation.real-postprocess", "app.zzal.attendance.cli-enabled"))
            assertThatThrownBy(() -> new CustomBundleDispatcher(null,null,null,enabled().withProperty(flag,"false")))
                    .hasMessageContaining(flag);
    }
    @Test void pendingJobIsCommittedBeforeOneExistingRunAndCallerTransactionIsRejected() throws Exception {
        var db=new Outbox(); var generation=mock(RealCustomBundleService.class); var dispatcher=dispatcher(db,generation);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(call.getArgument(0,Long.class)).isEqualTo(9L);
            assertThat(call.getArgument(1,String.class)).isEqualTo(db.token);
            assertThat(call.getArgument(2,Path.class)).isEqualTo(output.resolve("job-9").resolve(db.token));
            return true;
        }).when(generation).run(anyLong(),anyString(),any(),any());
        dispatcher.poll();dispatcher.poll();
        verify(generation,times(1)).run(anyLong(),anyString(),any(),any());
        assertThat(db.state).isEqualTo("PENDING");assertThat(db.token).isNotBlank();
        int before=db.queries;
        assertThatThrownBy(() -> new TransactionTemplate(new Transactions()).execute(status->{dispatcher.poll();return null;}))
                .hasMessageContaining("caller transaction");
        assertThat(db.queries).isEqualTo(before);
    }
    @Test void twoDispatchersCannotHandTheSameJobToTheProviderTwice() throws Exception {
        var db=new Outbox();var generation=mock(RealCustomBundleService.class);
        var first=dispatcher(db,generation);var second=dispatcher(db,generation);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        doAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();calls.incrementAndGet();entered.countDown();release.await(5,TimeUnit.SECONDS);return true;})
                .when(generation).run(anyLong(),anyString(),any(),any());
        var pool=Executors.newSingleThreadExecutor();
        try {
            var running=pool.submit(first::poll);assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            second.poll();assertThat(calls.get()).isEqualTo(1);release.countDown();running.get(5,TimeUnit.SECONDS);
            verify(generation,times(1)).run(anyLong(),anyString(),any(),any());
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void failedUncertainAndAlreadyClaimedJobsAreNeverAutomaticallyReplayed() throws Exception {
        for(String state:List.of("CLAIMED","FAILED","GENERATED")){
            var db=new Outbox();db.state=state;var generation=mock(RealCustomBundleService.class);
            dispatcher(db,generation).poll();verifyNoInteractions(generation);
        }
        var planning=new Outbox();planning.error="PLANNING_IN_PROGRESS";
        var unused=mock(RealCustomBundleService.class);dispatcher(planning,unused).poll();verifyNoInteractions(unused);
        var db=new Outbox();var generation=mock(RealCustomBundleService.class);
        doThrow(new TimeoutException("synthetic unknown outcome")).when(generation).run(anyLong(),anyString(),any(),any());
        var dispatcher=dispatcher(db,generation);dispatcher.poll();String token=db.token;dispatcher.poll();
        verify(generation,times(1)).run(anyLong(),anyString(),any(),any());assertThat(db.token).isEqualTo(token);
        assertThat(db.state).isEqualTo("PENDING");assertThat(db.error).isNull();
    }
    @Test void existingWorkerAcceptsExplicitSameTokenButDoesNotTakeAnotherDispatchersToken() throws Exception {
        var plans=mock(BundleSourcePlan.class);when(plans.requirePlan(anyLong(),any())).thenReturn("{\"motionPlan\":{}}");
        var db=new JdbcTemplate(){
            @Override public <T> T queryForObject(String sql,Class<T> type,Object... args){return type.cast(sql.contains("SELECT claim_token")?"reserved-token":"{}");}
            @Override public int update(String sql,Object... args){
                assertThat(sql).contains("AND (claim_token IS NULL OR claim_token=?)");
                assertThat(args[0]).isEqualTo(args[4]);
                return "reserved-token".equals(args[4])?1:0;
            }
        };
        var executor=mock(GenerationExecutor.class);when(executor.execute(any(),any())).thenReturn("synthetic-receipt");
        var worker=new BundleJobWorker(db,new TransactionTemplate(new Transactions()),null,plans,"unused-python",true){
            @Override public void complete(long id,String token,String receipt,Path directory,Instant now){}
        };
        var planning=mock(CustomPlanningService.class);
        var explicit=new RealCustomBundleService(planning,worker,executor,null,"stub-only");
        assertThatThrownBy(()->explicit.run(9,"other-token",output,Instant.EPOCH)).hasMessageContaining("another claim token");
        verifyNoInteractions(planning);verify(executor).preflight();verify(executor,never()).execute(any(),any());
        worker.requireCompatibleClaim(9,"reserved-token");
        // Only bypass the unrelated decoder preflight; no Python/image process is started.
        try(var decoder=mockConstruction(BundleAssetVerifier.class);var requestFactory=mockStatic(CustomGenerationRequest.class)){
            requestFactory.when(()->CustomGenerationRequest.from(anyLong(),anyString(),anyString())).thenReturn(mock(CustomGenerationRequest.class));
            assertThat(worker.run(9,"other-token",executor,output)).isFalse();
            // Build a valid typed request at the executor boundary without calling an image provider.
            doThrow(new IllegalStateException("reached explicit executor boundary")).when(executor).execute(any(),any());
            assertThatThrownBy(()->worker.run(9,"reserved-token",executor,output))
                    .hasMessage("reached explicit executor boundary");
        }
    }
}
