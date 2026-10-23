package com.lore.zzal.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL/CTE/transactions; only the existing external generation entry is replaced. */
@EnabledIfEnvironmentVariable(named="LORE_CUSTOM_DISPATCH_DB_URL",
        matches="jdbc:postgresql://127\\.0\\.0\\.1:5403/lore_custom_dispatch_[0-9]+_test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomBundleDispatcherPostgresTest {
    DriverManagerDataSource source; JdbcTemplate db;
    final Map<String,Object> evidence=new LinkedHashMap<>();
    @TempDir Path output;
    final String snapshot="{\"schemaVersion\":4,\"materialPolicy\":\"CUSTOM_SUBJECT_SHEET_REFERENCE_V4\"}";
    @BeforeAll void schema() throws Exception {
        String url=System.getenv("LORE_CUSTOM_DISPATCH_DB_URL");
        assertThat(url).matches("jdbc:postgresql://127\\.0\\.0\\.1:5403/lore_custom_dispatch_[0-9]+_test");
        source=new DriverManagerDataSource(url,System.getenv("LORE_TEST_DB_USERNAME"),System.getenv("LORE_TEST_DB_PASSWORD"));
        db=new JdbcTemplate(source);db.setQueryTimeout(3);
        // Only fields read by the CTE are scaffolded; job/asset/reward DDL is the unmodified product migration.
        db.execute("CREATE TABLE zzal_pet(id bigint PRIMARY KEY, phase varchar(20) NOT NULL)");
        try(var connection=source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V20261016_2290__zzal_attendance_rewards.sql"));
        }
        db.update("INSERT INTO zzal_pet(id,phase) VALUES(1,'ALIVE')");
        evidence.put("actualJobDdl","V20261016_2290__zzal_attendance_rewards.sql");
        evidence.put("postgresVersion",db.queryForObject("SHOW server_version",String.class));
    }
    @BeforeEach void reset() { db.execute("TRUNCATE zzal_custom_bundle_job RESTART IDENTITY CASCADE"); }
    @AfterAll void saveEvidence() throws Exception {
        String path=System.getenv("LORE_CUSTOM_DISPATCH_PG_EVIDENCE");
        if(path!=null)Files.writeString(Path.of(path),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
    }
    long pending(int set) {
        return db.queryForObject("INSERT INTO zzal_custom_bundle_job(pet_id,program_version,bundle_version,snapshot_json,input_hash,created_at) VALUES(1,?,?,?,?,now()) RETURNING id",
                Long.class,AttendanceService.PROGRAM,"bundle-"+set,snapshot,AttendanceService.hash(snapshot));
    }
    int pid(Connection connection) throws Exception {
        try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT pg_backend_pid()")){rows.next();return rows.getInt(1);}
    }
    List<Long> claim(Connection connection,String token) throws Exception {
        try(var statement=connection.prepareStatement(CustomBundleDispatcher.CLAIM)) {
            statement.setString(1,AttendanceService.PROGRAM);statement.setString(2,BundleCommandAdapter.SHEET_REFERENCE_POLICY);statement.setString(3,token);
            statement.setQueryTimeout(3);
            try(var rows=statement.executeQuery()){var result=new ArrayList<Long>();while(rows.next())result.add(rows.getLong(1));return result;}
        }
    }
    CustomBundleDispatcher dispatcher(RealCustomBundleService generation) {
        var env=new MockEnvironment().withProperty("app.zzal.custom.dispatch.output-root",output.toString());
        for(String flag:List.of("app.zzal.custom.dispatch.enabled","app.zzal.custom.enabled","app.zzal.generation.real",
                "app.zzal.generation.real-postprocess","app.zzal.attendance.cli-enabled"))env.withProperty(flag,"true");
        return new CustomBundleDispatcher(db,new TransactionTemplate(new DataSourceTransactionManager(source)),generation,env);
    }
    @Test void twoActualConnectionsReserveTheSameJobExactlyOnce() throws Exception {
        long job=pending(1);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        var pids=new ConcurrentSkipListSet<Integer>();
        try {
            var tasks=new ArrayList<Future<List<Long>>>();
            for(String token:List.of("connection-a","connection-b"))tasks.add(pool.submit(()->{
                try(var connection=source.getConnection()) {
                    connection.setAutoCommit(false);pids.add(pid(connection));start.await();
                    var selected=claim(connection,token);connection.commit();return selected;
                }
            }));
            start.countDown();var selected=new ArrayList<Long>();for(var task:tasks)selected.addAll(task.get(5,TimeUnit.SECONDS));
            assertThat(pids).hasSize(2);assertThat(selected).containsExactly(job);
            assertThat(db.queryForObject("SELECT count(*) FROM zzal_custom_bundle_job WHERE claim_token IS NOT NULL",Integer.class)).isEqualTo(1);
            try(var again=source.getConnection()){assertThat(claim(again,"later-poll")).isEmpty();}
            evidence.put("sameJobRace",Map.of("connectionPids",pids,"reservations",selected.size(),"replayReservations",0));
        } finally {pool.shutdownNow();}
    }
    @Test void skipLockedSkipsTheFirstJobAndOtherConnectionsProcessDifferentJobs() throws Exception {
        long first=pending(1),second=pending(2);var ids=new ArrayList<Long>();
        var generation=mock(RealCustomBundleService.class);
        doAnswer(call->{ids.add(call.getArgument(0,Long.class));return true;}).when(generation).run(anyLong(),anyString(),any(),any());
        var dispatcher=dispatcher(generation);
        try(var locked=source.getConnection()) {
            locked.setAutoCommit(false);
            try(var statement=locked.prepareStatement("SELECT id FROM zzal_custom_bundle_job WHERE id=? FOR UPDATE")) {
                statement.setLong(1,first);statement.executeQuery().close();
            }
            dispatcher.poll();assertThat(ids).containsExactly(second);locked.commit();
        }
        dispatcher.poll();dispatcher.poll();assertThat(ids).containsExactly(second,first);
        assertThat(db.queryForObject("SELECT count(DISTINCT claim_token) FROM zzal_custom_bundle_job",Integer.class)).isEqualTo(2);
        evidence.put("lockedFirstJob",Map.of("firstPollJob",second,"nextPollJob",first,"handoffs",ids.size(),"duplicates",0));
    }
    @Test void actualDispatcherCommitsBeforeExternalRunAndReleasesItsRowLock() throws Exception {
        long job=pending(1);var generation=mock(RealCustomBundleService.class);var called=new AtomicInteger();
        doAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            try(var independent=source.getConnection()) {
                independent.setAutoCommit(false);
                try(var statement=independent.prepareStatement("SELECT claim_token FROM zzal_custom_bundle_job WHERE id=? FOR UPDATE NOWAIT")) {
                    statement.setLong(1,job);
                    try(var rows=statement.executeQuery()){assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo(call.getArgument(1,String.class));}
                }
                independent.commit();evidence.put("externalRunLockProbePid",pid(independent));
            }
            called.incrementAndGet();return true;
        }).when(generation).run(anyLong(),anyString(),any(),any());
        dispatcher(generation).poll();assertThat(called.get()).isEqualTo(1);
        evidence.put("commitBeforeExternalRun",true);evidence.put("externalForUpdateNowaitAcquired",true);
    }
    @Test void failedHandoffAndExistingUncertainStatesCannotBeAutomaticallyReplayed() throws Exception {
        long job=pending(1);var generation=mock(RealCustomBundleService.class);
        doThrow(new TimeoutException("synthetic external outcome unknown")).when(generation).run(anyLong(),anyString(),any(),any());
        var dispatcher=dispatcher(generation);dispatcher.poll();String token=db.queryForObject("SELECT claim_token FROM zzal_custom_bundle_job WHERE id=?",String.class,job);
        assertThat(token).isNotBlank();dispatcher.poll();verify(generation,times(1)).run(anyLong(),anyString(),any(),any());
        for(String state:List.of("CLAIMED","FAILED","GENERATED")) {
            long blocked=pending(2+List.of("CLAIMED","FAILED","GENERATED").indexOf(state));
            db.update("UPDATE zzal_custom_bundle_job SET state=? WHERE id=?",state,blocked);
        }
        long planning=pending(5);db.update("UPDATE zzal_custom_bundle_job SET error_code='PLANNING_IN_PROGRESS' WHERE id=?",planning);
        dispatcher.poll();verify(generation,times(1)).run(anyLong(),anyString(),any(),any());
        assertThat(db.queryForObject("SELECT claim_token FROM zzal_custom_bundle_job WHERE id=?",String.class,job)).isEqualTo(token);
        assertThat(db.queryForObject("SELECT state FROM zzal_custom_bundle_job WHERE id=?",String.class,job)).isEqualTo("PENDING");
        evidence.put("timeoutAndBlockedStates",Map.of("externalAttempts",1,"automaticRetries",0,"tokenPreserved",true,"blockedOtherRows",4));
    }
    @Test void rolledBackReservationDoesNotBecomeAFalseCommittedHandoff() throws Exception {
        long job=pending(1);
        try(var transaction=source.getConnection()) {
            transaction.setAutoCommit(false);assertThat(claim(transaction,"rolled-back-token")).containsExactly(job);transaction.rollback();
        }
        assertThat(db.queryForObject("SELECT claim_token FROM zzal_custom_bundle_job WHERE id=?",String.class,job)).isNull();
        var generation=mock(RealCustomBundleService.class);when(generation.run(anyLong(),anyString(),any(),any())).thenReturn(true);
        dispatcher(generation).poll();verify(generation,times(1)).run(eq(job),anyString(),any(),any());
        assertThat(db.queryForObject("SELECT claim_token FROM zzal_custom_bundle_job WHERE id=?",String.class,job)).isNotEqualTo("rolled-back-token");
        evidence.put("rollback",Map.of("rolledBackTokenAbsent",true,"laterHandoffs",1));
    }
}
