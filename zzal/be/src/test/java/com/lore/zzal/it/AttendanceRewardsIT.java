package com.lore.zzal.it;
import com.lore.zzal.attendance.*;
import com.lore.zzal.pet.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@ZzalIntegrationTest
@TestPropertySource(properties={"app.zzal.attendance.cli-enabled=true", "app.zzal.python.bin=${LORE_ATTENDANCE_TEST_PYTHON:python3}",
 "app.zzal.attendance.published-backgrounds=[{\"key\":\"images/fixture/modern.webp\",\"url\":\"/images/fixture/modern.webp\",\"world\":\"modern_daily\",\"tags\":[],\"published\":true,\"autoCheck\":\"PASS\",\"humanReview\":\"UNREVIEWED\"}]"})
class AttendanceRewardsIT extends ZzalItSupport {
 static final Instant T=Instant.parse("2026-10-16T03:00:00Z");
 @Autowired AttendanceService attendance;
 @Autowired BundleJobWorker worker;
 ZzalPet make(Long owner,Instant at,boolean tutorial){return transactions.execute(s->{
  ZzalPet p=petRepository.save(ZzalPet.draft(owner,"images/fixture/source",at));p.character("여울",null,null,null,null,null,at);p.markAlive("images/fixture/sheet","외형 원문",at);
  if(!tutorial)p.skipTutorial(at);return p;
 });}
 int day(long id){return jdbc.queryForObject("SELECT count(*) FROM zzal_awake_visit WHERE pet_id=?",Integer.class,id);}
 @Test void legacyEnrollmentNightOnlyAndAtomicDayTwo(){
  Long owner=newUserId();var p=make(owner,T,false);
  petService.refresh(owner,p.getId(),T);petService.refresh(owner,p.getId(),T.plusSeconds(1));
  assertThat(day(p.getId())).isEqualTo(1);
  // Persisted NAP fixture exercises the real manual wake path does not create a NIGHT cycle.
  transactions.executeWithoutResult(s->{var locked=petRepository.findByIdForUpdate(p.getId()).orElseThrow();ReflectionTestUtils.setField(locked,"sleepKind",SleepKind.NAP);ReflectionTestUtils.setField(locked,"sleptAt",T);});
  var napResult=petService.wake(owner,p.getId(),T.plusSeconds(3602)).pet();
  assertThat(napResult.isSleeping()).as("NAP manual wake").isFalse();
  assertThat(napResult.isInTutorial()).as("tutorial remains complete").isFalse();
  assertThat(day(p.getId())).isEqualTo(1);
  // Settle traverses a real AUTO_SLEEP/AUTO_WAKE pair; no client cycle string is supplied.
  var next=petService.refresh(owner,p.getId(),T.plus(Duration.ofDays(1)));
  assertThat(next.getPhase()).as("next phase").isEqualTo(PetPhase.ALIVE);
  assertThat(next.isInTutorial()).as("next tutorial").isFalse();
  assertThat(next.getAttendanceNightCycleAt()).as("next slept=%s woke=%s settled=%s",next.getSleptAt(),next.getWokeAt(),next.getSettledAt()).isNotNull();assertThat(day(p.getId())).isEqualTo(2);
  assertThat(next.getAttendanceRewards().firstBundleState()).isEqualTo("PENDING");
  try {var frozen=json.readTree(jdbc.queryForObject("SELECT snapshot_json FROM zzal_custom_bundle_job WHERE pet_id=?",String.class,p.getId()));assertThat(frozen.has("legacySource")).isTrue();assertThat(frozen.path("identity").asText()).isEqualTo("외형 원문");}
  catch(Exception failure){throw new AssertionError(failure);}
  assertThat(next.getAttendanceRewards().background().owned()).hasSize(1);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM zzal_custom_bundle_job WHERE pet_id=?",Integer.class,p.getId())).isEqualTo(1);
  petService.refresh(owner,p.getId(),T.plus(Duration.ofDays(1)).plusSeconds(2));assertThat(day(p.getId())).isEqualTo(2);
  petService.changeBackground(owner,p.getId(),"images/fixture/modern.webp",T.plus(Duration.ofDays(1)).plusSeconds(3));
  petService.changeBackground(owner,p.getId(),"room",T.plus(Duration.ofDays(1)).plusSeconds(4));
  assertThatThrownBy(()->petService.changeBackground(owner,p.getId(),"images/not-owned.webp",T.plus(Duration.ofDays(1)).plusSeconds(5))).isInstanceOf(com.lore.common.exception.BusinessException.class);
 }
 @Test void concurrentVisitsRegisterOneCycleAndOneJob()throws Exception{
  Long owner=newUserId();var p=make(owner,T,false);petService.refresh(owner,p.getId(),T);
  try(var pool=java.util.concurrent.Executors.newFixedThreadPool(4)){
   var futures=new ArrayList<java.util.concurrent.Future<?>>();
   for(int i=0;i<4;i++)futures.add(pool.submit(()->petService.refresh(owner,p.getId(),T.plus(Duration.ofDays(1)))));
   for(var f:futures)f.get();
  }
  assertThat(day(p.getId())).isEqualTo(2);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM zzal_custom_bundle_job WHERE pet_id=?",Integer.class,p.getId())).isEqualTo(1);
 }
 @Test void legacySelectedBackgroundSurvivesSwitchToRoom(){
  Long owner=newUserId();var p=make(owner,T,false);
  transactions.executeWithoutResult(s->petRepository.findByIdForUpdate(p.getId()).orElseThrow().changeBackground("forest"));
  petService.refresh(owner,p.getId(),T);petService.changeBackground(owner,p.getId(),"room",T.plusSeconds(1));
  var selected=petService.changeBackground(owner,p.getId(),"forest",T.plusSeconds(2));
  assertThat(selected.pet().getBackground()).isEqualTo("forest");
  assertThat(selected.pet().getAttendanceRewards().background().owned()).extracting(AttendanceRewards.OwnedBackground::key).contains("forest");
 }
 @Test void noPublishedCandidatesDoNotInventOwnership(){
  var catalog=new PublishedBackgroundCatalog("[]");Long owner=newUserId();var p=make(owner,T,false);
  assertThat(catalog.select(p)).isEmpty();
  AttendanceService emptyService=new AttendanceService(jdbc,catalog,context.getBean(RewardMaterialProvider.class));
  transactions.executeWithoutResult(s->{var locked=petRepository.findByIdForUpdate(p.getId()).orElseThrow();emptyService.observe(locked,T,false);locked.settle(T.plus(Duration.ofDays(1)));emptyService.observe(locked,T.plus(Duration.ofDays(1)),false);});
  assertThat(jdbc.queryForObject("SELECT count(*) FROM zzal_attendance_reward_slot WHERE state='GRANTED'",Integer.class)).isZero();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM zzal_attendance_reward_slot WHERE state='ELIGIBLE'",Integer.class)).isEqualTo(1);
 }
 @Test void explicitFileFailureCannotGrantAndAmbiguousClaimCannotRetry()throws Exception{
  Long owner=newUserId();var p=make(owner,T,false);for(int d=0;d<3;d++)petService.refresh(owner,p.getId(),T.plus(Duration.ofDays(d)));
  long job=jdbc.queryForObject("SELECT id FROM zzal_custom_bundle_job",Long.class);
  jdbc.update("UPDATE zzal_custom_bundle_job SET state='CLAIMED',claim_token='uncertain' WHERE id=?",job);
  assertThat(worker.run(job,"retry",List.of("/bin/false"),Files.createTempDirectory("unused"))).isFalse();
  Path output=Files.createTempDirectory("broken-assets");Files.writeString(output.resolve("bad.webp"),"broken");
  var assets=new ArrayList<Map<String,Object>>();for(int i=1;i<=8;i++)assets.add(Map.of("motionOrder",i,"localFile","bad.webp","assetKey","images/bad-"+i+".webp","sha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("broken".getBytes())),"humanReview","UNREVIEWED","published",true));
  String receipt=json.writeValueAsString(Map.of("executionId","fixture-broken","assets",assets));
  assertThatThrownBy(()->worker.complete(job,"uncertain",receipt,output,T)).isInstanceOf(IllegalArgumentException.class);
  assertThat(jdbc.queryForObject("SELECT state FROM zzal_custom_bundle_job WHERE id=?",String.class,job)).isEqualTo("FAILED");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM zzal_custom_bundle_asset WHERE auto_check='FAIL'",Integer.class)).isEqualTo(8);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM zzal_attendance_reward_slot WHERE slot_key LIKE 'motion-%' AND state='GRANTED'",Integer.class)).isZero();
 }
 @Test void tutorialCompletionAtNightIsDayOneAndTutorialVisitsExcluded(){
  Long owner=newUserId();Instant night=Instant.parse("2026-10-16T15:00:00Z");var p=make(owner,night,true);
  transactions.executeWithoutResult(s->{var locked=petRepository.findByIdForUpdate(p.getId()).orElseThrow();ReflectionTestUtils.setField(locked,"firstIntroStep",locked.firstIntroIndex(FirstIntro.Step.FINISH_NOTICE));});
  var finishPreview=petService.refresh(owner,p.getId(),night).getAttendanceRewards();assertThat(day(p.getId())).isZero();
  assertThat(finishPreview.background().preview()).isNotNull();assertThat(finishPreview.background().preview().key()).isEqualTo("images/fixture/modern.webp");
  assertThat(finishPreview.background().owned()).isEmpty();assertThat(finishPreview.eligibleUnfulfilledCount()).isZero();
  petService.tutorialDone(owner,p.getId(),night);petService.tutorialDone(owner,p.getId(),night);
  assertThat(day(p.getId())).isEqualTo(1);
  var dayTwo=petService.refresh(owner,p.getId(),Instant.parse("2026-10-17T01:00:00Z"));assertThat(day(p.getId())).isEqualTo(2);
  assertThat(dayTwo.getAttendanceRewards().background().owned()).extracting(AttendanceRewards.OwnedBackground::key).contains("images/fixture/modern.webp");
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(ints={2,3})
 void firstFinishNoticeResponseIncludesPersistedPreviewWithoutAnExtraRefresh(int version)throws Exception{
  Long owner=newUserId();var p=make(owner,T,true);
  FirstIntro.Step previous=version==2?FirstIntro.Step.DIARY_INTRO:FirstIntro.Step.PERSONALITY;
  transactions.executeWithoutResult(s->{var locked=petRepository.findByIdForUpdate(p.getId()).orElseThrow();
   ReflectionTestUtils.setField(locked,"firstIntroVersion",version);
   ReflectionTestUtils.setField(locked,"firstIntroStep",locked.firstIntroIndex(previous));});
  var response=postAs(owner,"/api/zzal/v1/me/pets/"+p.getId()+"/tutorial/seen",Map.of("topic",previous.name()));
  assertThat(response.getResponse().getStatus()).isEqualTo(200);
  var detail=body(response).path("data");
  assertThat(detail.path("firstIntro").path("key").asText()).isEqualTo("FINISH_NOTICE");
  assertThat(detail.path("attendanceRewards").path("background").path("preview").path("key").asText())
   .isEqualTo("images/fixture/modern.webp");
  assertThat(detail.path("attendanceRewards").path("background").path("owned").size()).isZero();
  assertThat(day(p.getId())).isZero();
  assertThat(jdbc.queryForObject("SELECT asset_key FROM zzal_attendance_reward_slot WHERE pet_id=? AND slot_key='background'",String.class,p.getId()))
   .isEqualTo("images/fixture/modern.webp");
  assertThat(petRepository.findById(p.getId()).orElseThrow().getClockStartedAt()).isNull();
  var retry=postAs(owner,"/api/zzal/v1/me/pets/"+p.getId()+"/tutorial/seen",Map.of("topic",previous.name()));
  assertThat(retry.getResponse().getStatus()).isEqualTo(200);
  assertThat(body(retry).path("data").path("attendanceRewards").path("background").path("preview").path("key").asText())
   .isEqualTo("images/fixture/modern.webp");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM zzal_attendance_reward_slot WHERE pet_id=? AND slot_key='background'",Integer.class,p.getId())).isEqualTo(1);
 }
 @Test void transactionRollbackCannotLeaveDayTwoWithoutJob(){
  Long owner=newUserId();var p=make(owner,T,false);petService.refresh(owner,p.getId(),T);
  assertThatThrownBy(()->transactions.executeWithoutResult(s->{petService.refresh(owner,p.getId(),T.plus(Duration.ofDays(1)));throw new IllegalStateException("abort");})).isInstanceOf(IllegalStateException.class);
  assertThat(day(p.getId())).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM zzal_custom_bundle_job",Integer.class)).isZero();
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
 void harmlessCliClaimReceiptReplayLateGrantAndRejection(boolean partialFailure)throws Exception{
  Long owner=newUserId();var p=make(owner,T,false);
  for(int d=0;d<7;d++)petService.refresh(owner,p.getId(),T.plus(Duration.ofDays(d)));
  Long job=jdbc.queryForObject("SELECT id FROM zzal_custom_bundle_job WHERE pet_id=?",Long.class,p.getId());
  assertThat(worker.dryRun()).hasSize(1);assertThat(jdbc.queryForObject("SELECT state FROM zzal_custom_bundle_job WHERE id=?",String.class,job)).isEqualTo("PENDING");
  Path output=Files.createTempDirectory("attendance-cli-fixture-");Path script=output.resolve("fake.py");
  Files.writeString(script,"""
from PIL import Image,ImageDraw
import sys,json,hashlib,pathlib
out=pathlib.Path(sys.argv[2]);assets=[]
for order in range(1,9):
 frames=[]
 for x in (2,6):
  f=Image.new('RGBA',(24,24),(0,0,0,0));ImageDraw.Draw(f).rectangle((x,2,x+10,14),fill=(255,0,0,255));frames.append(f)
 path=out/f'{order}.webp';frames[0].save(path,save_all=True,append_images=frames[1:],duration=100,loop=0,lossless=True)
 assets.append(dict(motionOrder=order,localFile=path.name,assetKey=f'images/fixture/motion-{order}.webp',sha256=hashlib.sha256(path.read_bytes()).hexdigest(),humanReview='REJECTED' if order==2 else 'UNREVIEWED',published=True))
(out/'manifest.json').write_text(json.dumps(dict(executionId='fixture-cli-no-network',assets=assets)))
""");
  if(partialFailure)Files.writeString(script,Files.readString(script).replace(" assets.append", " if order==8:path.write_bytes(b'broken image')\n assets.append"));
  if(partialFailure)assertThatThrownBy(()->worker.run(job,"token",List.of(System.getenv().getOrDefault("LORE_ATTENDANCE_TEST_PYTHON","python3"),script.toString()),output)).isInstanceOf(IllegalArgumentException.class);
  else assertThat(worker.run(job,"token",List.of(System.getenv().getOrDefault("LORE_ATTENDANCE_TEST_PYTHON","python3"),script.toString()),output)).isTrue();
  assertThat(worker.run(job,"other",List.of("/bin/false"),output)).isFalse();
  String receipt=Files.readString(output.resolve("manifest.json"));worker.complete(job,"token",receipt,output,T.plus(Duration.ofDays(7)));
  assertThat(jdbc.queryForObject("SELECT count(*) FROM zzal_custom_bundle_asset",Integer.class)).isEqualTo(8);
  assertThat(jdbc.queryForObject("SELECT state FROM zzal_custom_bundle_job WHERE id=?",String.class,job)).isEqualTo(partialFailure?"FAILED":"GENERATED");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM zzal_custom_bundle_asset WHERE auto_check='FAIL'",Integer.class)).isEqualTo(partialFailure?1:0);
  // Attendance 7 earns five slots; explicitly rejected second file stays unfulfilled.
  assertThat(jdbc.queryForObject("SELECT count(*) FROM zzal_attendance_reward_slot WHERE slot_key LIKE 'motion-%' AND state='GRANTED'",Integer.class)).isEqualTo(4);
  attendance.grantReady(p.getId(),T.plus(Duration.ofDays(7)));assertThat(jdbc.queryForObject("SELECT count(*) FROM zzal_attendance_reward_slot WHERE slot_key LIKE 'motion-%' AND state='GRANTED'",Integer.class)).isEqualTo(4);
  assertThatThrownBy(()->worker.complete(job,"token",receipt+" ",output,T)).isInstanceOf(IllegalStateException.class);
  for(int d=7;d<9;d++)petService.refresh(owner,p.getId(),T.plus(Duration.ofDays(d)));
  assertThat(jdbc.queryForObject("SELECT count(*) FROM zzal_attendance_reward_slot WHERE slot_key='motion-5' AND state='GRANTED'",Integer.class)).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM zzal_custom_bundle_job",Integer.class)).isEqualTo(1);
 }
 /** Red dot: a grant counts as new until the gifts screen is opened; the initial legacy background never does. */
 @Test void newRewardCountClearsOnSeenAndReturnsOnNextGrant(){
  Long owner=newUserId();var p=make(owner,T,false);
  jdbc.update("INSERT INTO zzal_attendance_reward_slot(pet_id,program_version,slot_key,eligible_day,state,asset_key,granted_at) VALUES(?,?,'legacy-background',0,'GRANTED','forest',?) ON CONFLICT DO NOTHING",p.getId(),AttendanceService.PROGRAM,java.sql.Timestamp.from(T));
  var first=petService.refresh(owner,p.getId(),T);
  assertThat(first.getAttendanceRewards().newRewardCount()).as("legacy background is not a new gift").isZero();
  jdbc.update("INSERT INTO zzal_attendance_reward_slot(pet_id,program_version,slot_key,eligible_day,state,asset_key,granted_at) VALUES(?,?,'motion-1',3,'GRANTED','images/fixture/m1.webp',?)",p.getId(),AttendanceService.PROGRAM,java.sql.Timestamp.from(T.plusSeconds(10)));
  assertThat(petService.refresh(owner,p.getId(),T.plusSeconds(20)).getAttendanceRewards().newRewardCount()).as("after grant").isEqualTo(1);
  petService.markRewardsSeen(owner,p.getId(),T.plusSeconds(30));
  petService.markRewardsSeen(owner,p.getId(),T.plusSeconds(25)); // a late retry never rewinds
  var seen=petService.refresh(owner,p.getId(),T.plusSeconds(40));
  assertThat(seen.getAttendanceRewards().newRewardCount()).as("after seen").isZero();
  assertThat(seen.getRewardsSeenAt()).isEqualTo(T.plusSeconds(30));
  jdbc.update("INSERT INTO zzal_attendance_reward_slot(pet_id,program_version,slot_key,eligible_day,state,asset_key,granted_at) VALUES(?,?,'motion-2',4,'GRANTED','images/fixture/m2.webp',?)",p.getId(),AttendanceService.PROGRAM,java.sql.Timestamp.from(T.plusSeconds(50)));
  var next=petService.refresh(owner,p.getId(),T.plusSeconds(60));
  assertThat(next.getAttendanceRewards().newRewardCount()).as("after next grant").isEqualTo(1);
  assertThat(next.getAttendanceRewards().unreadDiaryCount()).isZero();
 }
}
