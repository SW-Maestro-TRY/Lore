package com.lore.zzal.pet;

import com.lore.zzal.PetFixture;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.lore.zzal.pet.AwakeClockTest.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;

/**
 * 튜토리얼 — <b>시각이 아니라 순서</b>(정본 12장 · 1.4).
 *
 * 옛 테스트는 "부화 뒤 3분·8분·12분…" 을 검증했다. 그 규칙이 폐기되어 통째로 다시 썼다.
 */
@DisplayName("튜토리얼 — 순서로 간다 (정본 12장 v1.4)")
class TutorialScheduleTest {

    private static final Instant T0 = kst("2026-09-05 12:00");

    private static ZzalPet born() {
        ZzalPet pet = PetFixture.hatching(1L, "여울", null, "images/zzal/abc", T0.minus(Duration.ofMinutes(3)));
        pet.markAlive("images/zzal/sheet", "생김새", T0);
        return pet;
    }

    @Nested
    @DisplayName("순서")
    class Order {

        @Test
        @DisplayName("부화 직후 = 0칸, 첫 칸은 밥. 시계는 아직 안 켜졌다")
        void startsAtFeed() {
            ZzalPet pet = born();
            assertThat(pet.isInTutorial()).isTrue();
            assertThat(pet.getClockStartedAt()).isNull();
            assertThat(pet.getTutorialStep()).isZero();

            TutorialSchedule.State s = TutorialSchedule.of(pet);
            assertThat(s).isNotNull();
            assertThat(s.step()).isZero();
            assertThat(s.steps().get(0).current()).isTrue();
            assertThat(s.steps().get(0).done()).isFalse();
        }

        @Test
        @DisplayName("★ 지금 칸의 행동만 다음으로 넘긴다 — 순서를 건너뛸 수 없다")
        void onlyCurrentStepAdvances() {
            ZzalPet pet = born();

            // 첫 칸은 밥인데 청소를 했다 — 청소는 정상 처리되지만 튜토리얼은 안 움직인다
            pet.clean(T0);
            assertThat(pet.getTutorialStep()).isZero();

            pet.feed(T0);
            assertThat(pet.getTutorialStep()).isEqualTo(1);
        }

        @Test
        @DisplayName("★ 시간이 아무리 흘러도 저절로 넘어가지 않는다 — 며칠 뒤에 와도 그 자리다")
        void timeDoesNotAdvance() {
            ZzalPet pet = born();
            pet.settle(T0.plus(Duration.ofDays(3)));

            assertThat(pet.getTutorialStep()).isZero();
            assertThat(pet.isInTutorial()).isTrue();
            assertThat(pet.getFullness()).isZero();          // 게이지도 안 줄었다
            assertThat(pet.isSleeping()).isFalse();          // 밤이 세 번 지났어도 안 잔다
        }

        @Test
        @DisplayName("9칸을 순서대로 눌러 끝낸다. 4칸이 끝나면 첫 똥이 떨어진다")
        void walkAllSteps() {
            ZzalPet pet = born();

            pet.feed(T0);
            pet.pet(T0);
            pet.answerChat();
            assertThat(pet.getTrash()).isZero();
            pet.choosePersonality(List.of(Personality.LIVELY), null);
            assertThat(pet.getTrash()).isEqualTo(1);          // ★ 5칸("치워 주세요")을 위한 첫 똥
            pet.clean(T0);
            pet.startGame();
            pet.share();
            pet.sleep(T0);
            pet.wake(T0);

            assertThat(pet.getTutorialStep()).isEqualTo(TutorialSchedule.TOTAL - 1);
            assertThat(pet.isInTutorial()).isTrue();          // 아직 — 마지막 칸이 남았다
        }

        @Test
        @DisplayName("★ 4칸은 성격을 안 고쳐도 넘어간다 — 확인만 하면 된다. 첫 똥도 그때 떨어진다")
        void personalityStepPassesOnSeeing() {
            ZzalPet pet = born();
            pet.feed(T0);
            pet.pet(T0);
            pet.answerChat();
            assertThat(TutorialSchedule.currentOf(pet.getTutorialStep()))
                    .isEqualTo(TutorialSchedule.Step.PERSONALITY);

            pet.advanceTutorial(TutorialSchedule.Step.PERSONALITY);   // tutorial/seen 이 하는 일

            assertThat(TutorialSchedule.currentOf(pet.getTutorialStep()))
                    .isEqualTo(TutorialSchedule.Step.CLEAN);
            assertThat(pet.getTrash()).isEqualTo(1);                  // ★ 5칸을 할 수 있어야 한다
            assertThat(pet.getPersonality()).isNull();                // 안 골라도 넘어간다(기본 톤)
        }

