package com.lore.zzal.attendance;

import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.persona.AuthoredPersona;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual Postgres SQL/transactions, isolated attendance schema; no app/server/CLI.
 * Product pet entity lifecycle is represented by a mock and a minimal FK/lock row.
 * The test refuses every URL except a newly provisioned task-specific local DB. */
@EnabledIfEnvironmentVariable(named="LORE_ATTENDANCE_POLICY_DB_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:55417/lore_attendance_policy_20261017_[0-9]+_test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FirstSetPostgresTest {
 static final Instant T=Instant.parse("2026-10-17T03:00:00Z");
 JdbcTemplate db; TransactionTemplate tx; AttendanceService attendance;RewardMaterialProvider memories;ZzalPet pet;
 @BeforeAll void isolatedSchema()throws Exception{
  String url=System.getenv("LORE_ATTENDANCE_POLICY_DB_URL");
  var ds=new DriverManagerDataSource(url,System.getenv("LORE_ATTENDANCE_POLICY_DB_USERNAME"),System.getenv("LORE_ATTENDANCE_POLICY_DB_PASSWORD"));
  db=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
  assertThat(db.queryForObject("SELECT current_database()",String.class)).matches("lore_attendance_policy_20261017_[0-9]+_test");
  assertThat(db.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='public'",Integer.class)).as("must be a fresh task-only database").isZero();
  db.execute("CREATE TABLE zzal_pet(id bigint PRIMARY KEY)");
  try(var connection=ds.getConnection()){
   ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V20261016_2290__zzal_attendance_rewards.sql"));
   ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V20261017_0030__zzal_initial_background_cluster.sql"));
   ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V20261017_0040__zzal_custom_bundle_memory_sources.sql"));
  }
 }
 @BeforeEach void fixture(){
  db.execute("TRUNCATE zzal_awake_visit,zzal_custom_bundle_asset,zzal_custom_bundle_job,zzal_attendance_reward_slot,zzal_pet RESTART IDENTITY CASCADE");
  db.update("INSERT INTO zzal_pet(id) VALUES(19)");
  memories=mock(RewardMaterialProvider.class);attendance=new AttendanceService(db,new PublishedBackgroundCatalog("[]"),memories);
  pet=mock(ZzalPet.class);when(pet.getId()).thenReturn(19L);when(pet.isAlive()).thenReturn(true);when(pet.getName()).thenReturn("시온");
  when(pet.getIdentityText()).thenReturn("외형 원문");when(pet.getSourceImageKey()).thenReturn("images/source.webp");
  when(pet.getAuthoredPersona()).thenReturn(new AuthoredPersona(1,List.of(new AuthoredPersona.Field("persona",List.of("시크"),"차분한 존댓말 원문",false))));
 }
 void day(int n){for(int i=1;i<=n;i++)db.update("INSERT INTO zzal_awake_visit(pet_id,program_version,cycle_key,event_id,observed_at) VALUES(19,?,?,?,?) ON CONFLICT DO NOTHING",AttendanceService.PROGRAM,i==1?"INITIAL":"FIXTURE:"+i,"fixture:"+i,Timestamp.from(T));}
 void observe(Instant at){tx.executeWithoutResult(status->{db.queryForObject("SELECT id FROM zzal_pet WHERE id=19 FOR UPDATE",Long.class);attendance.observe(pet,at,false);});}
 int count(String table){return db.queryForObject("SELECT count(*) FROM "+table,Integer.class);}
 int motions(String state){return db.queryForObject("SELECT count(*) FROM zzal_attendance_reward_slot WHERE slot_key LIKE 'motion-%' AND state=?",Integer.class,state);}
 Map<String,Object> job(){return db.queryForMap("SELECT snapshot_json,input_hash FROM zzal_custom_bundle_job WHERE pet_id=19");}
 @Test void actualDayThreeSixTenAccrueRightsWithoutWaitingForAssets(){
  day(3);observe(T);assertThat(motions("ELIGIBLE")).isEqualTo(1);
  day(6);observe(T.plusSeconds(1));assertThat(motions("ELIGIBLE")).isEqualTo(4);
  day(10);observe(T.plusSeconds(2));assertThat(motions("ELIGIBLE")).isEqualTo(8);assertThat(motions("GRANTED")).isZero();
  assertThat(count("zzal_awake_visit")).isEqualTo(10);assertThat(count("zzal_custom_bundle_job")).isEqualTo(1);
 }
 @Test void secondDaySnapshotUsesApprovedMemoryOnceAndRemainsImmutable()throws Exception{
  when(memories.memory(eq(pet),anyString(),eq(T.plusSeconds(1)))).thenReturn(Map.of("state","EMPTY","candidates",List.of()));
  day(1);observe(T);assertThat(count("zzal_custom_bundle_job")).isZero();
  day(2);observe(T.plusSeconds(1));var before=job();
  var source=new com.fasterxml.jackson.databind.ObjectMapper().readTree((String)before.get("snapshot_json"));
  assertThat(source.path("memory").path("state").asText()).isEqualTo("EMPTY");assertThat(source.path("persona").toString()).contains("차분한 존댓말 원문");
  assertThat(source.path("identity").asText()).isEqualTo("외형 원문");assertThat(source.path("sourceImageKey").asText()).isEqualTo("images/source.webp");
  assertThat(before.get("input_hash")).isEqualTo(AttendanceService.hash((String)before.get("snapshot_json")));
  when(pet.getIdentityText()).thenReturn("후에 바뀐 외형");when(pet.getAuthoredPersona()).thenReturn(null);day(6);observe(T.plusSeconds(2));
  assertThat(job()).isEqualTo(before);verify(memories,times(1)).memory(eq(pet),anyString(),eq(T.plusSeconds(1)));
 }
 @Test void legacyEligibleMovesEarlierWhileGrantedAndFrozenSnapshotStayByteIdentical(){
  day(6);String old="{\"memory\":{\"fact\":\"historical\"},\"identity\":\"old\"}";
  db.update("INSERT INTO zzal_custom_bundle_job(pet_id,program_version,bundle_version,snapshot_json,input_hash,created_at) VALUES(19,?,'bundle-1',?,?,?)",AttendanceService.PROGRAM,old,AttendanceService.hash(old),Timestamp.from(T));
  db.update("INSERT INTO zzal_attendance_reward_slot(pet_id,program_version,slot_key,eligible_day,state,asset_key,granted_at) VALUES(19,?,'motion-1',3,'GRANTED','images/owned',?)",AttendanceService.PROGRAM,Timestamp.from(T.minusSeconds(3)));
  db.update("INSERT INTO zzal_attendance_reward_slot(pet_id,program_version,slot_key,eligible_day) VALUES(19,?,'motion-4',7)",AttendanceService.PROGRAM);
  var owned=db.queryForMap("SELECT * FROM zzal_attendance_reward_slot WHERE slot_key='motion-1'");var before=job();observe(T);
  assertThat(db.queryForObject("SELECT eligible_day FROM zzal_attendance_reward_slot WHERE slot_key='motion-4'",Integer.class)).isEqualTo(6);
  assertThat(db.queryForMap("SELECT * FROM zzal_attendance_reward_slot WHERE slot_key='motion-1'")).isEqualTo(owned);assertThat(job()).isEqualTo(before);
  assertThat(count("zzal_awake_visit")).isEqualTo(6);verifyNoInteractions(memories);
 }
 void readyAssets(){
  Long id=db.queryForObject("SELECT id FROM zzal_custom_bundle_job WHERE pet_id=19",Long.class);
  db.update("UPDATE zzal_custom_bundle_job SET state='GENERATED',manifest_hash=? WHERE id=?","a".repeat(64),id);
  for(int i=1;i<=8;i++)db.update("INSERT INTO zzal_custom_bundle_asset(job_id,motion_order,asset_key,asset_hash,auto_check,human_review,published) VALUES(?,?,?,?,'PASS','UNREVIEWED',true)",id,i,"images/test-motion-"+i,"b".repeat(64));
 }
 @Test void lateAssetReadinessGrantsAllEarnedRightsOnceAndKeepsQualityFilters(){
  day(6);observe(T);assertThat(motions("ELIGIBLE")).isEqualTo(4);readyAssets();
  db.update("UPDATE zzal_custom_bundle_asset SET human_review='REJECTED' WHERE motion_order=2");attendance.grantReady(19L,T.plusSeconds(5));
  assertThat(motions("GRANTED")).isEqualTo(3);assertThat(motions("ELIGIBLE")).isEqualTo(1);
  var before=db.queryForList("SELECT * FROM zzal_attendance_reward_slot ORDER BY slot_key");observe(T.plusSeconds(10));attendance.grantReady(19L,T.plusSeconds(20));
  assertThat(db.queryForList("SELECT * FROM zzal_attendance_reward_slot ORDER BY slot_key")).isEqualTo(before);
  db.update("UPDATE zzal_custom_bundle_asset SET human_review='APPROVED' WHERE motion_order=2");attendance.grantReady(19L,T.plusSeconds(30));assertThat(motions("GRANTED")).isEqualTo(4);
  day(10);observe(T.plusSeconds(40));assertThat(motions("GRANTED")).isEqualTo(8);assertThat(count("zzal_awake_visit")).isEqualTo(10);
 }
 @Test void rollbackCannotPersistDayTwoWithoutJobOrMoveExistingOwnership(){
  day(1);var visits=db.queryForList("SELECT * FROM zzal_awake_visit");
  assertThatThrownBy(()->tx.executeWithoutResult(status->{db.queryForObject("SELECT id FROM zzal_pet WHERE id=19 FOR UPDATE",Long.class);day(2);attendance.observe(pet,T,false);assertThat(count("zzal_custom_bundle_job")).isEqualTo(1);throw new IllegalStateException("rollback fixture");})).isInstanceOf(IllegalStateException.class);
  assertThat(db.queryForList("SELECT * FROM zzal_awake_visit")).isEqualTo(visits);assertThat(count("zzal_custom_bundle_job")).isZero();assertThat(count("zzal_attendance_reward_slot")).isZero();
 }
 @Test void concurrentLockedObservationsKeepSingleJobAndSingleRights()throws Exception{
  day(6);try(var pool=Executors.newFixedThreadPool(4)){
   var start=new CountDownLatch(1);var work=new ArrayList<Future<?>>();
   for(int i=0;i<4;i++)work.add(pool.submit(()->{start.await();observe(T);return null;}));start.countDown();for(var f:work)f.get(20,TimeUnit.SECONDS);
  }
  assertThat(count("zzal_awake_visit")).isEqualTo(6);assertThat(count("zzal_custom_bundle_job")).isEqualTo(1);assertThat(motions("ELIGIBLE")).isEqualTo(4);verifyNoInteractions(memories);
 }
}
