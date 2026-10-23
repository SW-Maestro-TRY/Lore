package com.lore.zzal.attendance;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ClassPathResource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="LORE_OWNED_CUSTOM_DB_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:55420/lore_owned_custom_[0-9]+_test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OwnedCustomMotionPostgresTest {
 JdbcTemplate db;CustomMotionOwnershipService service;
 @BeforeAll void schema()throws Exception{
  var ds=new DriverManagerDataSource(System.getenv("LORE_OWNED_CUSTOM_DB_URL"),System.getenv("LORE_TEST_DB_USERNAME"),System.getenv("LORE_TEST_DB_PASSWORD"));db=new JdbcTemplate(ds);
  assertThat(db.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='public'",Integer.class)).isZero();
  db.execute("CREATE TABLE zzal_pet(id bigint PRIMARY KEY,user_id bigint NOT NULL)");
  try(var c=ds.getConnection()){ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/V20261016_2290__zzal_attendance_rewards.sql"));ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/V20261017_0040__zzal_custom_bundle_memory_sources.sql"));}
  service=new CustomMotionOwnershipService(db);
 }
 @BeforeEach void fixture()throws Exception{
  db.execute("TRUNCATE zzal_custom_bundle_job,zzal_pet RESTART IDENTITY CASCADE");db.update("INSERT INTO zzal_pet(id,user_id) VALUES(1,11)");
  String frozen=new ObjectMapper().writeValueAsString(Map.of("motionPlan",CustomPlannerTest.fixture()));
  String snapshot="{\"materialPolicy\":\"CUSTOM_SUBJECT_SHEET_REFERENCE_V4\",\"baseReferenceRound\":3}";
  long job=db.queryForObject("INSERT INTO zzal_custom_bundle_job(pet_id,program_version,bundle_version,snapshot_json,input_hash,state,manifest_hash,created_at,source_plan_json,source_plan_hash) VALUES(1,'first-v1','bundle-1',?,?,'GENERATED','manifest',now(),?,?) RETURNING id",Long.class,snapshot,AttendanceService.hash(snapshot),frozen,AttendanceService.hash(frozen));
  for(int order=1;order<=8;order++){String key="images/zzal/custom/run/"+CustomGridPrompt.SLOTS.get(order-1)+".webp";long asset=db.queryForObject("INSERT INTO zzal_custom_bundle_asset(job_id,motion_order,asset_key,asset_hash,auto_check,human_review,published) VALUES(?, ?,?,'hash','PASS','UNREVIEWED',true) RETURNING id",Long.class,job,order,key);db.update("INSERT INTO zzal_attendance_reward_slot(pet_id,program_version,slot_key,eligible_day,state,asset_id,asset_key,granted_at) VALUES(1,'first-v1',?,3,'GRANTED',?,?,now())","motion-"+order,asset,key);}
 }
 @Test void ownerOnlyDeliveredMetadataKeepsSemanticSeparateFromPhysicalPose(){
  assertThat(service.owned(12L,1L)).isEmpty();var owned=service.owned(11L,1L);assertThat(owned).hasSize(8);
  var hello=owned.get(5);assertThat(hello.semantic()).isEqualTo("hello");assertThat(hello.anchorPoseKey()).isEqualTo(CustomGridPrompt.SLOTS.get(5));assertThat(hello.anchorsKey()).isEqualTo("images/zzal/custom/run/anchors.json");assertThat(hello.baseReferenceRound()).isEqualTo(3);
  assertThat(CustomChatActions.allowed(owned)).extracting(com.lore.zzal.chat.prompt.ChatContext.ActionOption::id).containsExactly("base",owned.get(5).id(),owned.get(6).id());
  assertThat(db.queryForObject("SELECT count(*) FROM zzal_attendance_reward_slot WHERE state='GRANTED'",Integer.class)).isEqualTo(8);
 }
 @Test void pendingUnpublishedRejectedFailAndWrongKeyNeverBecomeOwnedMetadata(){
  db.update("UPDATE zzal_attendance_reward_slot SET state='ELIGIBLE' WHERE slot_key='motion-1'");db.update("UPDATE zzal_custom_bundle_asset SET published=false WHERE motion_order=2");db.update("UPDATE zzal_custom_bundle_asset SET human_review='REJECTED' WHERE motion_order=3");db.update("UPDATE zzal_custom_bundle_asset SET auto_check='FAIL' WHERE motion_order=4");db.update("UPDATE zzal_attendance_reward_slot SET asset_key='images/wrong.webp' WHERE slot_key='motion-5'");
  assertThat(service.owned(11L,1L)).extracting(OwnedCustomMotion::order).containsExactly(6,7,8);
 }
 @Test void failedJobAndCorruptOrHistoricalPlanAreNotGuessed(){
  db.update("UPDATE zzal_custom_bundle_job SET state='FAILED'");assertThat(service.owned(11L,1L)).isEmpty();
  db.update("UPDATE zzal_custom_bundle_job SET state='GENERATED',source_plan_hash='bad'");assertThat(service.owned(11L,1L)).isEmpty();
  db.update("UPDATE zzal_custom_bundle_job SET source_plan_json='{}',source_plan_hash=?",AttendanceService.hash("{}"));assertThat(service.owned(11L,1L)).isEmpty();
 }
}
