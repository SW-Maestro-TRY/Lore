package com.lore.zzal.attendance;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="LORE_NEXT_CUSTOM_DB_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:55420/lore_next_custom_[0-9]+_test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NextCustomSetQueuePostgresTest {
 static final Instant START=NextCustomSetEligibilityTest.START;
 static final String SNAPSHOT="{\"materialPolicy\":\"CUSTOM_SUBJECT_SHEET_REFERENCE_V4\",\"sheetImageKey\":\"images/sheet.webp\",\"identity\":\"frozen appearance\",\"persona\":{\"world\":\"city\"},\"memory\":{\"state\":\"EMPTY\",\"candidates\":[]},\"customGridTemplateVersion\":\"custom-v2\",\"customGridTemplateHash\":\"hash\"}";
 JdbcTemplate db;TransactionTemplate tx;NextCustomSetQueueService service;
 @BeforeAll void schema()throws Exception{
  var ds=new DriverManagerDataSource(System.getenv("LORE_NEXT_CUSTOM_DB_URL"),System.getenv("LORE_TEST_DB_USERNAME"),System.getenv("LORE_TEST_DB_PASSWORD"));db=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));service=new NextCustomSetQueueService(db);
  assertThat(db.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='public'",Integer.class)).isZero();
  db.execute("CREATE TABLE zzal_pet(id bigint PRIMARY KEY,user_id bigint NOT NULL)");
  try(var c=ds.getConnection()){ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/V20261016_2290__zzal_attendance_rewards.sql"));ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/V20261017_0030__zzal_initial_background_cluster.sql"));ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/V20261017_0040__zzal_custom_bundle_memory_sources.sql"));}
  db.execute("CREATE TABLE zzal_chat_turn(id bigserial PRIMARY KEY,pet_id bigint NOT NULL,speaker varchar(4) NOT NULL,line text NOT NULL,created_at timestamptz NOT NULL)");
 }
 void cycle(int day,int messages){Instant at=START.plusSeconds(day*86400L);db.update("INSERT INTO zzal_awake_visit(pet_id,program_version,cycle_key,event_id,observed_at) VALUES(1,'first-v1',?,?,?)",(day==0?"INITIAL":"NIGHT:"+at),"event-"+day,Timestamp.from(at));for(int i=0;i<messages;i++)db.update("INSERT INTO zzal_chat_turn(pet_id,speaker,line,created_at) VALUES(1,'USER','same text',?)",Timestamp.from(at.plusSeconds(i+1)));db.update("INSERT INTO zzal_chat_turn(pet_id,speaker,line,created_at) VALUES(1,'PET','not participation',?)",Timestamp.from(at.plusSeconds(8)));}
 @BeforeEach void fixture(){
  db.execute("TRUNCATE zzal_custom_bundle_job,zzal_pet,zzal_chat_turn RESTART IDENTITY CASCADE");db.update("INSERT INTO zzal_pet(id,user_id) VALUES(1,11)");for(int day=0;day<8;day++)cycle(day,5);
  long j=db.queryForObject("INSERT INTO zzal_custom_bundle_job(pet_id,program_version,bundle_version,snapshot_json,input_hash,state,manifest_hash,created_at) VALUES(1,'first-v1','bundle-1','historical','original','GENERATED','receipt',?) RETURNING id",Long.class,Timestamp.from(START.minusSeconds(10)));
  for(int order=1;order<=8;order++){long a=asset(j,order,true);db.update("INSERT INTO zzal_attendance_reward_slot(pet_id,program_version,slot_key,eligible_day,state,asset_id,asset_key,granted_at) VALUES(1,'first-v1',?,3,'GRANTED',?,?,?)","motion-"+order,a,"images/"+j+"/"+order+".webp",Timestamp.from(START.plusSeconds((order==1?0:6)*86400L+60)));}
 }
 long asset(long job,int order,boolean published){return db.queryForObject("INSERT INTO zzal_custom_bundle_asset(job_id,motion_order,asset_key,asset_hash,auto_check,human_review,published) VALUES(?,?,?,'hash','PASS','UNREVIEWED',?) RETURNING id",Long.class,job,order,"images/"+job+"/"+order+".webp",published);}
 Instant now(){return START.plusSeconds(7*86400L+3600);}
 NextCustomSetQueueService.Queued queue(){return tx.execute(status->service.enqueue(11L,1L,now(),()->SNAPSHOT));}
 @Test void queuesOnceAndPreservesOldJobAndDistinctEightDailyRights(){
  var result=queue();assertThat(result.created()).isTrue();assertThat(result.evaluation().decision().generationCycle()).isEqualTo("NIGHT:"+START.plusSeconds(7*86400L));
  assertThat(queue().created()).isFalse();assertThat(db.queryForObject("SELECT count(*) FROM zzal_custom_bundle_job",Integer.class)).isEqualTo(2);
  assertThat(db.queryForObject("SELECT snapshot_json||':'||input_hash FROM zzal_custom_bundle_job WHERE bundle_version='bundle-1'",String.class)).isEqualTo("historical:original");
  assertThat(db.queryForList("SELECT eligible_day FROM zzal_attendance_reward_slot WHERE slot_key LIKE 'bundle-2:%' ORDER BY eligible_day",Integer.class)).containsExactly(9,10,11,12,13,14,15,16);
  assertThat(db.queryForObject("SELECT snapshot_json FROM zzal_custom_bundle_job WHERE id=?",String.class,result.jobId())).isEqualTo(SNAPSHOT);
 }
 @Test void concurrentQueueCallsUsePetLockAndInvokeSnapshotOnlyOnce()throws Exception{
  var pool=Executors.newFixedThreadPool(2);var go=new CountDownLatch(1);var calls=new AtomicInteger();
  try{List<Future<Boolean>> results=new ArrayList<>();for(int i=0;i<2;i++)results.add(pool.submit(()->{go.await();return tx.execute(s->service.enqueue(11L,1L,now(),()->{calls.incrementAndGet();return SNAPSHOT;}).created());}));go.countDown();int made=0;for(var f:results)if(f.get(10,TimeUnit.SECONDS))made++;assertThat(made).isEqualTo(1);assertThat(calls.get()).isEqualTo(1);}finally{pool.shutdownNow();}
 }
 @Test void missingReceiptAndInsufficientUserCyclesDoNotCallSnapshotFactory(){
  db.update("UPDATE zzal_attendance_reward_slot SET state='ELIGIBLE' WHERE slot_key='motion-8'");
  assertThat(tx.execute(s->service.enqueue(11L,1L,now(),()->{throw new AssertionError("must not snapshot");})).created()).isFalse();
  db.update("UPDATE zzal_attendance_reward_slot SET state='GRANTED' WHERE slot_key='motion-8'");db.update("DELETE FROM zzal_chat_turn WHERE speaker='USER' AND created_at>=?",Timestamp.from(START.plusSeconds(6*86400L)));
  assertThat(service.evaluate(11L,1L,now()).decision().state()).isEqualTo(NextCustomSetEligibility.State.AWAITING_SEVEN_CYCLES);
  assertThatThrownBy(()->service.evaluate(12L,1L,now())).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void latePreparedAssetsDeliverAccruedRightsIdempotentlyAndDoNotReuseOldParticipation(){
  var result=queue();long j=result.jobId();for(int day=8;day<16;day++)cycle(day,5);Instant late=START.plusSeconds(15*86400L+3600);
  db.update("UPDATE zzal_custom_bundle_job SET state='GENERATED',manifest_hash='new-receipt' WHERE id=?",j);for(int order=1;order<=8;order++)asset(j,order,false);
  assertThat(tx.<Integer>execute(s->service.grantReadyQueued(11L,1L,late))).isZero();db.update("UPDATE zzal_custom_bundle_asset SET published=true WHERE job_id=?",j);
  assertThat(tx.<Integer>execute(s->service.grantReadyQueued(11L,1L,late))).isEqualTo(8);assertThat(tx.<Integer>execute(s->service.grantReadyQueued(11L,1L,late))).isZero();
  var decision=service.evaluate(11L,1L,late).decision();assertThat(decision.qualifyingCycles()).isEqualTo(1);assertThat(decision.state()).isEqualTo(NextCustomSetEligibility.State.AWAITING_SEVEN_CYCLES);
  assertThat(db.queryForObject("SELECT count(*) FROM zzal_attendance_reward_slot WHERE state='GRANTED'",Integer.class)).isEqualTo(16);
 }
 com.lore.zzal.pet.ZzalPet pet(){
  var pet=com.lore.zzal.pet.ZzalPet.draft(11L,"images/source.webp",START.minusSeconds(86400));
  org.springframework.test.util.ReflectionTestUtils.setField(pet,"id",1L);pet.character("합성","설정",null,"현대",null,null,START.minusSeconds(86400));pet.markAlive("images/sheet.webp","identity",START.minusSeconds(86400));
  org.springframework.test.util.ReflectionTestUtils.setField(pet,"clockStartedAt",START.minusSeconds(86400));
  org.springframework.test.util.ReflectionTestUtils.setField(pet,"attendanceNightCycleAt",START.plusSeconds(7*86400L));
  org.springframework.test.util.ReflectionTestUtils.setField(pet,"hatchPipelineVersion","v1");assertThat(pet.isAlive()).isTrue();assertThat(pet.isInTutorial()).isFalse();return pet;
 }
 AttendanceService attendance(RewardMaterialProvider memories){return new AttendanceService(db,new PublishedBackgroundCatalog("[]"),memories,null,service);}
 @Test void realObserveHookReusesCommonSnapshotAndWorkerGrantBoundaryHandlesNextPrefix(){
  var memories=org.mockito.Mockito.mock(RewardMaterialProvider.class);
  org.mockito.Mockito.when(memories.memory(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(Map.of("state","EMPTY","candidates",List.of()));
  var pet=pet();var attendance=attendance(memories);
  tx.executeWithoutResult(status->attendance.observe(pet,now(),false));
  org.mockito.Mockito.verify(memories,org.mockito.Mockito.times(1)).memory(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
  tx.executeWithoutResult(status->attendance.observe(pet,now(),false));
  assertThat(db.queryForObject("SELECT count(*) FROM zzal_custom_bundle_job",Integer.class)).isEqualTo(2);
  String frozen=db.queryForObject("SELECT snapshot_json FROM zzal_custom_bundle_job WHERE bundle_version='bundle-2'",String.class);
  assertThat(frozen).contains("CUSTOM_SUBJECT_SHEET_REFERENCE_V4","images/sheet.webp","identity","memory");
  long j=db.queryForObject("SELECT id FROM zzal_custom_bundle_job WHERE bundle_version='bundle-2'",Long.class);
  for(int day=8;day<16;day++)cycle(day,5);Instant late=START.plusSeconds(15*86400L+3600);
  db.update("UPDATE zzal_custom_bundle_job SET state='GENERATED',manifest_hash='ready' WHERE id=?",j);for(int order=1;order<=8;order++)asset(j,order,true);
  tx.executeWithoutResult(status->attendance.grantReady(1L,late));tx.executeWithoutResult(status->attendance.grantReady(1L,late));
  var view=attendance.view(pet);assertThat(view.ownedRewards().stream().filter(x->x.slotKey().startsWith("bundle-2:")).map(AttendanceRewards.OwnedReward::motionOrder).sorted().toList()).containsExactly(1,2,3,4,5,6,7,8);
  assertThat(db.queryForObject("SELECT count(*) FROM zzal_attendance_reward_slot WHERE state='GRANTED'",Integer.class)).isEqualTo(16);
 }
 @Test void sleepingAndTravelVisitsDoNotQueueOrReadFreshMemory(){
  var memories=org.mockito.Mockito.mock(RewardMaterialProvider.class);var attendance=attendance(memories);var pet=pet();
  org.springframework.test.util.ReflectionTestUtils.setField(pet,"sleepKind",com.lore.zzal.pet.SleepKind.NIGHT);
  tx.executeWithoutResult(status->attendance.observe(pet,now(),false));
  org.springframework.test.util.ReflectionTestUtils.setField(pet,"sleepKind",null);
  org.springframework.test.util.ReflectionTestUtils.setField(pet,"tripStartedAt",now());
  tx.executeWithoutResult(status->attendance.observe(pet,now(),false));
  org.mockito.Mockito.verifyNoInteractions(memories);
  assertThat(db.queryForObject("SELECT count(*) FROM zzal_custom_bundle_job",Integer.class)).isEqualTo(1);
 }

}
