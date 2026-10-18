package com.lore.zzal.it;
import com.lore.zzal.attendance.*;
import com.lore.zzal.pet.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.nio.file.*;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
@ZzalIntegrationTest
class InitialBackgroundIT extends ZzalItSupport {
 static String catalog(){try{return Files.readString(Path.of("zzal/be/src/test/resources/attendance/review-background-catalog.json"));}catch(Exception e){throw new IllegalStateException(e);}}
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){r.add("app.zzal.attendance.published-backgrounds",InitialBackgroundIT::catalog);}
 @Autowired AttendanceService attendance;
 Long named(String world)throws Exception{
  Long owner=newUserId();Long id=transactions.execute(s->petRepository.save(ZzalPet.draft(owner,newUploadedImageKey(owner),Instant.now())).getId());
  var result=postAs(owner,"/api/zzal/v1/me/pets/draft/"+id+"/character",Map.of("name","배경 친구","authoredPersona",Map.of("version",1,"fields",List.of(Map.of("fieldId","world","chips",List.of(),"rawText",world,"skipped",world.isBlank())))));
  assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);return id;
 }
 void readyAndDayTwo(Long id,AttendanceService service){
  transactions.executeWithoutResult(s->{var p=petRepository.findByIdForUpdate(id).orElseThrow();Instant t=Instant.parse("2026-10-17T03:00:00Z");p.markAlive("synthetic","identity",t);p.devSetFirstIntro(4,FirstIntro.Step.FINISH_NOTICE,t);service.observe(p,t,false);p.startClock(t);p.finishFirstIntroReview(t);service.observe(p,t,true);jdbc.update("INSERT INTO zzal_awake_visit(pet_id,program_version,cycle_key,event_id,observed_at) VALUES(?,?,'TEST:SECOND',?,?)",id,AttendanceService.PROGRAM,"initial-bg-test:"+id,Timestamp.from(t));service.observe(p,t.plusSeconds(1),false);});
 }
 @Test void characterHttpGrantsInitialOnceAndSecondDayAddsDifferentOwnedAssetWithoutOverwritingChoice()throws Exception{
  Long id=named("");assertThat(jdbc.queryForObject("SELECT background FROM zzal_pet WHERE id=?",String.class,id)).endsWith("03.png");
  String cluster=jdbc.queryForObject("SELECT source_cluster_key FROM zzal_attendance_reward_slot WHERE pet_id=? AND slot_key='legacy-background'",String.class,id);assertThat(cluster).isEqualTo("modern_daily::주거·일상");
  transactions.executeWithoutResult(s->{var p=petRepository.findByIdForUpdate(id).orElseThrow();p.changeBackground("room");attendance.initializeBackground(p,Instant.now());});
  assertThat(jdbc.queryForObject("SELECT background FROM zzal_pet WHERE id=?",String.class,id)).isEqualTo("room");
  // Catalog renames the initial entry's middle category; its stored selection provenance must survive.
  var changed=new com.fasterxml.jackson.databind.ObjectMapper().readTree(catalog());for(var e:changed)if(e.path("key").asText().endsWith("03.png"))((com.fasterxml.jackson.databind.node.ArrayNode)e.path("tags")).set(1,new com.fasterxml.jackson.databind.node.TextNode("后来改名"));
  var service=new AttendanceService(jdbc,new PublishedBackgroundCatalog(changed.toString()),null);readyAndDayTwo(id,service);
  assertThat(jdbc.queryForObject("SELECT asset_key FROM zzal_attendance_reward_slot WHERE pet_id=? AND slot_key='background' AND state='GRANTED'",String.class,id)).endsWith("04.png");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM zzal_attendance_reward_slot WHERE pet_id=? AND slot_key IN ('legacy-background','background') AND state='GRANTED'",Integer.class,id)).isEqualTo(2);
  assertThat(jdbc.queryForObject("SELECT source_cluster_key FROM zzal_attendance_reward_slot WHERE pet_id=? AND slot_key='legacy-background'",String.class,id)).isEqualTo(cluster);
  assertThat(jdbc.queryForObject("SELECT background FROM zzal_pet WHERE id=?",String.class,id)).isEqualTo("room");
 }
 @Test void explicitKoreanCountryRejectsJapaneseAlternativeAndKeepsDayTwoPending()throws Exception{
  Long id=named("조선 시대 한양 한옥");assertThat(jdbc.queryForObject("SELECT background FROM zzal_pet WHERE id=?",String.class,id)).endsWith("13.png");readyAndDayTwo(id,attendance);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM zzal_attendance_reward_slot WHERE pet_id=? AND slot_key='background' AND state='ELIGIBLE' AND asset_key IS NULL",Integer.class,id)).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM zzal_attendance_reward_slot WHERE pet_id=? AND slot_key='legacy-background' AND state='GRANTED'",Integer.class,id)).isEqualTo(1);
 }
}
