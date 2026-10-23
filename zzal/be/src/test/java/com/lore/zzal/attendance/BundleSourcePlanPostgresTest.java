package com.lore.zzal.attendance;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="LORE_BUNDLE_SOURCE_DB_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:55420/lore_bundle_source_[0-9]+_test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BundleSourcePlanPostgresTest {
 static final Instant NOW=Instant.parse("2026-10-17T12:00:00Z");
 static final String SNAPSHOT="{\"schemaVersion\":2,\"materialPolicy\":\"CUSTOM_PERSONA_IDENTITY_LONG_TERM_V2\",\"identity\":\"외형\",\"memory\":{\"state\":\"AVAILABLE\",\"candidates\":[{\"id\":11,\"revision\":1,\"subject\":\"CHARACTER\",\"status\":\"ACTIVE\",\"sourceTurnId\":50,\"sourceQuote\":\"기억 하나\"},{\"id\":12,\"revision\":1,\"subject\":\"CHARACTER\",\"status\":\"ACTIVE\",\"sourceTurnId\":51,\"sourceQuote\":\"새 에피소드\"}]}}";
 JdbcTemplate db;TransactionTemplate tx;BundleSourcePlan plans;
 @BeforeAll void schema()throws Exception{
  var ds=new DriverManagerDataSource(System.getenv("LORE_BUNDLE_SOURCE_DB_URL"),System.getenv("LORE_TEST_DB_USERNAME"),System.getenv("LORE_TEST_DB_PASSWORD"));
  db=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));plans=new BundleSourcePlan(db);
  assertThat(db.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='public'",Integer.class)).isZero();
  db.execute("CREATE TABLE zzal_pet(id bigint PRIMARY KEY)");
  try(var c=ds.getConnection()){ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/V20261016_2290__zzal_attendance_rewards.sql"));ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/V20261017_0040__zzal_custom_bundle_memory_sources.sql"));}
 }
 @BeforeEach void reset(){db.execute("TRUNCATE zzal_custom_bundle_source,zzal_custom_bundle_job,zzal_pet RESTART IDENTITY CASCADE");db.update("INSERT INTO zzal_pet(id) VALUES(1)");}
 long job(String bundle){return db.queryForObject("INSERT INTO zzal_custom_bundle_job(pet_id,program_version,bundle_version,snapshot_json,input_hash,created_at) VALUES(1,'first-v1',?,?,?,?) RETURNING id",Long.class,bundle,SNAPSHOT,AttendanceService.hash(SNAPSHOT),Timestamp.from(NOW));}
 interface Action{void run()throws Exception;}
 void transaction(Action a){tx.executeWithoutResult(s->{try{a.run();}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException(e);}});}
 void reserve(long job,long id){transaction(()->plans.reserve(job,List.of(new BundleSourcePlan.Source(id,1)),NOW));}
 String state(long job){return db.queryForObject("SELECT state FROM zzal_custom_bundle_source WHERE job_id=?",String.class,job);}
 @Test void explicitPlanFreezesOnlySelectedInputAndEmptyPlanIsDistinctFromMissing()throws Exception{
  long j=job("one");assertThatThrownBy(()->plans.requirePlan(j,SNAPSHOT)).hasMessage("SOURCE_PLAN_REQUIRED");reserve(j,11);
  String plan=plans.requirePlan(j,SNAPSHOT);var input=BundleCommandAdapter.plannedInput(SNAPSHOT,plan);
  assertThat(input.json()).contains("기억 하나","selectedSources").doesNotContain("새 에피소드");
  assertThat(input.sourceSnapshotHash()).isEqualTo(AttendanceService.hash(SNAPSHOT));
  assertThatThrownBy(()->reserve(j,12)).hasMessage("Frozen source plan conflict");
  long empty=job("empty");transaction(()->plans.reserve(empty,List.of(),NOW));assertThat(plans.requirePlan(empty,SNAPSHOT)).contains("selectedSources");
 }
 @Test void concurrentJobsCannotReserveSameEpisode()throws Exception{
  long a=job("a"),b=job("b");var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
  try{List<Future<Boolean>> results=new ArrayList<>();for(long id:List.of(a,b))results.add(pool.submit(()->{start.await();try{reserve(id,11);return true;}catch(RuntimeException conflict){return false;}}));start.countDown();int wins=0;for(var result:results)if(result.get(10,TimeUnit.SECONDS))wins++;assertThat(wins).isEqualTo(1);assertThat(db.queryForObject("SELECT count(*) FROM zzal_custom_bundle_source",Integer.class)).isEqualTo(1);}finally{pool.shutdownNow();}
 }
 @Test void successfulReceiptUsesOnlyExplicitSubsetAndRejectsUnknownWithoutMutation(){
  long j=job("one");reserve(j,11);
  assertThatThrownBy(()->transaction(()->plans.complete(j,SNAPSHOT,List.of(new BundleSourcePlan.Source(12L,1)),true,NOW))).isInstanceOf(IllegalArgumentException.class);assertThat(state(j)).isEqualTo("RESERVED");
  transaction(()->plans.complete(j,SNAPSHOT,List.of(new BundleSourcePlan.Source(11L,1)),true,NOW));assertThat(state(j)).isEqualTo("USED");
  assertThatThrownBy(()->reserve(job("two"),11)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
 }
 @Test void v3ReservesUserWithoutRewritingSubjectAndRejectsInference()throws Exception {
  String v3=SNAPSHOT.replace("CUSTOM_PERSONA_IDENTITY_LONG_TERM_V2","CUSTOM_SUBJECT_PRESERVING_LONG_TERM_V3").replace("\"subject\":\"CHARACTER\"","\"subject\":\"USER\",\"kind\":\"USER_REPORTED\"");
  long j=job("v3");db.update("UPDATE zzal_custom_bundle_job SET snapshot_json=?,input_hash=? WHERE id=?",v3,AttendanceService.hash(v3),j);
  reserve(j,11);var input=BundleCommandAdapter.plannedInput(v3,plans.requirePlan(j,v3));
  assertThat(input.json()).contains("\"subject\":\"USER\"","기억 하나").doesNotContain("새 에피소드");
  assertThat(db.queryForObject("SELECT snapshot_json FROM zzal_custom_bundle_job WHERE id=?",String.class,j)).isEqualTo(v3);
  long bad=job("bad");String inference=v3.replace("USER_REPORTED","INFERENCE");db.update("UPDATE zzal_custom_bundle_job SET snapshot_json=? WHERE id=?",inference,bad);
  assertThatThrownBy(()->reserve(bad,12)).hasMessage("Unconfirmed planned source");
 }
 @Test void typedPlanningFreezesEightMotionsAndReusesWithoutCallingAuthorAgain()throws Exception {
  long j=job("typed");String v3=CustomPlannerTest.SNAPSHOT;
  db.update("UPDATE zzal_custom_bundle_job SET snapshot_json=?,input_hash=? WHERE id=?",v3,AttendanceService.hash(v3),j);
  var service=new CustomPlanningService(db,plans);var calls=new java.util.concurrent.atomic.AtomicInteger();
  transaction(()->service.prepare(j,c->{calls.incrementAndGet();return CustomPlannerTest.fixture();},NOW));
  String frozen=plans.requirePlan(j,v3);var request=CustomGenerationRequest.from(j,v3,frozen);
  assertThat(request.frozenPlan().motions()).hasSize(8);assertThat(request.quality()).isEqualTo("medium");
  assertThat(service.prepare(j,c->{throw new AssertionError("must reuse frozen plan");},NOW)).isEqualTo(CustomPlannerTest.fixture());assertThat(calls.get()).isEqualTo(1);
  assertThat(plans.requirePlan(j,v3)).isEqualTo(frozen);assertThat(state(j)).isEqualTo("RESERVED");
 }
 @Test void typedPlannerExcludesUsedSourcesAndCannotRewriteHistoricalPlan(){
  long old=job("old");reserve(old,11);transaction(()->plans.complete(old,SNAPSHOT,List.of(new BundleSourcePlan.Source(11L,1)),true,NOW));
  long next=job("next");db.update("UPDATE zzal_custom_bundle_job SET snapshot_json=? WHERE id=?",CustomPlannerTest.SNAPSHOT,next);
  var service=new CustomPlanningService(db,plans);
  assertThatThrownBy(()->service.prepare(next,c->{assertThat(c.availableMemory()).isEmpty();return CustomPlannerTest.fixture();},NOW)).hasMessage("Unknown, excluded or misattributed motion evidence");
  assertThatThrownBy(()->service.prepare(old,c->CustomPlannerTest.fixture(),NOW)).hasMessage("Historical source plan cannot be rewritten");
 }
 @Test void typedFailedBundleCannotPartiallyGrantReleasedMaterialsButLegacyKeepsItsContract(){
  long j=job("bundle-1");db.update("UPDATE zzal_custom_bundle_job SET state='FAILED',manifest_hash='receipt',source_plan_json='{\"motionPlan\":{}}' WHERE id=?",j);
  db.update("INSERT INTO zzal_custom_bundle_asset(job_id,motion_order,asset_key,asset_hash,auto_check,human_review,published) VALUES(?,1,'images/pass','hash','PASS','UNREVIEWED',true)",j);
  db.update("INSERT INTO zzal_attendance_reward_slot(pet_id,program_version,slot_key,eligible_day) VALUES(1,'first-v1','motion-1',3)");
  new AttendanceService(db,null,null).grantReady(1L,NOW);assertThat(db.queryForObject("SELECT state FROM zzal_attendance_reward_slot",String.class)).isEqualTo("ELIGIBLE");
  db.update("UPDATE zzal_custom_bundle_job SET source_plan_json=NULL WHERE id=?",j);new AttendanceService(db,null,null).grantReady(1L,NOW);assertThat(db.queryForObject("SELECT state FROM zzal_attendance_reward_slot",String.class)).isEqualTo("GRANTED");
 }
 @Test void directPersistenceCannotMisattributeUserAsCharacter(){
  long j=job("misattribution");db.update("UPDATE zzal_custom_bundle_job SET snapshot_json=? WHERE id=?",CustomPlannerTest.SNAPSHOT,j);
  var original=CustomPlannerTest.fixture();var m=original.motions().get(0);var motions=new ArrayList<>(original.motions());motions.set(0,new CustomMotionPlan.Motion(m.order(),m.title(),m.action(),m.rationale(),m.basis(),List.of(new CustomMotionPlan.Evidence(11L,1,"CHARACTER")),m.semantic(),m.albumOnly(),m.pose(),m.props(),m.staging()));
  assertThatThrownBy(()->transaction(()->plans.reserveMotionPlan(j,new CustomMotionPlan(CustomMotionPlan.VERSION,motions),NOW))).hasMessage("Unknown, excluded or misattributed motion evidence");
  assertThat(db.queryForObject("SELECT count(*) FROM zzal_custom_bundle_source",Integer.class)).isZero();
 }
 @Test void sameJobConcurrentPlanningCallsAuthorOnlyOnce()throws Exception {
  long j=job("concurrent-plan");db.update("UPDATE zzal_custom_bundle_job SET snapshot_json=? WHERE id=?",CustomPlannerTest.SNAPSHOT,j);
  var service=new CustomPlanningService(db,plans);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new java.util.concurrent.atomic.AtomicInteger();var pool=Executors.newFixedThreadPool(2);
  try{var first=pool.submit(()->{transaction(()->service.prepare(j,c->{calls.incrementAndGet();entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("timeout");return CustomPlannerTest.fixture();},NOW));return true;});assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
   assertThatThrownBy(()->service.prepare(j,c->{calls.incrementAndGet();return CustomPlannerTest.fixture();},NOW)).hasMessage("PLANNING_ALREADY_CLAIMED");release.countDown();assertThat(first.get(10,TimeUnit.SECONDS)).isTrue();assertThat(calls.get()).isEqualTo(1);
  }finally{release.countDown();pool.shutdownNow();}
 }
 @Test void failedAuthorIsNotAutomaticallyInvokedAgain(){
  long j=job("uncertain-plan");db.update("UPDATE zzal_custom_bundle_job SET snapshot_json=? WHERE id=?",CustomPlannerTest.SNAPSHOT,j);var service=new CustomPlanningService(db,plans);var calls=new java.util.concurrent.atomic.AtomicInteger();
  assertThatThrownBy(()->service.prepare(j,c->{calls.incrementAndGet();throw new IllegalStateException("unknown result");},NOW)).hasMessage("unknown result");
  assertThatThrownBy(()->service.prepare(j,c->{calls.incrementAndGet();return CustomPlannerTest.fixture();},NOW)).hasMessage("PLANNING_ALREADY_CLAIMED");assertThat(calls.get()).isEqualTo(1);
 }
 @Test void uncertainClaimAndFailedOutputRetainReservation(){
  long j=job("one");reserve(j,11);db.update("UPDATE zzal_custom_bundle_job SET state='CLAIMED' WHERE id=?",j);
  assertThatThrownBy(()->reserve(job("two"),11)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);assertThat(state(j)).isEqualTo("RESERVED");
  transaction(()->plans.complete(j,SNAPSHOT,List.of(),false,NOW));assertThat(state(j)).isEqualTo("RESERVED");assertThatThrownBy(()->reserve(job("three"),11)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
 }
}
