package com.lore.zzal.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.persona.AuthoredPersona;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Unit boundary tests with an in-memory JDBC ledger double. No DB/CLI/model.
 * SQL execution/locking/transactions remain covered only by separately run ITs. */
class FirstSetPolicyTest {
 static final Instant NOW=Instant.parse("2026-10-17T03:00:00Z");
 record Slot(int day,String state,String asset,Instant granted){}
 static class Ledger {
  int day=1; boolean ready; String snapshot,inputHash; int jobs;
  final Map<String,Slot> slots=new LinkedHashMap<>();
  final List<String> writes=new ArrayList<>();
  final JdbcTemplate db=mock(JdbcTemplate.class, invocation->{
   String method=invocation.getMethod().getName();
   if(method.equals("toString"))return "ledger-double";
   Object[] raw=invocation.getRawArguments();String sql=(String)raw[0];
   Object[] args=raw[raw.length-1] instanceof Object[] a?a:new Object[0];
   if(method.equals("queryForObject")) {
    if(sql.startsWith("SELECT count(*) FROM zzal_awake_visit"))return day;
    if(sql.startsWith("SELECT EXISTS(SELECT 1 FROM zzal_custom_bundle_job"))return jobs>0;
    // Red-dot counters (view): new gifts since the never-opened gifts screen, and unread diary entries.
    if(sql.startsWith("SELECT count(*) FROM zzal_attendance_reward_slot")&&sql.contains("state='GRANTED'"))return (int)slots.values().stream().filter(s->s.state.equals("GRANTED")).count();
    if(sql.startsWith("SELECT count(*) FROM zzal_diary_entry"))return 0;
    if(sql.startsWith("SELECT count(*) FROM zzal_attendance_reward_slot"))return (int)slots.values().stream().filter(s->s.state.equals("ELIGIBLE")&&s.day<=day).count();
   }
   if(method.equals("queryForList"))return jobs>0?List.of(ready?"GENERATED":"PENDING"):List.of();
   if(method.equals("query")) {
    if(sql.startsWith("UPDATE zzal_attendance_reward_slot s SET state='GRANTED'")) {
     assertThat(sql).contains("s.state='ELIGIBLE'","a.auto_check='PASS'","a.published=true","a.human_review<>'REJECTED'","j.manifest_hash IS NOT NULL","RETURNING s.slot_key");
     var granted=new ArrayList<String>();
     if(ready) slots.replaceAll((key,old)->{if(key.startsWith("motion-")&&old.state.equals("ELIGIBLE")) {
      granted.add(key);return new Slot(old.day,"GRANTED","images/"+key,((Timestamp)args[0]).toInstant());}return old;});
     return granted;
    }
    return List.of();
   }
   if(method.equals("update")) {
    writes.add(sql);
    if(sql.startsWith("INSERT INTO zzal_awake_visit"))return 0; // this fixture's INITIAL cycle already exists
    if(sql.startsWith("INSERT INTO zzal_custom_bundle_job")) {
     if(jobs==0){jobs++;snapshot=(String)args[3];inputHash=(String)args[4];return 1;}return 0;
    }
    if(sql.startsWith("INSERT INTO zzal_attendance_reward_slot")) {
     String key=(String)args[2];int eligible=(Integer)args[3];Slot old=slots.get(key);
     assertThat(sql).contains("eligible_day=LEAST(","state='ELIGIBLE'");
     if(old==null)slots.put(key,new Slot(eligible,"ELIGIBLE",null,null));
     else if(old.state.equals("ELIGIBLE"))slots.put(key,new Slot(Math.min(old.day,eligible),old.state,old.asset,old.granted));
     return 1;
    }
    if(sql.startsWith("UPDATE zzal_attendance_reward_slot s SET state='GRANTED'")) {
     assertThat(sql).contains("s.state='ELIGIBLE'","a.auto_check='PASS'","a.published=true","a.human_review<>'REJECTED'","j.manifest_hash IS NOT NULL");
     if(ready)slots.replaceAll((key,old)->key.startsWith("motion-")&&old.state.equals("ELIGIBLE")?new Slot(old.day,"GRANTED","images/"+key,((Timestamp)args[0]).toInstant()):old);
     return 1;
    }
    // Empty background catalog cannot supply an asset; production UPDATE must not invent one.
    if(sql.startsWith("UPDATE zzal_attendance_reward_slot SET state='GRANTED'"))return 0;
   }
   throw new AssertionError("Unexpected JDBC boundary: "+method+" "+sql);
  });
  long motionCount(String state){return slots.entrySet().stream().filter(e->e.getKey().startsWith("motion-")&&e.getValue().state.equals(state)).count();}
 }
 ZzalPet pet(){
  var pet=mock(ZzalPet.class);when(pet.getId()).thenReturn(19L);when(pet.isAlive()).thenReturn(true);
  when(pet.getName()).thenReturn("시온");when(pet.getWorld()).thenReturn("도시");when(pet.getNote()).thenReturn("오래된 원문");
  when(pet.getIdentityText()).thenReturn("외형 원문");when(pet.getSourceImageKey()).thenReturn("images/source.webp");
  when(pet.getAuthoredPersona()).thenReturn(new AuthoredPersona(1,List.of(new AuthoredPersona.Field("persona",List.of("시크"),"차분한 존댓말 원문",false))));
  return pet;
 }
 AttendanceService service(Ledger l,RewardMaterialProvider memories){return new AttendanceService(l.db,new PublishedBackgroundCatalog("[]"),memories);}
 @Test void dayTwoQueuesApprovedMemoryAndPreservesAuthoredVisualSource()throws Exception{
  var l=new Ledger();var memory=mock(RewardMaterialProvider.class);var s=service(l,memory);var p=pet();
  when(memory.memory(eq(p),anyString(),eq(NOW))).thenReturn(Map.of("state","AVAILABLE","candidates",List.of("승인된 캐릭터 장기기억")));
  s.observe(p,NOW,false);assertThat(l.jobs).isZero();assertThat(l.motionCount("ELIGIBLE")).isZero();
  l.day=2;s.observe(p,NOW,false);assertThat(l.jobs).isEqualTo(1);assertThat(l.motionCount("ELIGIBLE")).isZero();
  var source=new ObjectMapper().readTree(l.snapshot);
  assertThat(source.path("memory").toString()).contains("승인된 캐릭터 장기기억");assertThat(source.has("memories")).isFalse();
  assertThat(source.path("materialPolicy").asText()).isEqualTo(BundleCommandAdapter.SHEET_REFERENCE_POLICY);
  assertThat(source.path("name").asText()).isEqualTo("시온");assertThat(source.path("identity").asText()).isEqualTo("외형 원문");
  assertThat(source.path("sourceImageKey").asText()).isEqualTo("images/source.webp");assertThat(source.path("persona").toString()).contains("차분한 존댓말 원문");
  assertThat(source.path("legacySource").path("note").asText()).isEqualTo("오래된 원문");
  assertThat(l.inputHash).isEqualTo(AttendanceService.hash(l.snapshot));verify(memory,times(1)).memory(eq(p),anyString(),eq(NOW));
 }
 @Test void dayThreeSixTenAccrueOneFourEightRightsEvenWhenNoAssetsAreReady(){
  var l=new Ledger();var s=service(l,mock(RewardMaterialProvider.class));var p=pet();
  l.day=3;s.observe(p,NOW,false);assertThat(l.motionCount("ELIGIBLE")).isEqualTo(1);
  l.day=6;s.observe(p,NOW.plusSeconds(1),false);assertThat(l.motionCount("ELIGIBLE")).isEqualTo(4);
  l.day=10;s.observe(p,NOW.plusSeconds(2),false);assertThat(l.motionCount("ELIGIBLE")).isEqualTo(8);
  assertThat(l.motionCount("GRANTED")).isZero();assertThat(l.day).isEqualTo(10);assertThat(l.jobs).isEqualTo(1);
 }
 @Test void lateAssetsFulfilAccumulatedRightsExactlyOnceAndDoNotRewriteAttendance(){
  var l=new Ledger();var s=service(l,mock(RewardMaterialProvider.class));var p=pet();l.day=6;
  s.observe(p,NOW,false);assertThat(l.motionCount("ELIGIBLE")).isEqualTo(4);
  l.ready=true;s.grantReady(19L,NOW.plusSeconds(5));assertThat(l.motionCount("GRANTED")).isEqualTo(4);
  var granted=new LinkedHashMap<>(l.slots);s.observe(p,NOW.plusSeconds(10),false);s.grantReady(19L,NOW.plusSeconds(20));
  assertThat(l.slots).isEqualTo(granted);assertThat(l.day).isEqualTo(6);
  l.day=10;s.observe(p,NOW.plusSeconds(30),false);assertThat(l.motionCount("GRANTED")).isEqualTo(8);
  assertThat(l.slots.get("motion-1").granted).isEqualTo(NOW.plusSeconds(5));
 }
 @Test void advancingOnlyUnpaidRightsLeavesGrantedOwnershipAndLegacySnapshotsUntouched(){
  var l=new Ledger();l.day=6;l.jobs=1;l.snapshot="{\"memory\":\"historical frozen bytes\"}";l.inputHash=AttendanceService.hash(l.snapshot);
  l.slots.put("motion-1",new Slot(3,"GRANTED","images/already-owned",NOW.minusSeconds(5)));
  l.slots.put("motion-4",new Slot(7,"ELIGIBLE",null,null));
  var oldOwned=l.slots.get("motion-1");var memory=mock(RewardMaterialProvider.class);var p=pet();
  when(p.getNote()).thenReturn("설정이 바뀌어도 이미고정된snapshot재작성X");service(l,memory).observe(p,NOW,false);
  assertThat(l.slots.get("motion-4").day).isEqualTo(6);assertThat(l.slots.get("motion-1")).isEqualTo(oldOwned);
  assertThat(l.snapshot).isEqualTo("{\"memory\":\"historical frozen bytes\"}");assertThat(l.inputHash).isEqualTo(AttendanceService.hash(l.snapshot));
  assertThat(l.writes).noneMatch(sql->sql.startsWith("DELETE")||sql.startsWith("UPDATE zzal_awake_visit")||sql.startsWith("UPDATE zzal_custom_bundle_job"));verifyNoInteractions(memory);
 }
 @Test void firstCliBoundaryStripsLegacyMemoryAndNamesHashesByExactBytes()throws Exception{
  String frozen="{\"schemaVersion\":1,\"persona\":{\"raw\":\"성격 원문\"},\"identity\":\"외형\",\"sourceImageKey\":\"images/source\",\"memory\":{\"secret\":\"사용자사실\"},\"memories\":[\"다른기억\"],\"unknown\":\"추가재료\"}";
  var input=BundleCommandAdapter.firstSetInput(frozen);var json=new ObjectMapper().readTree(input.json());
  assertThat(json.path("persona").path("raw").asText()).isEqualTo("성격 원문");assertThat(json.path("identity").asText()).isEqualTo("외형");
  assertThat(input.json()).doesNotContain("memory","memories","사용자사실","다른기억","unknown","추가재료");
  assertThat(input.sourceSnapshotHash()).isEqualTo(AttendanceService.hash(frozen));assertThat(input.generatorInputHash()).isEqualTo(AttendanceService.hash(input.json())).isNotEqualTo(input.sourceSnapshotHash());
  assertThat(frozen).contains("사용자사실");assertThat(BundleCommandAdapter.firstSetInput(frozen)).isEqualTo(input);
 }
 @Test void actualCliInputFileAndProvenanceKeepSourceAndGeneratorHashesDistinct(@org.junit.jupiter.api.io.TempDir java.nio.file.Path output)throws Exception{
  String frozen="{\"persona\":{\"rawText\":\"원문\"},\"identity\":\"외형\",\"memory\":{\"fact\":\"옛사용자기억\"}}";
  var input=BundleCommandAdapter.writeFirstSetInput(frozen,output);
  String bytes=java.nio.file.Files.readString(output.resolve("input.json"));
  var provenance=new ObjectMapper().readTree(java.nio.file.Files.readString(output.resolve("input-provenance.json")));
  assertThat(bytes).isEqualTo(input.json()).contains("원문","외형").doesNotContain("memory","옛사용자기억");
  assertThat(provenance.path("sourceSnapshotHash").asText()).isEqualTo(AttendanceService.hash(frozen));
  assertThat(provenance.path("generatorInputHash").asText()).isEqualTo(AttendanceService.hash(bytes));
  assertThat(java.nio.file.Files.exists(output.resolve("manifest.json"))).isFalse(); // no process or generation
 }
 @Test void v2ProjectionIncludesOnlyFrozenApprovedMemoryAndKeepsOriginalHash()throws Exception {
  String frozen="{\"schemaVersion\":2,\"materialPolicy\":\"CUSTOM_PERSONA_IDENTITY_LONG_TERM_V2\",\"persona\":\"원문\",\"identity\":\"외형\",\"memory\":{\"state\":\"AVAILABLE\",\"candidates\":[\"승인기억\"]},\"rawChat\":\"전체대화금지\"}";
  var input=BundleCommandAdapter.firstSetInput(frozen);
  assertThat(input.json()).contains("승인기억","원문","외형").doesNotContain("rawChat","전체대화금지");
  assertThat(input.sourceSnapshotHash()).isEqualTo(AttendanceService.hash(frozen));
  assertThat(input.generatorInputHash()).isEqualTo(AttendanceService.hash(input.json()));
 }
 @Test void emptyApprovedMemoryStillQueuesAndUnavailableMemoryDoesNotInventEmptySnapshot(){
  var l=new Ledger();l.day=2;var p=pet();var memory=mock(RewardMaterialProvider.class);
  when(memory.memory(eq(p),anyString(),eq(NOW))).thenReturn(Map.of("state","EMPTY","candidates",List.of()));
  service(l,memory).observe(p,NOW,false);assertThat(l.snapshot).contains("EMPTY","외형 원문");
  var failed=new Ledger();failed.day=2;var unavailable=mock(RewardMaterialProvider.class);
  when(unavailable.memory(eq(p),anyString(),eq(NOW))).thenThrow(new IllegalStateException("UNAVAILABLE"));
  assertThatThrownBy(()->service(failed,unavailable).observe(p,NOW,false)).hasMessage("UNAVAILABLE");
  assertThat(failed.jobs).isZero();
 }
 @Test void cliRejectsNonObjectInputsBeforeAnyExecution(){
  assertThatThrownBy(()->BundleCommandAdapter.firstSetInput("[]")).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->BundleCommandAdapter.firstSetInput("not-json")).isInstanceOf(Exception.class);
 }
}
