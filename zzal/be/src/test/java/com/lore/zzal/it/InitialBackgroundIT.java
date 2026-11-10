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
  var service=new AttendanceService(jdbc,new PublishedBackgroundCatalog(changed.toString()),context.getBean(RewardMaterialProvider.class));readyAndDayTwo(id,service);
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

 static final Instant LEGACY_NOW=Instant.parse("2026-10-17T03:00:00Z");
 Long legacy(int priorDays,String selection){
  Long owner=newUserId();
  Long id=transactions.execute(s->{var p=petRepository.save(ZzalPet.draft(owner,newUploadedImageKey(owner),LEGACY_NOW));p.character("기존 친구",null,null,null,null,null,LEGACY_NOW);p.markAlive("synthetic","identity",LEGACY_NOW);p.skipTutorial(LEGACY_NOW);p.changeBackground(selection);return p.getId();});
  for(int day=0;day<priorDays;day++)jdbc.update("INSERT INTO zzal_awake_visit(pet_id,program_version,cycle_key,event_id,observed_at) VALUES(?,?,?,?,?)",id,AttendanceService.PROGRAM,day==0?"INITIAL":"OLD:"+day,"old:"+id+":"+day,Timestamp.from(LEGACY_NOW));
  return id;
 }
 void visit(Long id,AttendanceService service){transactions.executeWithoutResult(s->service.observe(petRepository.findByIdForUpdate(id).orElseThrow(),LEGACY_NOW,false));}
 String selected(Long id){return jdbc.queryForObject("SELECT background FROM zzal_pet WHERE id=?",String.class,id);}
 List<String> owned(Long id){return jdbc.queryForList("SELECT asset_key FROM zzal_attendance_reward_slot WHERE pet_id=? AND state='GRANTED' ORDER BY slot_key",String.class,id);}
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(ints={0,1,3})
 void legacyRoomVisitRepairsMissingInitialEvenAfterDayOneWithoutRepeatingIt(int days){
  Long id=legacy(days,"room");visit(id,attendance);
  assertThat(selected(id)).endsWith("03.png");
  assertThat(owned(id)).contains("images/review/backgrounds/03.png");
  if(days>=2)assertThat(owned(id)).containsExactlyInAnyOrder("images/review/backgrounds/03.png","images/review/backgrounds/04.png");
  var before=jdbc.queryForList("SELECT * FROM zzal_attendance_reward_slot WHERE pet_id=? ORDER BY slot_key",id);
  visit(id,attendance);
  assertThat(jdbc.queryForList("SELECT * FROM zzal_attendance_reward_slot WHERE pet_id=? ORDER BY slot_key",id)).isEqualTo(before);
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(strings={"room","images/review/backgrounds/03.png"})
 void historicalDayTwoGrantAndSelectionStayUntouchedWhileMissingCompanionIsAdded(String choice){
  Long id=legacy(3,choice);
  jdbc.update("INSERT INTO zzal_attendance_reward_slot(pet_id,program_version,slot_key,eligible_day,state,asset_key,granted_at) VALUES(?,?,'background',2,'GRANTED','images/review/backgrounds/03.png',?)",id,AttendanceService.PROGRAM,Timestamp.from(LEGACY_NOW.minusSeconds(50)));
  var oldGrant=jdbc.queryForMap("SELECT * FROM zzal_attendance_reward_slot WHERE pet_id=? AND slot_key='background'",id);
  visit(id,attendance);visit(id,attendance);
  assertThat(selected(id)).isEqualTo(choice);
  assertThat(owned(id)).containsExactlyInAnyOrder("images/review/backgrounds/03.png","images/review/backgrounds/04.png");
  assertThat(jdbc.queryForMap("SELECT * FROM zzal_attendance_reward_slot WHERE pet_id=? AND slot_key='background'",id)).isEqualTo(oldGrant);
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
 void lateLegacyDirectChoiceRetainsOwnershipAndSelection(boolean alreadyGranted){
  Long id=legacy(3,"forest");
  if(alreadyGranted)jdbc.update("INSERT INTO zzal_attendance_reward_slot(pet_id,program_version,slot_key,eligible_day,state,asset_key,granted_at) VALUES(?,?,'background',2,'GRANTED','images/review/backgrounds/03.png',?)",id,AttendanceService.PROGRAM,Timestamp.from(LEGACY_NOW));
  visit(id,attendance);visit(id,attendance);
  assertThat(selected(id)).isEqualTo("forest");assertThat(owned(id)).contains("forest").hasSize(alreadyGranted?2:1);
  if(alreadyGranted)assertThat(owned(id)).contains("images/review/backgrounds/03.png");
 }
 @Test void emptyCatalogRetriesOnLaterVisitWithoutInventingOrReplacingOwnership(){
  Long id=legacy(1,"room");var empty=new AttendanceService(jdbc,new PublishedBackgroundCatalog("[]"),context.getBean(RewardMaterialProvider.class));
  visit(id,empty);assertThat(selected(id)).isEqualTo("room");assertThat(owned(id)).isEmpty();
  visit(id,attendance);assertThat(selected(id)).endsWith("03.png");assertThat(owned(id)).hasSize(1);
  transactions.executeWithoutResult(s->{var p=petRepository.findByIdForUpdate(id).orElseThrow();p.choosePersonality(List.of(),"미래 우주");p.changeBackground("room");});
  visit(id,attendance);assertThat(selected(id)).isEqualTo("room");assertThat(owned(id)).containsExactly("images/review/backgrounds/03.png");
 }
 @Test void historicalDayTwoWithMissingClusterKeepsExistingGrantAndSelectionPending(){
  Long id=legacy(2,"room");
  jdbc.update("INSERT INTO zzal_attendance_reward_slot(pet_id,program_version,slot_key,eligible_day,state,asset_key,granted_at) VALUES(?,?,'background',2,'GRANTED','images/removed.png',?)",id,AttendanceService.PROGRAM,Timestamp.from(LEGACY_NOW));
  visit(id,attendance);assertThat(selected(id)).isEqualTo("room");assertThat(owned(id)).containsExactly("images/removed.png");
 }
 @Test void staleEligiblePreviewCannotGrantFromAnotherClusterAfterInitialRepair(){
  Long id=legacy(2,"room");
  jdbc.update("INSERT INTO zzal_attendance_reward_slot(pet_id,program_version,slot_key,eligible_day,state,asset_key) VALUES(?,?,'background',2,'ELIGIBLE','images/review/backgrounds/01.png')",id,AttendanceService.PROGRAM);
  visit(id,attendance);
  assertThat(owned(id)).containsExactlyInAnyOrder("images/review/backgrounds/03.png","images/review/backgrounds/04.png");
 }
}
