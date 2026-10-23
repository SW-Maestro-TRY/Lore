package com.lore.zzal.pet;
import com.lore.zzal.PetFixture;
import com.lore.zzal.pet.persona.FirstMeeting;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
class FirstNightChatGraceTest {
 Instant kst(String t){return LocalDateTime.parse("2026-10-17T"+t).atZone(ZzalRules.ZONE).toInstant();}
 ZzalPet pending(Instant at){var p=PetFixture.hatching(1L,"시온",null,"key",at.minusSeconds(60));p.markAlive("sheet","identity",at);p.beginFirstMeeting();p.advanceFirstMeeting(FirstMeeting.State.COMPLETE);p.devSetFirstIntro(4,FirstIntro.Step.FINISH_NOTICE,at);p.startClock(at);return p;}
 @Test void clockDtoReportsActualGraceSleepDeadlineRatherThanCurrentNightTime(){
  var at=kst("23:00:00");var p=pending(at);p.finishFirstIntroReview(at);
  var d=com.lore.zzal.pet.dto.PetResponses.Detail.fromWithoutPieces(p,null,at.plusSeconds(60),new com.lore.zzal.motion.MotionCatalog("","","v1"));
  assertThat(d.clock().autoSleepAt()).isEqualTo(at.plusSeconds(1800));
 }
 @Test void clockDtoSevenCapReportsNextNormalNightInsteadOfMorningSleep(){
  var at=kst("06:50:00");var p=pending(at);p.finishFirstIntroReview(at);
  var d=com.lore.zzal.pet.dto.PetResponses.Detail.fromWithoutPieces(p,null,at,new com.lore.zzal.motion.MotionCatalog("","","v1"));
  assertThat(d.clock().autoSleepAt()).isEqualTo(kst("23:00:00"));
  p.settle(kst("07:00:00"));
  assertThat(com.lore.zzal.pet.dto.PetResponses.Detail.fromWithoutPieces(p,null,kst("07:00:00"),new com.lore.zzal.motion.MotionCatalog("","","v1")).clock().autoSleepAt()).isEqualTo(kst("23:00:00"));
 }
 @Test void onlyFirstNightCompletionStartsOneFixedThirtyMinutesAcrossMidnight(){
  var at=kst("23:55:00");var p=pending(at);assertThat(p.firstNightChatGraceEligible(at)).isTrue();
  assertThat(FirstIntro.of(p,at).completionWillSleep()).isFalse();p.finishFirstIntroReview(at);
  assertThat(p.getFirstNightChatDeadlineAt()).isEqualTo(at.plusSeconds(1800));assertThat(p.firstNightChatGraceActive(at)).isTrue();
  var deadline=p.getFirstNightChatDeadlineAt();p.finishFirstIntroReview(at.plusSeconds(900));p.settle(at.plusSeconds(1200));
  assertThat(p.getFirstNightChatDeadlineAt()).isEqualTo(deadline);assertThat(p.isSleeping()).isFalse();
  p.settle(deadline);assertThat(p.getSleptAt()).isEqualTo(deadline);assertThat(p.getSleepKind()).isEqualTo(SleepKind.NIGHT);
  assertThat(p.getFirstNightChatEndedAt()).isEqualTo(deadline);assertThat(p.firstNightChatGraceActive(deadline)).isFalse();
 }
 @Test void sevenCapNeverCreatesANewNightAndCountersDoNotReset(){
  var at=kst("06:50:00");var p=pending(at);ReflectionTestUtils.setField(p,"todaySnacks",2);p.finishFirstIntroReview(at);
  assertThat(p.getFirstNightChatDeadlineAt()).isEqualTo(kst("07:00:00"));p.settle(kst("07:00:00"));
  assertThat(p.isSleeping()).isFalse();assertThat(p.firstNightChatGraceActive(kst("07:00:00"))).isFalse();
  assertThat(p.getAttendanceNightCycleAt()).isNull();assertThat(ReflectionTestUtils.getField(p,"todaySnacks")).isEqualTo(2);
 }
 @Test void longAbsenceUsesDeadlineSleepThenTenAutoWake(){
  var at=kst("23:00:00");var p=pending(at);p.finishFirstIntroReview(at);var deadline=at.plusSeconds(1800);
  var tomorrowTen=at.atZone(ZzalRules.ZONE).toLocalDate().plusDays(1).atTime(10,0).atZone(ZzalRules.ZONE).toInstant();
  p.settle(tomorrowTen.plusSeconds(3600));assertThat(p.isSleeping()).isFalse();assertThat(p.getFirstNightChatEndedAt()).isEqualTo(deadline);
  assertThat(p.getAttendanceNightCycleAt()).isEqualTo(tomorrowTen);assertThat(p.getWokeAt()).isEqualTo(tomorrowTen);
 }
 @Test void manualSleepTerminatesForeverWithoutExtendingDeadline(){
  var at=kst("23:00:00");var p=pending(at);p.finishFirstIntroReview(at);var end=at.plusSeconds(60);
  assertThat(p.canSleep(end)).isTrue();p.sleep(end);assertThat(p.getFirstNightChatEndedAt()).isEqualTo(end);assertThat(p.getSleptAt()).isEqualTo(end);
  var morning=at.plusSeconds(8*3600);p.settle(morning);p.wake(morning);p.finishFirstIntroReview(morning);
  assertThat(p.getFirstNightChatDeadlineAt()).isEqualTo(at.plusSeconds(1800));assertThat(p.firstNightChatGraceActive(morning)).isFalse();
 }
 @Test void awakeGaugesContinueDuringGraceUnlikePendingReview(){
  var at=kst("23:00:00");var p=pending(at);ReflectionTestUtils.setField(p,"fullness",3);ReflectionTestUtils.setField(p,"fullnessAwakeSec",ZzalRules.FULLNESS_DROP_AWAKE.getSeconds()-600);p.startDiaryAbsence(at);int start=p.getFullness();p.settle(at.plusSeconds(600));assertThat(p.getFullness()).isEqualTo(start);
  p.finishFirstIntroReview(at.plusSeconds(600));var stamp=p.getSettledAt();p.settle(stamp.plusSeconds(1200));
  assertThat(p.getSettledAt()).isAfter(stamp);assertThat(p.isSleeping()).isFalse();
  assertThat(p.getFullness()).isEqualTo(start-1);assertThat(p.getDiaryAbsenceAwakeSec()).isEqualTo(1200);
 }
 @Test void dayAndHistoricalCompleteAndTravelingOrSleepingDoNotGainGrace(){
  var day=kst("12:00:00");var p=pending(day);p.finishFirstIntroReview(day);assertThat(p.getFirstNightChatDeadlineAt()).isNull();
  p.finishFirstIntroReview(kst("23:00:00"));assertThat(p.getFirstNightChatDeadlineAt()).isNull();
  var travel=pending(kst("23:00:00"));ReflectionTestUtils.setField(travel,"tripStartedAt",kst("23:00:00"));assertThat(travel.firstNightChatGraceEligible(kst("23:00:00"))).isFalse();travel.finishFirstIntroReview(kst("23:00:00"));assertThat(travel.getFirstNightChatDeadlineAt()).isNull();
  var sleep=pending(kst("23:00:00"));ReflectionTestUtils.setField(sleep,"sleepKind",SleepKind.NIGHT);ReflectionTestUtils.setField(sleep,"sleptAt",kst("23:00:00"));sleep.finishFirstIntroReview(kst("23:00:00"));assertThat(sleep.getFirstNightChatDeadlineAt()).isNull();
 }
}