        @Test
        @DisplayName("★ 지금 칸이 아니면 아무 일도 안 일어난다 — 아무 칸에서나 밀면 순서가 무너진다")
        void seeingOutOfTurnDoesNothing() {
            ZzalPet pet = born();
            assertThat(TutorialSchedule.currentOf(pet.getTutorialStep()))
                    .isEqualTo(TutorialSchedule.Step.FEED);

            pet.advanceTutorial(TutorialSchedule.Step.PERSONALITY);

            assertThat(TutorialSchedule.currentOf(pet.getTutorialStep()))
                    .isEqualTo(TutorialSchedule.Step.FEED);
            assertThat(pet.getTrash()).isZero();                      // 똥도 안 생긴다
        }
    }

    @Nested
    @DisplayName("시계 켜기")
    class StartClock {

        private static ZzalPet upToLastStep() {
            ZzalPet pet = born();
            pet.feed(T0);
            pet.pet(T0);
            pet.answerChat();
            pet.choosePersonality(List.of(Personality.LIVELY), null);
            pet.clean(T0);
            pet.startGame();
            pet.share();
            pet.sleep(T0);
            pet.wake(T0);
            return pet;
        }

        @Test
        @DisplayName("★ 마지막 칸에서 시계가 켜지고, 그 순간부터 센다")
        void clockStartsAtLastStep() {
            ZzalPet pet = upToLastStep();
            Instant at = T0.plus(Duration.ofHours(2));

            pet.startClock(at);

            assertThat(pet.isInTutorial()).isFalse();
            assertThat(pet.getClockStartedAt()).isEqualTo(at);
            assertThat(pet.getSettledAt()).isEqualTo(at);      // ★ 머문 두 시간이 빚으로 안 남는다
            assertThat(TutorialSchedule.of(pet)).isNull();
        }

        @Test
        @DisplayName("★ 시계가 켜진 뒤에야 게이지가 준다")
        void gaugesOnlyAfterClock() {
            ZzalPet pet = upToLastStep();
            pet.startClock(T0);
            assertThat(pet.getFullness()).isEqualTo(1);        // 튜토리얼에서 한 번 먹였다

            pet.settle(T0.plus(Duration.ofHours(3)));
            assertThat(pet.getFullness()).isZero();            // 3시간에 한 칸
        }

        @Test
        @DisplayName("남은 칸이 있으면 거절한다")
        void refusesWhenUnfinished() {
            ZzalPet pet = born();
            pet.feed(T0);
            assertThatThrownBy(() -> pet.startClock(T0)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("이미 켰으면 다시 못 켠다")
        void refusesTwice() {
            ZzalPet pet = upToLastStep();
            pet.startClock(T0);
            assertThatThrownBy(() -> pet.startClock(T0)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("★ 밤에 끝내면 다음 정산에서 그 자리로 잠든다")
        void nightFinishFallsAsleep() {
            ZzalPet pet = upToLastStep();
            Instant night = kst("2026-09-05 23:30");

            pet.startClock(night);
            assertThat(pet.isSleeping()).isFalse();

            pet.settle(night.plusSeconds(1));
            assertThat(pet.isSleeping()).isTrue();
        }
    }

    @Nested
    @DisplayName("낮잠 (8칸)")
    class Nap {

        @Test
        @DisplayName("★ 재우면 곧바로 깨울 수 있다 — 시계가 멈춰 있어 기다림이 없다")
        void napWakesImmediately() {
            ZzalPet pet = born();
            pet.feed(T0);
            pet.pet(T0);
            pet.answerChat();
            pet.choosePersonality(List.of(Personality.LIVELY), null);
            pet.clean(T0);
            pet.startGame();
            pet.share();

            assertThat(pet.sleepKindAvailable(T0)).isEqualTo(SleepKind.NAP);
            pet.sleep(T0);
            assertThat(pet.canWake(T0)).isTrue();              // ★ 대기 0
            pet.wake(T0);

            assertThat(pet.getNapCount()).isEqualTo(1);
            assertThat(pet.getSleepWakeCount()).isEqualTo(2);  // 2층 11번(자기, 3회) 중 2회
        }

        @Test
        @DisplayName("★ 튜토리얼 중에는 19~23시라도 밤잠이 없다 — 낮잠뿐")
        void noNightSleepDuringTutorial() {
            ZzalPet pet = born();
            com.lore.zzal.PetFixture.readyForNap(pet);
            assertThat(pet.sleepKindAvailable(kst("2026-09-05 20:00"))).isEqualTo(SleepKind.NAP);
        }
    }
}
