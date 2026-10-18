package com.lore.zzal.pet;

import com.lore.zzal.PetFixture;
import com.lore.zzal.pet.persona.FirstMeeting;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class FirstIntroV4Test {
    final Instant noon = Instant.parse("2026-10-16T03:00:00Z");
    ZzalPet fresh() {
        ZzalPet p = PetFixture.hatching(1L,"시온",null,"k",noon.minusSeconds(60));
        p.markAlive("sheet","identity",noon);
        p.beginFirstMeeting();p.advanceFirstMeeting(FirstMeeting.State.COMPLETE);
        return p;
    }
    void five(ZzalPet p) { p.feed(noon);p.snack(noon);p.clean(noon);p.bath(noon);p.finishGame(); }
    Map<String,Object> daily(ZzalPet p) {
        var m=new LinkedHashMap<String,Object>();
        for(String key:List.of("food","todayBathDone","todaySnacks","todayGames","attendanceNightCycleAt","wakes","todayCareIntimacy"))
            m.put(key,ReflectionTestUtils.getField(p,key));
        return m;
    }
    @Test void freshFourTracesBecomeThreeAfterCleanAndZeroAfterBathWithoutEntryRespawn() {
        var p=fresh();assertThat(p.getTrash()).isEqualTo(4);
        p.beginFirstMeeting();assertThat(p.getTrash()).isEqualTo(4);
        p.feed(noon);p.snack(noon);assertThat(p.getTrash()).isEqualTo(4);
        p.advanceFirstIntro(FirstIntro.Step.SNACK);assertThat(p.getTrash()).isEqualTo(4);
        p.clean(noon);assertThat(p.introAt(FirstIntro.Step.BATH)).isTrue();assertThat(p.getTrash()).isEqualTo(3);
        p.beginFirstMeeting();p.advanceFirstIntro(FirstIntro.Step.CLEAN);assertThat(p.getTrash()).isEqualTo(3);
        p.bath(noon);assertThat(p.getTrash()).isZero();
        p.beginFirstMeeting();p.advanceFirstIntro(FirstIntro.Step.BATH);assertThat(p.getTrash()).isZero();
    }
    @Test void characterNamedBeforeImageCompletionPreservesFreshFourAtHatch() {
        var p=PetFixture.hatching(1L,"시온",null,"k",noon.minusSeconds(60));
        p.beginFirstMeeting();assertThat(p.getTrash()).isEqualTo(4);
        p.markAlive("sheet","identity",noon);assertThat(p.getTrash()).isEqualTo(4);
    }
    @Test void newSevenActionsThenAttendanceFinishReviewWithoutRemovedRequiredSteps() {
        ZzalPet p=fresh();assertThat(p.getFirstIntroVersion()).isEqualTo(4);
        assertThat(FirstIntro.sequence(4)).containsExactly(FirstIntro.Step.FEED,FirstIntro.Step.SNACK,FirstIntro.Step.CLEAN,
                FirstIntro.Step.BATH,FirstIntro.Step.GAME,FirstIntro.Step.NAP_SLEEP,FirstIntro.Step.NAP_WAKE,
                FirstIntro.Step.ATTENDANCE_INTRO,FirstIntro.Step.FINISH_NOTICE,FirstIntro.Step.REVIEW_SEEN,FirstIntro.Step.COMPLETE);
        assertThat(p.firstIntroChatAvailable()).isFalse();
        five(p);assertThat(p.introAt(FirstIntro.Step.NAP_SLEEP)).isTrue();
        p.sleep(noon);assertThat(p.getSleepKind()).isEqualTo(SleepKind.NAP);
        assertThat(p.firstIntroChatAvailable()).isFalse();
        p.wake(noon);assertThat(p.introAt(FirstIntro.Step.ATTENDANCE_INTRO)).isTrue();
        assertThat(p.firstIntroChatAvailable()).isFalse();
        p.advanceFirstIntro(FirstIntro.Step.ATTENDANCE_INTRO);
        assertThat(p.introAt(FirstIntro.Step.FINISH_NOTICE)).isTrue();
        p.startClock(noon);assertThat(p.introAt(FirstIntro.Step.REVIEW_SEEN)).isTrue();
        p.advanceFirstIntro(FirstIntro.Step.REVIEW_SEEN);p.advanceFirstIntro(FirstIntro.Step.COMPLETE);
        assertThat(p.introAt(FirstIntro.Step.COMPLETE)).isTrue();
        assertThat(FirstIntro.of(p,noon).completedKeys()).doesNotContain(FirstIntro.Step.PET,FirstIntro.Step.ALBUM,
                FirstIntro.Step.CHAT,FirstIntro.Step.PERSONALITY,FirstIntro.Step.DIARY_INTRO);
    }
    @Test void absentStepsNeverCompareAsPassedOrAvailable() {
        ZzalPet p=fresh();
        for(var step:List.of(FirstIntro.Step.PET,FirstIntro.Step.ALBUM,FirstIntro.Step.CHAT,FirstIntro.Step.PERSONALITY,FirstIntro.Step.DIARY_INTRO)) {
            assertThat(p.introAt(step)).isFalse();assertThat(p.introPassed(step)).isFalse();assertThat(p.introAtOrAfter(step)).isFalse();
        }
        assertThat(p.introCareAlreadyDone(CareAction.PET)).isFalse();
        assertThat(p.introCareCurrent(CareAction.PET)).isFalse();
    }
    @ParameterizedTest @ValueSource(ints={2,3})
    void oldVersionsAndCompletedProgressAreNeverReinterpreted(int v) {
        ZzalPet p=fresh();p.devSetFirstIntro(v,FirstIntro.Step.DIARY_INTRO,noon);
        int old=p.getFirstIntroStep();p.beginFirstMeeting();
        assertThat(p.getFirstIntroVersion()).isEqualTo(v);assertThat(p.getFirstIntroStep()).isEqualTo(old);
        assertThat(FirstIntro.of(p,noon).key()).isEqualTo(FirstIntro.Step.DIARY_INTRO);
        assertThat(FirstIntro.sequence(v)).hasSize(15).doesNotContain(FirstIntro.Step.ATTENDANCE_INTRO);
        assertThat(FirstIntro.index(v,FirstIntro.Step.COMPLETE)).isEqualTo(14);
        p.devSetFirstIntro(v,FirstIntro.Step.COMPLETE,noon);p.beginFirstMeeting();
        assertThat(p.introAt(FirstIntro.Step.COMPLETE)).isTrue();assertThat(p.isFirstMeetingComplete()).isTrue();
    }
    @ParameterizedTest @ValueSource(strings={"2026-10-16T14:59:00Z","2026-10-16T15:01:00Z","2026-10-16T22:00:00Z","2026-10-17T01:00:00Z"})
    void napAtNightOrWakeBoundariesDoesNotResetDailyStateOrCreateNightAttendance(String timestamp) {
        ZzalPet p=fresh();five(p);Map<String,Object> before=daily(p);Instant at=Instant.parse(timestamp);
        assertThat(p.sleepKindAvailable(at)).isEqualTo(SleepKind.NAP);
        p.sleep(at);assertThat(p.canWake(at)).isTrue();p.wake(at);
        assertThat(daily(p)).isEqualTo(before);assertThat(p.getAttendanceNightCycleAt()).isNull();
        assertThat(p.introAt(FirstIntro.Step.ATTENDANCE_INTRO)).isTrue();assertThat(p.isSleeping()).isFalse();
        assertThatThrownBy(()->p.wake(at)).isInstanceOf(IllegalStateException.class);
        assertThat(daily(p)).isEqualTo(before);
    }
    @Test void topicsAreVersionSpecificAndUnsupportedDevKeysCannotMutateProgress() {
        ZzalPet p=fresh();int before=p.getFirstIntroStep();
        assertThat(FirstIntro.seenTopicAllowed(4,FirstIntro.Step.ATTENDANCE_INTRO)).isTrue();
        assertThat(FirstIntro.seenTopicAllowed(4,FirstIntro.Step.ALBUM)).isFalse();
        assertThat(FirstIntro.seenTopicAllowed(3,FirstIntro.Step.ATTENDANCE_INTRO)).isFalse();
        assertThatThrownBy(()->p.devSetFirstIntro(4,FirstIntro.Step.PET,noon)).isInstanceOf(IllegalArgumentException.class);
        assertThat(p.getFirstIntroStep()).isEqualTo(before);
    }
}
