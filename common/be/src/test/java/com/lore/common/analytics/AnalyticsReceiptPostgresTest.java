package com.lore.common.analytics;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="LORE_METRICS_DB_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:5403/lore_metrics_[0-9]+_test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnalyticsReceiptPostgresTest {
 LocalContainerEntityManagerFactoryBean factory; TransactionTemplate tx; JdbcTemplate db; AnalyticsService service; org.springframework.transaction.PlatformTransactionManager manager; AnalyticsEventRepository repository;
 @BeforeAll void setup() throws Exception {
  var ds=new DriverManagerDataSource(System.getenv("LORE_METRICS_DB_URL"),System.getenv("LORE_TEST_DB_USERNAME"),System.getenv("LORE_TEST_DB_PASSWORD"));
  db=new JdbcTemplate(ds);
  assertThat(db.queryForObject("select count(*) from information_schema.tables where table_schema='public'",Integer.class)).isZero();
  factory=new LocalContainerEntityManagerFactoryBean();factory.setDataSource(ds);factory.setPackagesToScan("com.lore.common.analytics");
  factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","create"));factory.afterPropertiesSet();
  try(var connection=ds.getConnection()){org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,new org.springframework.core.io.ClassPathResource("db/migration/V20261022_2347__zzal_analytics_delivery_receipts.sql"));}
  var emf=factory.getObject();manager=new JpaTransactionManager(emf);tx=new TransactionTemplate(manager);
  repository=new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(emf)).getRepository(AnalyticsEventRepository.class);
  service=new AnalyticsService(repository,org.mockito.Mockito.mock(AnonIdentityRepository.class),true,50);
 }
 @BeforeEach void clearEvents(){db.execute("truncate zzal_event restart identity");}
 @AfterAll void close(){if(factory!=null)factory.destroy();}
 @Test void realNativeInsertConcurrentReplayRollbackAndArchiveReadback() throws Exception {
  String id=UUID.randomUUID().toString();long ts=System.currentTimeMillis()-3*86400000L;
  var batch=new com.lore.common.analytics.dto.EventRequests.Batch(null,null,List.of(new com.lore.common.analytics.dto.EventRequests.Event("zzal_care",ts,"/zzal",Map.of("action","feed"),id,2,UUID.randomUUID().toString(),"real","room")));
  try(var pool=Executors.newFixedThreadPool(2)){
   var start=new CountDownLatch(1);var futures=new ArrayList<Future<AnalyticsService.Receipt>>();
   for(int i=0;i<2;i++)futures.add(pool.submit(()->{start.await();return tx.execute(s->service.collectAcknowledged(batch,"anon",12L,null));}));
   start.countDown();var a=futures.get(0).get(10,TimeUnit.SECONDS);var b=futures.get(1).get(10,TimeUnit.SECONDS);
   assertThat(a.persisted_count()+b.persisted_count()).isEqualTo(1);assertThat(a.duplicate_count()+b.duplicate_count()).isEqualTo(1);
   assertThat(a.accepted_event_ids()).containsExactly(id);assertThat(b.accepted_event_ids()).containsExactly(id);
  }
  assertThat(db.queryForObject("select count(*) from zzal_event",Integer.class)).isEqualTo(1);
  var cookieReset=tx.execute(s->service.collectAcknowledged(batch,"new-cookie",99L,null));
  assertThat(cookieReset.duplicate_count()).isEqualTo(1);
  assertThat(db.queryForObject("select anon_id from zzal_event",String.class)).isEqualTo("anon");
  var row=new com.lore.zzal.archive.JdbcEventSource(db).readAfter(0,java.time.Instant.now().plusSeconds(5),10).getFirst();
  assertThat(row.reportedTs()).isEqualTo(ts);assertThat(row.timestampQuality()).isEqualTo("delayed");
  assertThat(com.lore.zzal.archive.EventJsonLines.line(row)).contains(id,"reported_ts","timestamp_quality");
  var rollbackBatch=new com.lore.common.analytics.dto.EventRequests.Batch(null,null,List.of(new com.lore.common.analytics.dto.EventRequests.Event("zzal_care",ts,"/zzal",null,UUID.randomUUID().toString(),2,null,"real","room")));
  assertThatThrownBy(()->tx.execute(s->{service.collectAcknowledged(rollbackBatch,"another-anon",null,null);throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);
  assertThat(db.queryForObject("select count(*) from zzal_event",Integer.class)).isEqualTo(1);
  var now=java.time.Instant.now();
  var trusted=AnalyticsEvent.of("zzal_server_hatch","00000000000000000000000000000000",99L,"{}",null,null,"server",null,null,now,now)
      .envelope(id,2,null,null,null).identityStatus("server").serverOrigin();
  assertThat(tx.<Integer>execute(status->repository.insertOnce(trusted))).isEqualTo(1);
  assertThat(db.queryForObject("select count(*) from zzal_event where event_id=?",Integer.class,id)).isEqualTo(2);
  assertThat(db.queryForObject("select user_id from zzal_event where event_origin='server'",Long.class)).isEqualTo(99L);
 }
 @Test void committedOutcomeOnlyAndReturnGreetingBusinessRejectionPreservesSettlement() {
  var writer=new com.lore.zzal.generation.client.PersistentProviderUsageRecorder(repository,manager);
  int before=db.queryForObject("select count(*) from zzal_event",Integer.class);
  assertThatThrownBy(()->tx.execute(s->{writer.outcome("hatch","rollback-pet",1L,1L,null,"succeeded");throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);
  assertThat(db.queryForObject("select count(*) from zzal_event",Integer.class)).isEqualTo(before);
  tx.executeWithoutResult(s->writer.outcome("hatch","committed-pet",1L,1L,null,"succeeded"));
  tx.executeWithoutResult(s->writer.outcome("hatch","committed-pet",1L,1L,null,"succeeded"));
  assertThat(db.queryForObject("select count(*) from zzal_event",Integer.class)).isEqualTo(before+1);
  db.execute("create table if not exists settlement_probe(id integer primary key,ticks integer)");
  db.update("insert into settlement_probe values(1,0) on conflict(id) do update set ticks=0");
  var pets=org.mockito.Mockito.mock(com.lore.zzal.pet.PetService.class);
  var turns=org.mockito.Mockito.mock(com.lore.zzal.chat.session.ZzalChatTurnRepository.class);
  var target=org.mockito.Mockito.mock(com.lore.zzal.chat.ChatService.class,org.mockito.Mockito.CALLS_REAL_METHODS);
  org.springframework.test.util.ReflectionTestUtils.setField(target,"petService",pets);
  org.springframework.test.util.ReflectionTestUtils.setField(target,"turns",turns);
  var proxy=new org.springframework.aop.framework.ProxyFactory(target);
  proxy.setProxyTargetClass(true);
  proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
  var chat=(com.lore.zzal.chat.ChatService)proxy.getProxy();
  org.mockito.Mockito.when(pets.awake(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenAnswer(i->{
   db.update("update settlement_probe set ticks=ticks+1 where id=1");
   throw new com.lore.common.exception.BusinessException(com.lore.common.exception.ErrorCode.ZZAL_PET_SLEEPING);
  });
  assertThatThrownBy(()->chat.appendReturnGreeting(1L,1L,java.time.Instant.now())).isInstanceOf(com.lore.common.exception.BusinessException.class);
  assertThat(db.queryForObject("select ticks from settlement_probe where id=1",Integer.class)).isEqualTo(1);
  org.mockito.Mockito.doAnswer(i->{db.update("update settlement_probe set ticks=ticks+1 where id=1");throw new IllegalStateException("system");}).when(pets).awake(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
  assertThatThrownBy(()->chat.appendReturnGreeting(1L,1L,java.time.Instant.now())).isInstanceOf(IllegalStateException.class);
  assertThat(db.queryForObject("select ticks from settlement_probe where id=1",Integer.class)).isEqualTo(1);
  org.mockito.Mockito.verifyNoInteractions(turns);
 }

}
