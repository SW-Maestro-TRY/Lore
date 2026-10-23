package com.lore.zzal.it;
import com.lore.zzal.pet.*;
import com.lore.zzal.pet.persona.FirstMeeting;
import com.lore.zzal.chat.line.*;
import com.lore.zzal.chat.presence.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

@ZzalIntegrationTest @Import(FirstNightChatGraceIT.DelayedFake.class)
class FirstNightChatGraceIT extends ZzalItSupport {
 static final AtomicLong DELAY=new AtomicLong();static final AtomicInteger CALLS=new AtomicInteger();
 @TestConfiguration static class DelayedFake {
  @Bean @Primary FakeChatLineClient graceFakeClient(){return new FakeChatLineClient(){
   @Override public Completion complete(String system,String user,String model,Duration timeout)throws Exception{
    CALLS.incrementAndGet();if(DELAY.get()>0)Thread.sleep(DELAY.get());
    return new Completion("{\"line\":\"조금 더 이야기하자.\",\"motion\":\"reply\",\"memory_deltas\":[]}",java.math.BigDecimal.ZERO,0,0);
   }};}
 }
 @Autowired ChatPresenceService presence;
 record Room(Long owner,Long id,Instant at){String path(){return "/api/zzal/v1/me/pets/"+id;}}
 @BeforeEach void fakeReset(){DELAY.set(0);CALLS.set(0);}
 Room pending(Instant at){Long owner=newUserId();Long id=transactions.execute(s->{var p=ZzalPet.draft(owner,newUploadedImageKey(owner),at);p.character("시온",null,null,"현대",null,null,at);p.markAlive("sheet","identity",at);p.beginFirstMeeting();p.advanceFirstMeeting(FirstMeeting.State.COMPLETE);p.devSetFirstIntro(4,FirstIntro.Step.REVIEW_SEEN,at);return petRepository.save(p).getId();});motionSeeder.seed(id,at);pinClock(id,at);return new Room(owner,id,at);}
 ZzalPet pet(Room r){return petRepository.findById(r.id).orElseThrow();}
 void complete(Room r){petService.tutorialSeen(r.owner,r.id,FirstIntro.Step.REVIEW_SEEN,Instant.now());}
 int visits(Room r){return jdbc.queryForObject("SELECT count(*) FROM zzal_awake_visit WHERE pet_id=?",Integer.class,r.id);}
 @Test void concurrentCompletionPersistsOneDeadlineAndNoNightCycle()throws Exception{
  var r=pending(kstToday(23,5));try(var pool=Executors.newFixedThreadPool(3)){
   var start=new CountDownLatch(1);var fs=new ArrayList<Future<?>>();for(int i=0;i<3;i++)fs.add(pool.submit(()->{start.await();complete(r);return null;}));start.countDown();for(var f:fs)f.get(20,TimeUnit.SECONDS);
  }
  var deadline=pet(r).getFirstNightChatDeadlineAt();assertThat(deadline).isBetween(r.at.plusSeconds(1800),r.at.plusSeconds(1802));
  assertThat(pet(r).isSleeping()).isFalse();assertThat(pet(r).getAttendanceNightCycleAt()).isNull();assertThat(visits(r)).isEqualTo(1);
  complete(r);assertThat(pet(r).getFirstNightChatDeadlineAt()).isEqualTo(deadline);
 }
 @Test void sevenCapEndsAwakeWithoutNewNightAndDtoSaysNoSleepAtEnd()throws Exception{
  var r=pending(kstToday(6,50));complete(r);var view=getJson(r.owner,r.path()).path("data");
  assertThat(view.path("firstNightChatGrace").path("active").asBoolean()).isTrue();assertThat(view.path("firstNightChatGrace").path("willSleepAtEnd").asBoolean()).isFalse();
  pinClock(r.id,kstToday(7,0));getJson(r.owner,r.path());assertThat(pet(r).isSleeping()).isFalse();assertThat(pet(r).getFirstNightChatEndedAt()).isEqualTo(kstToday(7,0));assertThat(pet(r).getAttendanceNightCycleAt()).isNull();assertThat(visits(r)).isEqualTo(1);
 }
 @Test void absentPastTenSettlesDeadlineNightThenOneMorningAttendance()throws Exception{
  var r=pending(kstToday(23,0));complete(r);var deadline=pet(r).getFirstNightChatDeadlineAt();
  var morning=r.at.atZone(ZzalRules.ZONE).toLocalDate().plusDays(1).atTime(10,0).atZone(ZzalRules.ZONE).toInstant();
  pinClock(r.id,morning.plusSeconds(3600));getJson(r.owner,r.path());getJson(r.owner,r.path());
  assertThat(pet(r).isSleeping()).isFalse();assertThat(pet(r).getFirstNightChatEndedAt()).isEqualTo(deadline);assertThat(pet(r).getAttendanceNightCycleAt()).isEqualTo(morning);assertThat(visits(r)).isEqualTo(2);
 }
 @Test void manualSleepEndsGraceAndMorningWakeDoesNotRegrant()throws Exception{
  var r=pending(kstToday(23,0));complete(r);var deadline=pet(r).getFirstNightChatDeadlineAt();petService.sleep(r.owner,r.id,Instant.now());
  assertThat(pet(r).getSleepKind()).isEqualTo(SleepKind.NIGHT);assertThat(pet(r).getFirstNightChatEndedAt()).isNotNull();
  var morning=r.at.atZone(ZzalRules.ZONE).toLocalDate().plusDays(1).atTime(7,0).atZone(ZzalRules.ZONE).toInstant();pinClock(r.id,morning);petService.wake(r.owner,r.id,Instant.now());complete(r);
  assertThat(pet(r).getFirstNightChatDeadlineAt()).isEqualTo(deadline);assertThat(pet(r).firstNightChatGraceActive(morning)).isFalse();assertThat(visits(r)).isEqualTo(2);
 }
 Map<String,Object> request(Room r,String id)throws Exception{var c=getJson(r.owner,r.path()+"/chat").path("data");return Map.of("text","이야기하자","sessionId",c.path("session").path("id").asLong(),"expectedRound",c.path("session").path("round").asInt(),"requestId",id);}
 @Test void savedAwakeReceiptReplaysReplyWithCurrentSleepingPetWithoutRewritingReceipt()throws Exception{
  var r=pending(kstToday(23,0));complete(r);String id=UUID.randomUUID().toString();var request=request(r,id);
  var first=postAs(r.owner,r.path()+"/chat/CONTINUOUS/answer",request);assertThat(first.getResponse().getStatus()).isEqualTo(200);
  String frozen=jdbc.queryForObject("SELECT response_json FROM zzal_chat_delivery WHERE pet_id=?",String.class,r.id);int before=CALLS.get();
  pinClock(r.id,pet(r).getFirstNightChatDeadlineAt().plusSeconds(1));var retry=postAs(r.owner,r.path()+"/chat/CONTINUOUS/answer",request);assertThat(retry.getResponse().getStatus()).isEqualTo(200);
  var data=body(retry).path("data");assertThat(data.path("pet").path("clock").path("sleeping").asBoolean()).isTrue();assertThat(data.path("pet").path("firstNightChatGrace").path("active").asBoolean()).isFalse();assertThat(data.path("chatReply")).isEqualTo(body(first).path("data").path("chatReply"));
  assertThat(CALLS.get()).isEqualTo(before);assertThat(jdbc.queryForObject("SELECT response_json FROM zzal_chat_delivery WHERE pet_id=?",String.class,r.id)).isEqualTo(frozen);
 }
 @Test void acceptedRequestFinishesAfterDeadlineWithSleepingSnapshotAndNoExtraCall()throws Exception{
  var r=pending(kstToday(23,0));complete(r);String id=UUID.randomUUID().toString();var request=request(r,id);
  var until=pet(r).now(Instant.now()).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plusSeconds(2);jdbc.update("UPDATE zzal_pet SET first_night_chat_deadline_at=? WHERE id=?",java.sql.Timestamp.from(until),r.id);DELAY.set(2500);int before=CALLS.get();
  var answer=postAs(r.owner,r.path()+"/chat/CONTINUOUS/answer",request);assertThat(answer.getResponse().getStatus()).as(answer.getResponse().getContentAsString()).isEqualTo(200);
  assertThat(body(answer).path("data").path("pet").path("clock").path("sleeping").asBoolean()).isTrue();assertThat(pet(r).getSleptAt()).isEqualTo(until);assertThat(CALLS.get()).isEqualTo(before+1);DELAY.set(0);
 }
 @Test void duplicatePresenceAfterExpiryCannotReshowSavedSpeech(){
  var r=pending(kstToday(23,0));complete(r);var event=new ChatPresenceRequest(UUID.randomUUID(),UUID.randomUUID(),1,ChatPresenceRequest.Event.ENTER,ChatPresenceRequest.EntryKind.OPEN);
  presence.accept(r.owner,r.id,event,Instant.now());int before=CALLS.get();pinClock(r.id,pet(r).getFirstNightChatDeadlineAt().plusSeconds(1));var reply=presence.accept(r.owner,r.id,event,Instant.now());
  assertThat(reply.outcome()).isEqualTo("UNAVAILABLE");assertThat(reply.chat()).isNull();assertThat(CALLS.get()).isEqualTo(before);assertThat(pet(r).isSleeping()).isTrue();
 }
 @Test void additiveMigrationLeavesHistoricalRowsNullAndDoesNotRewriteProgress()throws Exception{
  try(var connection=jdbc.getDataSource().getConnection()){
   try(var statement=connection.createStatement()){
    statement.execute("CREATE TEMP TABLE zzal_pet(id bigint PRIMARY KEY,first_intro_version int,first_intro_step int)");
    try{
     statement.execute("INSERT INTO zzal_pet VALUES(1,2,14),(2,3,7),(3,4,10)");
     org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,new org.springframework.core.io.ClassPathResource("db/migration/V20261017_0020__zzal_first_night_chat_grace.sql"));
     try(var rows=statement.executeQuery("SELECT id,first_intro_version,first_intro_step,first_night_chat_deadline_at,first_night_chat_ended_at FROM zzal_pet ORDER BY id")){
      int[][] expected={{1,2,14},{2,3,7},{3,4,10}};int index=0;while(rows.next()){assertThat(rows.getInt(1)).isEqualTo(expected[index][0]);assertThat(rows.getInt(2)).isEqualTo(expected[index][1]);assertThat(rows.getInt(3)).isEqualTo(expected[index][2]);assertThat(rows.getObject(4)).isNull();assertThat(rows.getObject(5)).isNull();index++;}assertThat(index).isEqualTo(3);
     }
    }finally{statement.execute("DROP TABLE pg_temp.zzal_pet");}
   }
  }
 }

 @Test void oldPracticeWakeReceiptReturnsCurrentSleepingPetWithoutReplayingWake()throws Exception{
  var r=pending(kstToday(23,0));complete(r);var deadline=pet(r).getFirstNightChatDeadlineAt();pinClock(r.id,deadline.plusSeconds(1));
  var reply=postAs(r.owner,r.path()+"/wake",Map.of("firstIntroStep","NAP_WAKE"));assertThat(reply.getResponse().getStatus()).isEqualTo(200);
  assertThat(body(reply).path("data").path("clock").path("sleeping").asBoolean()).isTrue();assertThat(pet(r).getSleptAt()).isEqualTo(deadline);
  postAs(r.owner,r.path()+"/wake",Map.of("firstIntroStep","NAP_WAKE"));assertThat(pet(r).getSleptAt()).isEqualTo(deadline);assertThat(pet(r).getAttendanceNightCycleAt()).isNull();assertThat(visits(r)).isEqualTo(1);
 }

}
