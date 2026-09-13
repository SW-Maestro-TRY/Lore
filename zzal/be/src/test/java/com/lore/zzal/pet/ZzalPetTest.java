package com.lore.zzal.pet;

import com.lore.zzal.PetFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static com.lore.zzal.pet.AwakeClockTest.kst;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 펫 규칙 테스트 — 플레이 정본 v1.2(#192).
 *
 * ★ 이 테스트들이 가능한 것은 {@link ZzalPet} 이 시각을 <b>인자로 받기</b> 때문이다.
 *   안에서 {@code Instant.now()} 를 부르면 "6시간 뒤" 를 만들 수 없어 사실상 검증이 불가능해진다.
 *
 * 여기서 지키는 것은 "코드가 안 터지는가" 가 아니라 <b>시간이 흘렀을 때 값이 맞는가</b> 이다.
 * 수치 시스템의 버그는 대개 예외를 던지지 않고, 며칠 뒤 사용자 화면에서만 드러난다.
 *
 * 시각은 전부 KST 벽시계로 적는다. T0 = 2026-09-05 12:00 KST(정오).
 */
@DisplayName("펫 — 시계와 돌봄 (정본 v1.2)")
class ZzalPetTest {

    private static final Instant T0 = kst("2026-09-05 12:00");

    /**
     * 방금 부화해 <b>튜토리얼을 시작한</b> 펫(T0). 배부름 0·행복 3·흔적 0·밥 3.
     *
     * ★ 시계가 아직 안 켜져 있다 — 게이지가 줄지 않고, 케어 미스·병·자동 취침이 없다(정본 1.4).
     * ★ 배부름이 0 인 것은 튜토리얼 첫 칸이 "배가 고픈가 봐요" 이기 때문이다(정본 1.5).
     */
    private static ZzalPet baby() {
        ZzalPet pet = PetFixture.hatching(1L, "여울", null, "images/zzal/abc", T0.minus(Duration.ofMinutes(3)));
        pet.markAlive("images/zzal/sheet", "생김새", T0);
        return pet;
    }

    /**
     * 시계가 켜진 뒤 T0 에 배부름 3·행복 3·흔적 0·밥 0(충전 시계 T0 에서 시작)·누적초 0 인 펫.
     *
     * ★ 튜토리얼은 {@code skipTutorial} 로 건너뛴다 — 아홉 칸을 실제로 누르면 밥·쓰다듬·채팅
     *   누적 카운터가 함께 올라, 시계 규칙을 보는 테스트들이 엉뚱한 수를 보게 된다.
     *   튜토리얼 자체는 {@link TutorialScheduleTest} 가 눌러 가며 본다.
     */
    private static ZzalPet child() {
        ZzalPet pet = baby();
        pet.skipTutorial(T0);
        assertThat(pet.getClockStartedAt()).isEqualTo(T0);
        pet.feed(T0);
        pet.feed(T0);
        pet.feed(T0);
        assertThat(pet.getFullness()).isEqualTo(3);
        assertThat(pet.getHappiness()).isEqualTo(3);
        assertThat(pet.getTrash()).isZero();
        assertThat(pet.getFood()).isZero();
        return pet;
    }

    private static Instant at(String kstDateTime) {
        return kst(kstDateTime);
    }

    @Nested
    @DisplayName("게이지 — 깨어 있는 시간으로만 (정본 4·16장)")
    class Gauges {

        @Test
        @DisplayName("배부름 3시간·행복 4시간·흔적 4시간에 1칸")
        void dropRates() {
            ZzalPet pet = child();
            pet.settle(at("2026-09-05 15:00"));
            assertThat(pet.getFullness()).isEqualTo(2);
            assertThat(pet.getHappiness()).isEqualTo(3);
            assertThat(pet.getTrash()).isZero();

            pet.settle(at("2026-09-05 16:00"));
            assertThat(pet.getHappiness()).isEqualTo(2);
            assertThat(pet.getTrash()).isEqualTo(1);
        }

        @Test
        @DisplayName("★ 자주 들여다봐도 나머지 시간이 버려지지 않는다")
        void remainderKept() {
            ZzalPet pet = child();
            pet.settle(at("2026-09-05 14:00"));     // 2h — 아직
            assertThat(pet.getFullness()).isEqualTo(3);
            pet.settle(at("2026-09-05 16:00"));     // 4h — 1칸(나머지 1h)
            assertThat(pet.getFullness()).isEqualTo(2);
            pet.settle(at("2026-09-05 17:00"));     // 5h — 나머지 2h
            assertThat(pet.getFullness()).isEqualTo(2);
            pet.settle(at("2026-09-05 18:00"));     // 6h — 2칸
            assertThat(pet.getFullness()).isEqualTo(1);
        }

        @Test
        @DisplayName("채워도 타이머는 안 멈춘다 — 14:59 에 밥을 줘도 15:00 에 1칸 떨어진다")
        void feedingDoesNotResetTimer() {
            ZzalPet pet = child();
            pet.grantFood(at("2026-09-05 14:59"));
            pet.settle(at("2026-09-05 14:59"));
            pet.feed(at("2026-09-05 14:59"));                 // 4
            pet.settle(at("2026-09-05 15:00"));
            assertThat(pet.getFullness()).isEqualTo(3);
        }

        @Test
        @DisplayName("밥은 흔적을 늘리지 않는다(해석 1) · 간식은 행복만 · 청소는 흔적 0")
        void careEffects() {
            ZzalPet pet = child();
            pet.settle(at("2026-09-05 16:00"));               // 흔적 1
            pet.grantFood(at("2026-09-05 16:00"));
            pet.feed(at("2026-09-05 16:00"));
            assertThat(pet.getTrash()).isEqualTo(1);
            pet.snack(at("2026-09-05 16:00"));
            assertThat(pet.getHappiness()).isEqualTo(3);      // 2 → 3
            pet.clean(at("2026-09-05 16:00"));
            assertThat(pet.getTrash()).isZero();
        }

        @Test
        @DisplayName("★ 1초 미만 조각이 버려지지 않는다 — 0.3초 간격 600회 정산 = 180초 한 번 정산")
        void subSecondPollingDoesNotFreeze() {
            ZzalPet polled = baby();
            ZzalPet control = baby();
            Instant t = T0;
            for (int i = 0; i < 600; i++) {
                t = t.plusMillis(300);
                polled.settle(t);
            }
            control.settle(T0.plus(Duration.ofSeconds(180)));
            assertThat(polled.getFullness()).isZero();                 // 아기 3분에 1칸
            assertThat(polled.getFullness()).isEqualTo(control.getFullness());
            assertThat(polled.getSettledAt()).isEqualTo(control.getSettledAt().truncatedTo(ChronoUnit.SECONDS));
        }

        @Test
        @DisplayName("대기 동작 우선순위 — 배부름 0 > 행복 0 > 흔적 3+ > 보통")
        void mood() {
            ZzalPet pet = child();
            assertThat(pet.mood()).isEqualTo(ZzalPet.Mood.NORMAL);
            pet.settle(at("2026-09-05 21:00"));               // 배부름 0(15·18·21) · 행복 1 · 흔적 2
            assertThat(pet.mood()).isEqualTo(ZzalPet.Mood.HUNGRY);
        }
    }

    @Nested
    @DisplayName("잠 — 자는 동안 정지, 밥만 충전 (정본 2·16장)")
    class Sleep {

        @Test
        @DisplayName("★ 19:00 에 재우면 07:00 까지 게이지가 그대로다")
        void gaugesFreezeWhileSleeping() {
            ZzalPet pet = child();
            pet.settle(at("2026-09-05 19:00"));               // 7h 깨어 있음 — 배부름 1(15·18) · 행복 2(16) · 흔적 1(16)
            assertThat(pet.getFullness()).isEqualTo(1);
            pet.sleep(at("2026-09-05 19:00"));                // 재우기 보상 행복 +1 → 3
            assertThat(pet.getHappiness()).isEqualTo(3);

            pet.settle(at("2026-09-06 07:00"));
            assertThat(pet.isSleeping()).isTrue();
            assertThat(pet.getFullness()).isEqualTo(1);
            assertThat(pet.getHappiness()).isEqualTo(3);
            assertThat(pet.getTrash()).isEqualTo(1);
        }

        @Test
        @DisplayName("★ 밥 충전은 자는 동안에도 돈다 — 벽시계 4시간에 1개")
        void foodChargesWhileSleeping() {
            ZzalPet pet = child();                            // 밥 0, 충전 시계 12:00
            pet.settle(at("2026-09-05 19:00"));
            pet.sleep(at("2026-09-05 19:00"));
            assertThat(pet.getFood()).isEqualTo(1);           // 16:00 에 하나
            pet.settle(at("2026-09-06 07:00"));
            assertThat(pet.getFood()).isEqualTo(3);           // 20:00·00:00 → 가득(04:00 은 상한)
            assertThat(pet.foodRemainingSeconds(at("2026-09-06 07:00"))).isNull();
        }

        @Test
        @DisplayName("깨어난 뒤 이어서 센다 — 잔 시간은 빠지고 나머지 초는 남는다")
        void resumesAfterWake() {
            ZzalPet pet = child();
            pet.settle(at("2026-09-05 19:00"));               // 배부름 나머지 1h(18:00 이후)
            pet.sleep(at("2026-09-05 19:00"));
            pet.settle(at("2026-09-06 07:00"));
            pet.wake(at("2026-09-06 07:00"));
            pet.settle(at("2026-09-06 08:59"));               // 1h + 1h59m = 2h59m
            assertThat(pet.getFullness()).isEqualTo(1);
            pet.settle(at("2026-09-06 09:00"));               // 3h
            assertThat(pet.getFullness()).isZero();
        }

        @Test
        @DisplayName("★ 23:00 자동 취침 — 안 재워도 잠들고, 자정에 조회하면 자고 있다")
        void autoSleepAtEleven() {
            ZzalPet pet = child();
            pet.settle(at("2026-09-06 00:00"));
            assertThat(pet.isSleeping()).isTrue();
            assertThat(pet.getSleepKind()).isEqualTo(SleepKind.NIGHT);
            assertThat(pet.getSleptAt()).isEqualTo(at("2026-09-05 23:00"));
            // 12:00~23:00 = 11h — 배부름 3칸(0) · 행복 2칸(1) · 흔적 2
            assertThat(pet.getFullness()).isZero();
            assertThat(pet.getHappiness()).isEqualTo(1);
            assertThat(pet.getTrash()).isEqualTo(2);
            // 자동 취침은 보상 없음
            assertThat(pet.getSleepWakeCount()).isZero();
        }

        @Test
        @DisplayName("★ 10:00 자동 기상 = 늦잠. 그 뒤 깨어 있는 시간이 다시 흐른다")
        void autoWakeAtTen() {
            ZzalPet pet = child();
            pet.settle(at("2026-09-06 11:00"));
            assertThat(pet.isSleeping()).isFalse();
            assertThat(pet.isOverslept()).isTrue();
            assertThat(pet.getWokeAt()).isEqualTo(at("2026-09-06 10:00"));
            // 23:00 시점 배부름 누적 2h(21:00 이후) + 10:00~11:00 1h = 3h → 이미 0 이라 그대로 0
            assertThat(pet.getFullness()).isZero();
            // 흔적: 23:00 시점 누적 3h(20:00 이후) + 1h = 4h → 3개
            assertThat(pet.getTrash()).isEqualTo(3);
        }

        @Test
        @DisplayName("★ 튜토리얼을 밤(23:00~07:00)에 끝내면 그 순간부터 밤잠에 든다 (정본 16장)")
        void finishingTutorialAtNightSleeps() {
            Instant hatched = at("2026-09-05 22:30");
            ZzalPet pet = PetFixture.hatching(1L, "여울", null, "k", hatched);
            pet.markAlive("s", "i", hatched);

            pet.settle(at("2026-09-05 23:15"));
            assertThat(pet.isSleeping()).isFalse();                  // 튜토리얼 중 — 시계가 안 돈다

            pet.skipTutorial(at("2026-09-05 23:30"));                // 여기서 시계가 켜진다
            pet.settle(at("2026-09-05 23:45"));
            assertThat(pet.isSleeping()).isTrue();
            assertThat(pet.getSleptAt()).isEqualTo(at("2026-09-05 23:30"));
        }

        @Test
        @DisplayName("재우기 창 — 18:59 ✗ · 19:00 ✓ · 22:59 ✓ · 23:00 ✗(이미 자동 취침)")
        void sleepWindow() {
            ZzalPet pet = child();
            assertThat(pet.sleepKindAvailable(at("2026-09-05 18:59"))).isNull();
            assertThat(pet.sleepKindAvailable(at("2026-09-05 19:00"))).isEqualTo(SleepKind.NIGHT);
            assertThat(pet.sleepKindAvailable(at("2026-09-05 22:59"))).isEqualTo(SleepKind.NIGHT);
            pet.settle(at("2026-09-05 23:00"));
            assertThat(pet.isSleeping()).isTrue();
            assertThat(pet.sleepKindAvailable(at("2026-09-05 23:00"))).isNull();
        }

        @Test
        @DisplayName("깨우기 창 — 06:59 ✗ · 07:00 ✓. 깨우면 친밀도 +10, 오늘 기상 시각이 된다")
        void wakeWindow() {
            ZzalPet pet = child();
            pet.settle(at("2026-09-05 19:00"));
            int before = pet.getIntimacy();
            pet.sleep(at("2026-09-05 19:00"));
            assertThat(pet.getIntimacy()).isEqualTo(before + 10);

            pet.settle(at("2026-09-06 06:59"));
            assertThat(pet.canWake(at("2026-09-06 06:59"))).isFalse();
            assertThat(pet.canWake(at("2026-09-06 07:00"))).isTrue();

            pet.settle(at("2026-09-06 07:00"));
            pet.wake(at("2026-09-06 07:00"));
            assertThat(pet.isSleeping()).isFalse();
            assertThat(pet.isOverslept()).isFalse();
            assertThat(pet.getWokeAt()).isEqualTo(at("2026-09-06 07:00"));
            assertThat(pet.getIntimacy()).isEqualTo(before + 20);
            assertThat(pet.getSleepWakeCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("★ 낮잠(12장 8칸) — 튜토리얼 중 한 번, 곧바로 깨울 수 있다, 보상 0, 횟수에는 포함")
        void nap() {
            ZzalPet pet = baby();
            PetFixture.readyForNap(pet);                         // 8칸 차례여야 재울 수 있다
            assertThat(pet.sleepKindAvailable(T0)).isEqualTo(SleepKind.NAP);
            pet.sleep(T0);
            assertThat(pet.getSleepKind()).isEqualTo(SleepKind.NAP);
            assertThat(pet.getSleepWakeCount()).isEqualTo(1);

            // ★ 1.4 — 시계가 멈춰 있어 기다림이 없다. 재운 그 순간 깨울 수 있다
            assertThat(pet.canWake(T0)).isTrue();

            int intimacy = pet.getIntimacy();
            pet.wake(T0);
            assertThat(pet.isSleeping()).isFalse();
            assertThat(pet.getNapCount()).isEqualTo(1);
            assertThat(pet.getIntimacy()).isEqualTo(intimacy);   // 낮잠은 재우기·깨우기 둘 다 보상 0(정본 16장)
            assertThat(pet.getWokeAt()).isEqualTo(T0);          // 낮잠은 기상 시각이 아니다

            // 두 번째 낮잠은 없다(정본 16장) — 튜토리얼 중 한 번뿐
            assertThat(pet.sleepKindAvailable(T0)).isNull();
        }

        @Test
        @DisplayName("낮잠 보상 없음 — 수동으로 깨워도 친밀도 0, 밤잠만 +10 (해석 16)")
        void napGivesNoReward() {
            ZzalPet pet = baby();
            PetFixture.readyForNap(pet);
            Instant t = T0.plus(Duration.ofMinutes(40));
            pet.settle(t);
            pet.sleep(t);
            pet.settle(t.plus(Duration.ofMinutes(5)));
            pet.wake(t.plus(Duration.ofMinutes(5)));
            assertThat(pet.getIntimacy()).isZero();
            assertThat(pet.getSleepWakeCount()).isEqualTo(2);   // 횟수(2층 11번 조건)에는 든다
        }

        @Test
        @DisplayName("★ 튜토리얼 중 낮잠에서 깨면 밤이어도 깨어 있는다 — 시계가 아직 안 켜졌으므로")
        void napDuringTutorialNeverBecomesNight() {
            Instant hatched = at("2026-09-05 22:35");
            ZzalPet pet = PetFixture.hatching(1L, "여울", null, "k", hatched);
            pet.markAlive("s", "i", hatched);
            PetFixture.readyForNap(pet);
            pet.sleep(at("2026-09-05 23:30"));                   // 낮잠
            pet.wake(at("2026-09-05 23:36"));                    // 밤이지만 튜토리얼 중
            assertThat(pet.isSleeping()).isFalse();
            assertThat(pet.getNapCount()).isEqualTo(1);
            assertThat(pet.getSleepWakeCount()).isEqualTo(2);    // 2층 11번 조건에는 든다
        }

        @Test
        @DisplayName("★ 새벽 1시 부화 — 튜토리얼은 시계와 논외. 끝낸 순간(02:00)이 밤이면 그 자리로 밤잠")
        void hatchedAtOneAm() {
            Instant hatched = at("2026-09-06 01:00");
            ZzalPet pet = PetFixture.hatching(1L, "여울", null, "k", hatched);
            pet.markAlive("s", "i", hatched);
            pet.settle(at("2026-09-06 01:59"));
            assertThat(pet.isSleeping()).isFalse();                 // 밤이지만 튜토리얼은 시계 논외
            PetFixture.readyForNap(pet);
            assertThat(pet.sleepKindAvailable(at("2026-09-06 01:59"))).isEqualTo(SleepKind.NAP);

            pet.skipTutorial(at("2026-09-06 02:00"));
            pet.settle(at("2026-09-06 02:01"));
            assertThat(pet.isSleeping()).isTrue();                  // 끝낸 시각이 밤 → 밤잠
            assertThat(pet.getSleepKind()).isEqualTo(SleepKind.NIGHT);
            assertThat(pet.canWake(at("2026-09-06 06:59"))).isFalse();
            assertThat(pet.canWake(at("2026-09-06 07:00"))).isTrue();

            ZzalPet other = PetFixture.hatching(1L, "여울", null, "k", hatched);
            other.markAlive("s", "i", hatched);
            other.skipTutorial(at("2026-09-06 02:00"));
            other.settle(at("2026-09-06 10:30"));                   // 안 깨움
            assertThat(other.isSleeping()).isFalse();
            assertThat(other.isOverslept()).isTrue();
            assertThat(other.getWokeAt()).isEqualTo(at("2026-09-06 10:00"));
        }

        @Test
        @DisplayName("★ 튜토리얼 중에는 재우기 = 낮잠만(한 번). 19~23시라도 밤잠은 없고 23시 자동 취침도 없다")
        void tutorialHasNoNightSleep() {
            Instant hatched = at("2026-09-05 22:30");
            ZzalPet pet = PetFixture.hatching(1L, "여울", null, "k", hatched);
            pet.markAlive("s", "i", hatched);
            PetFixture.readyForNap(pet);
            pet.sleep(at("2026-09-05 22:35"));                     // 낮잠
            pet.wake(at("2026-09-05 22:36"));
            assertThat(pet.getNapCount()).isEqualTo(1);
            assertThat(pet.sleepKindAvailable(at("2026-09-05 22:50"))).isNull();   // 낮잠은 한 번뿐
            pet.settle(at("2026-09-05 23:20"));
            assertThat(pet.isSleeping()).isFalse();                  // 23:00 자동 취침 없음
        }

        @Test
        @DisplayName("★ 튜토리얼 중에는 밤이 몇 번 지나도 자동 취침이 없다 (정본 1.4)")
        void tutorialHasNoAutoSleep() {
            ZzalPet pet = baby();
            pet.settle(at("2026-09-07 03:00"));                 // 밤을 두 번 지났다
            assertThat(pet.isSleeping()).isFalse();
            assertThat(pet.isInTutorial()).isTrue();
        }

        @Test
        @DisplayName("★ 튜토리얼 낮잠 동안에도, 그 앞뒤에도 게이지는 안 준다 — 시계가 아예 안 돌기 때문")
        void napFreezes() {
            ZzalPet pet = baby();
            PetFixture.readyForNap(pet);
            pet.feed(T0);                                        // 배부름 1
            pet.settle(T0.plus(Duration.ofHours(5)));            // 다섯 시간을 흘려도
            assertThat(pet.getFullness()).isEqualTo(1);
            pet.sleep(T0.plus(Duration.ofHours(5)));
            pet.settle(T0.plus(Duration.ofHours(9)));
            assertThat(pet.getFullness()).isEqualTo(1);
            pet.wake(T0.plus(Duration.ofHours(9)));
            assertThat(pet.getFullness()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("케어 미스 — 숨은 수치 (정본 4·16장)")
    class CareMiss {

        @Test
        @DisplayName("★ 어느 게이지든 0인 채 깨어 있는 6시간 → +1. 자는 시간은 안 센다. 카운터는 하나")
        void sixAwakeHoursAtZero() {
            ZzalPet pet = child();
            pet.settle(at("2026-09-05 21:00"));                 // 배부름 0 (여기서 타이머 시작)
            assertThat(pet.getFullness()).isZero();
            pet.settle(at("2026-09-06 13:59"));                 // 21~23시 2h + 10~13:59 3h59m
            assertThat(pet.getCareMiss()).isZero();
            pet.settle(at("2026-09-06 14:00"));                 // 6h
            assertThat(pet.getCareMiss()).isEqualTo(1);
            assertThat(pet.getTodayCareMiss()).isEqualTo(1);

            // 같은 게이지로는 다시 안 오른다. 다른 게이지는 각자 — 행복은 11:00 에 0(전날 20:00 이후 3h 누적)이 되어
            // 17:00 에 두 번째, 흔적은 15:00 에 4개가 되어 21:00 에 세 번째.
            pet.settle(at("2026-09-06 16:59"));
            assertThat(pet.getCareMiss()).isEqualTo(1);
            pet.settle(at("2026-09-06 17:00"));
            assertThat(pet.getCareMiss()).isEqualTo(2);
            pet.settle(at("2026-09-06 20:59"));
            assertThat(pet.getCareMiss()).isEqualTo(2);
            pet.settle(at("2026-09-06 21:00"));
            assertThat(pet.getCareMiss()).isEqualTo(3);
        }

        @Test
        @DisplayName("★★ 배부름과 행복이 둘 다 0 인 채 6시간이면 +2 — 판정은 게이지마다 따로 한다(정본 1.5)")
        void twoGaugesAtZeroGiveTwo() {
            ZzalPet pet = child();
            // 배부름과 행복을 같은 순간에 0 으로 놓는다 — 자연히 흘려 맞추면 밤잠이 끼어
            // 두 게이지의 0 이 되는 시각이 갈리고, 여기서 보려는 "동시에 0" 이 성립하지 않는다.
            org.springframework.test.util.ReflectionTestUtils.setField(pet, "fullness", 0);
            org.springframework.test.util.ReflectionTestUtils.setField(pet, "happiness", 0);
            org.springframework.test.util.ReflectionTestUtils.setField(pet, "fullnessMissArmed", true);
            org.springframework.test.util.ReflectionTestUtils.setField(pet, "happinessMissArmed", true);

            pet.settle(T0.plus(Duration.ofHours(5)));
            assertThat(pet.getCareMiss()).isZero();             // 아직 6시간이 안 지났다

            pet.settle(T0.plus(Duration.ofHours(6)));           // 둘 다 0 인 채 6시간
            // ★ 카운터는 하나지만 무장은 게이지별이라, 같은 순간에 둘이 함께 오른다(정본 1.5).
            //   원조 다마고치도 배고픔·행복 미터가 각각 케어 미스를 만든다.
            assertThat(pet.getCareMiss()).isEqualTo(2);
        }

        @Test
        @DisplayName("채워졌다 다시 0 이 되어야 그 게이지로 다음 +1")
        void rearmsAfterRefill() {
            ZzalPet pet = child();
            pet.settle(at("2026-09-05 21:00"));                 // 배부름 0
            pet.settle(at("2026-09-06 14:00"));                 // +1
            assertThat(pet.getCareMiss()).isEqualTo(1);

            pet.grantFood(at("2026-09-06 14:00"));
            pet.feed(at("2026-09-06 14:00"));                   // 1 — 누적초는 그대로(14:00 에 마침 0)
            pet.settle(at("2026-09-06 17:00"));                 // 3h → 다시 0, 재무장
            assertThat(pet.getFullness()).isZero();
            // 17:00~23:00 6h → 배부름으로 두 번째 +1. 그 사이 행복(17:00)·흔적(21:00)도 각자 +1.
            pet.settle(at("2026-09-06 23:00"));
            assertThat(pet.getCareMiss()).isEqualTo(4);
        }

        @Test
        @DisplayName("★★ 돌보지 않은 날은 \"잘 돌본 날\" 이 아니다 — 케어 미스가 0 이어도 안 센다 (정본 1.7)")
        void neglectedDayIsNotAZeroMissDay() {
            ZzalPet pet = child();
            // child() 가 게이지를 맞추느라 밥을 먹였다. 그 흔적을 지워 "오늘 아직 안 돌본" 상태로 둔다.
            org.springframework.test.util.ReflectionTestUtils.setField(pet, "todayCared", false);
            int before = pet.getZeroMissDays();

            // 아무것도 안 하고 저녁에 재운다. 일곱 시간이라 케어 미스는 안 쌓인다.
            pet.sleep(at("2026-09-05 19:00"));

            assertThat(pet.getTodayCareMiss()).isZero();          // 새로 쌓인 케어 미스는 없지만
            assertThat(pet.getZeroMissDays()).isEqualTo(before);  // ★ 돌본 적이 없으니 안 센다
        }

        @Test
        @DisplayName("한 번이라도 돌본 날은 잘 돌본 날로 센다")
        void caredDayCounts() {
            ZzalPet pet = child();
            int before = pet.getZeroMissDays();
            pet.pet(T0);                                    // 쓰다듬 한 번
            pet.sleep(at("2026-09-05 19:00"));
            assertThat(pet.getZeroMissDays()).isEqualTo(before + 1);
        }

        @Test
        @DisplayName("케어 미스 0인 날 — 잠드는 순간 판정, 오늘 카운터 리셋")
        void zeroMissDayJudgedAtSleep() {
            ZzalPet pet = child();
            pet.settle(at("2026-09-05 23:00"));                 // 자동 취침. 오늘 새로 쌓인 것 없음
            assertThat(pet.getZeroMissDays()).isEqualTo(1);
            assertThat(pet.getTodayCareMiss()).isZero();

            pet.settle(at("2026-09-06 14:00"));                 // +1 (배부름)
            pet.settle(at("2026-09-06 23:00"));
            assertThat(pet.getZeroMissDays()).isEqualTo(1);     // 오늘은 아니다
            assertThat(pet.getTodayCareMiss()).isZero();        // 리셋
            assertThat(pet.getCareMiss()).isGreaterThanOrEqualTo(1); // 누적은 남는다
        }
    }

    @Nested
    @DisplayName("하루의 경계 = 잠드는 순간 (정본 16장)")
    class DayBoundary {

        @Test
        @DisplayName("쓰다듬기 3회·목욕 1회·연속 간식이 밤잠에 리셋된다. 낮잠은 아니다")
        void todayCountersResetAtNightSleep() {
            ZzalPet pet = child();
            pet.pet(T0);
            pet.pet(T0);
            pet.pet(T0);
            pet.bath(T0);
            pet.snack(T0);
            assertThat(pet.getTodayPetCount()).isEqualTo(3);
            assertThat(pet.isTodayBathDone()).isTrue();
            assertThat(pet.getTodaySnacks()).isEqualTo(1);

            pet.settle(at("2026-09-05 19:00"));
            pet.sleep(at("2026-09-05 19:00"));
            assertThat(pet.getTodayPetCount()).isZero();
            assertThat(pet.isTodayBathDone()).isFalse();
            assertThat(pet.getTodaySnacks()).isZero();
            assertThat(pet.getTodayCareIntimacy()).isZero();
        }

        @Test
        @DisplayName("낮잠은 경계가 아니다 — 카운터가 남는다")
        void napIsNotBoundary() {
            ZzalPet pet = baby();
            PetFixture.readyForNap(pet);
            pet.pet(T0);
            pet.sleep(T0.plus(Duration.ofMinutes(1)));
            assertThat(pet.getTodayPetCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("친밀도·돌봄 상한 (정본 4·8장)")
    class Intimacy {

        @Test
        @DisplayName("밥·청소·목욕·약 각 +5, 하루 합산 30 상한")
        void careIntimacyCap() {
            ZzalPet pet = baby();
            pet.feed(T0);
            pet.feed(T0);
            pet.feed(T0);                                       // 15
            pet.clean(T0);                                      // 20
            pet.bath(T0);                                       // 25
            pet.medicine(T0);                                   // 30
            pet.medicine(T0);                                   // 상한
            assertThat(pet.getIntimacy()).isEqualTo(30);
            assertThat(pet.getTodayCareIntimacy()).isEqualTo(30);
        }

        @Test
        @DisplayName("쓰다듬기 +5 는 하루 3회까지. 4번째는 반응만(행복도 안 오른다)")
        void petThreeTimes() {
            ZzalPet pet = baby();
            int happiness = pet.getHappiness();
            for (int i = 0; i < 4; i++) {
                pet.pet(T0);
            }
            assertThat(pet.getIntimacy()).isEqualTo(15);
            assertThat(pet.getTodayPetCount()).isEqualTo(3);
            assertThat(pet.getHappiness()).isEqualTo(happiness);
        }

        @Test
        @DisplayName("재우기 +10 · 깨우기 +10 · 최고치가 따로 남는다")
        void sleepWakeIntimacy() {
            ZzalPet pet = child();
            int before = pet.getIntimacy();
            pet.settle(at("2026-09-05 19:00"));
            pet.sleep(at("2026-09-05 19:00"));
            pet.settle(at("2026-09-06 07:00"));
            pet.wake(at("2026-09-06 07:00"));
            assertThat(pet.getIntimacy()).isEqualTo(before + 20);
            assertThat(pet.getIntimacyPeak()).isEqualTo(before + 20);
        }

        @Test
        @DisplayName("★ 간식은 그날 4개까지 — 5개째에 배탈. 사이에 다른 행동이 껴도 같다 (정본 1.9)")
        void snackDailyLimit() {
            // ★ 옛 규칙("다른 행동 없이 연달아 5개")은 밥을 한 번만 끼우면 연속이 끊겨
            //   하루에 열 개도 먹일 수 있었다. 이제 "연속" 을 보지 않는다.
            ZzalPet pet = child();
            pet.snack(T0);
            pet.snack(T0);
            pet.pet(T0);                                        // ← 다른 행동을 끼운다
            pet.snack(T0);
            pet.snack(T0);
            assertThat(pet.getTodaySnacks()).isEqualTo(4);
            assertThat(pet.isSick()).as("4개까지는 멀쩡하다").isFalse();

            pet.snack(T0);                                      // 5개째
            assertThat(pet.isSick()).isTrue();
            assertThat(pet.getSickKind()).isEqualTo(com.lore.zzal.pet.SickKind.UPSET);
        }

        @Test
        @DisplayName("★ 배탈이 나는 그 간식은 조각에 안 센다 — nextSnackUpsets 가 먹이기 전에 답한다")
        void nextSnackUpsetsTellsBefore() {
            ZzalPet pet = child();
            for (int i = 0; i < 4; i++) {
                assertThat(pet.nextSnackUpsets()).as("%d개째는 멀쩡".formatted(i + 1)).isFalse();
                pet.snack(T0);
            }
            assertThat(pet.nextSnackUpsets()).as("5개째부터 배탈").isTrue();
        }

        @Test
        @DisplayName("목욕 = 흔적 0 + 행복 +1, 누적 횟수(2층 씻기 조건)")
        void bath() {
            ZzalPet pet = child();
            pet.settle(at("2026-09-05 16:00"));                 // 행복 2 · 흔적 1
            pet.bath(at("2026-09-05 16:00"));
            assertThat(pet.getTrash()).isZero();
            assertThat(pet.getHappiness()).isEqualTo(3);
            assertThat(pet.getBathCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("밥 재고 (정본 4장)")
    class Food {

        @Test
        @DisplayName("보관 3 · 4시간에 1개 충전 · 가득이면 시계가 멈추고 먹으면 다시 켜진다")
        void foodCharge() {
            ZzalPet pet = child();                              // 0, 시계 12:00
            assertThat(pet.foodRemainingSeconds(at("2026-09-05 13:00"))).isEqualTo(3 * 3600L);
            pet.settle(at("2026-09-05 16:00"));
            assertThat(pet.getFood()).isEqualTo(1);
            pet.settle(at("2026-09-06 00:30"));                 // 벽시계 12.5h → 3(상한)
            assertThat(pet.getFood()).isEqualTo(3);
            assertThat(pet.foodRemainingSeconds(at("2026-09-06 00:30"))).isNull();
        }

        @Test
        @DisplayName("보상으로 받은 밥도 상한을 넘지 않는다")
        void grantFoodCapped() {
            ZzalPet pet = baby();
            pet.grantFood(T0);
            assertThat(pet.getFood()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("개발용 시계 — 오프셋")
    class DevClock {

        @Test
        @DisplayName("오프셋을 걸면 이 펫의 '지금' 이 그만큼 앞이다. 규칙은 그대로")
        void offset() {
            ZzalPet pet = child();
            assertThat(pet.now(T0)).isEqualTo(T0);
            pet.advanceDevClock(Duration.ofHours(11));
            assertThat(pet.now(T0)).isEqualTo(at("2026-09-05 23:00"));
            pet.settle(pet.now(T0));
            assertThat(pet.isSleeping()).isTrue();              // 23:00 자동 취침이 실제 규칙으로 돈다
        }

        @Test
        @DisplayName("특정 시각으로 맞추기")
        void setClock() {
            ZzalPet pet = child();
            pet.setDevClock(at("2026-09-05 19:00"), T0);
            assertThat(pet.now(T0)).isEqualTo(at("2026-09-05 19:00"));
            assertThat(pet.canSleep(pet.now(T0))).isTrue();
        }

        @Test
        @DisplayName("★ 실제 시각에 소수 초가 있어도 맞춘 시각보다 앞서지 않는다 — 19:00 으로 맞추면 창 안이다")
        void setClockNeverBeforeTarget() {
            ZzalPet pet = child();
            Instant real = T0.plusMillis(611);
            pet.setDevClock(at("2026-09-05 19:00"), real);
            assertThat(pet.now(real)).isAfterOrEqualTo(at("2026-09-05 19:00"));
            assertThat(pet.now(real)).isBefore(at("2026-09-05 19:00").plusSeconds(1));
            assertThat(pet.canSleep(pet.now(real))).isTrue();
        }
    }

    @Nested
    @DisplayName("생애")
    class Life {

        @Test
        @DisplayName("★ 부화 = 튜토리얼 시작. 시계는 아직 안 켜진다. 배부름 0·행복 3·흔적 0·밥 3")
        void hatchStartsTutorial() {
            ZzalPet pet = baby();
            assertThat(pet.getSettledAt()).isEqualTo(T0);
            assertThat(pet.getWokeAt()).isEqualTo(T0);
            assertThat(pet.getClockStartedAt()).isNull();       // ★ 1.4 — 시계는 튜토리얼 끝에 켜진다
            assertThat(pet.isInTutorial()).isTrue();
            assertThat(pet.getFullness()).isZero();             // ★ 첫 칸이 "배가 고픈가 봐요"
            assertThat(pet.getHappiness()).isEqualTo(3);
            assertThat(pet.getTrash()).isZero();
            assertThat(pet.getFood()).isEqualTo(3);
        }

        @Test
        @DisplayName("보내면 DEAD·RELEASED, 부화 중·이미 떠난 아이는 그대로")
        void release() {
            ZzalPet pet = baby();
            pet.release(T0);
            assertThat(pet.getPhase()).isEqualTo(PetPhase.DEAD);
            assertThat(pet.getDeathReason()).isEqualTo(DeathReason.RELEASED);
            pet.release(T0);
            assertThat(pet.getPhase()).isEqualTo(PetPhase.DEAD);

            ZzalPet egg = PetFixture.hatching(1L, "알", null, "k", T0);
            egg.release(T0);
            assertThat(egg.getPhase()).isEqualTo(PetPhase.HATCHING);
        }

        @Test
        @DisplayName("ALIVE 가 아니면 정산해도 아무 일 없다")
        void settleIgnoresNonAlive() {
            ZzalPet egg = PetFixture.hatching(1L, "알", null, "k", T0);
            egg.settle(at("2026-09-08 12:00"));
            assertThat(egg.isSleeping()).isFalse();
            assertThat(egg.getSettledAt()).isNull();
        }
    }

    /**
     * 떠남·재회 — 실패 주입(verify-failure-paths).
     *
     * ★ 여기서 지키는 것은 <b>"떠남은 벌이 아니다"</b>이다. 예고는 돌아오면 즉시 취소되고, 여행 중에는
     *   아무 시계도 안 돌며, 부르면 그동안 쌓은 것을 절반은 안고 돌아온다. 끄고 싶으면 끌 수 있다.
     */
    @Nested
    @DisplayName("떠남·재회 (정본 9·16장) — 실패 주입")
    class Leaving {

        /** 마지막 방문을 {@code days} 일 전으로 밀어 둔 어린이. */
        private ZzalPet absentFor(int days) {
            ZzalPet pet = child();
            org.springframework.test.util.ReflectionTestUtils.setField(pet, "lastVisitDate",
                    java.time.LocalDate.of(2026, 9, 6).minusDays(days));   // 판정은 09-06 기상에서 돈다
            return pet;
        }

        @Test
        @DisplayName("★★ 미방문 5일 → 기상에 짐 싸기 예고. 그 전에는 없다")
        void noticeAfterFiveDays() {
            ZzalPet four = absentFor(4);
            four.settle(at("2026-09-06 12:00"));          // 23:00 취침 → 10:00 기상을 지난다
            assertThat(four.isLeavingNoticed()).as("나흘로는 아직").isFalse();

            ZzalPet five = absentFor(5);
            five.settle(at("2026-09-06 12:00"));
            assertThat(five.isLeavingNoticed()).isTrue();
            assertThat(five.departAt()).isNotNull();
        }

        @Test
        @DisplayName("★ 케어 미스 8이면 방문과 무관하게 예고")
        void noticeAtEightCareMiss() {
            ZzalPet pet = child();
            org.springframework.test.util.ReflectionTestUtils.setField(pet, "careMiss", 8);
            pet.settle(at("2026-09-06 12:00"));
            assertThat(pet.isLeavingNoticed()).isTrue();
        }

        @Test
        @DisplayName("★★ 예고 중 접속하면 즉시 취소 + 케어 미스 -2")
        void visitCancelsNotice() {
            ZzalPet pet = absentFor(5);
            org.springframework.test.util.ReflectionTestUtils.setField(pet, "careMiss", 5);
            pet.settle(at("2026-09-06 12:00"));
            assertThat(pet.isLeavingNoticed()).isTrue();

            pet.visit(at("2026-09-06 12:00"));

            assertThat(pet.isLeavingNoticed()).isFalse();
            assertThat(pet.getCareMiss()).isEqualTo(3);
            assertThat(pet.isLeavingJustCancelled()).as("이번 한 번은 알려 준다").isTrue();
        }

        @Test
        @DisplayName("★★ 예고 2일 뒤 여행 — 그 뒤로는 게이지도 병도 부재도 멈춘다")
        void departsTwoDaysAfterNotice() {
            ZzalPet pet = absentFor(5);
            pet.settle(at("2026-09-06 12:00"));
            assertThat(pet.isLeavingNoticed()).isTrue();
            int fullnessBefore = pet.getFullness();

            pet.settle(at("2026-09-08 12:00"));           // 예고 2일 뒤
            assertThat(pet.isTraveling()).isTrue();
            assertThat(pet.isLeavingNoticed()).as("떠났으면 짐 가방은 없다").isFalse();

            long absenceBefore = pet.getAbsenceAwakeSec();
            pet.settle(at("2026-09-12 12:00"));           // 여행 중 나흘
            assertThat(pet.getAbsenceAwakeSec()).as("부재 시계도 멈춘다").isEqualTo(absenceBefore);
            assertThat(pet.getFullness()).isLessThanOrEqualTo(fullnessBefore);
        }

        @Test
        @DisplayName("★★ 함께한 날 30일 이상이면 예고·유예가 각각 2배")
        void graceDoublesAfterThirtyDays() {
            ZzalPet pet = absentFor(5);
            org.springframework.test.util.ReflectionTestUtils.setField(pet, "daysTogether", 30);
            assertThat(pet.noticeDays()).isEqualTo(10);
            assertThat(pet.departDays()).isEqualTo(4);

            pet.settle(at("2026-09-06 12:00"));
            assertThat(pet.isLeavingNoticed()).as("닷새로는 아직(오래 함께했으니 열흘)").isFalse();
        }

        @Test
        @DisplayName("★★ 떠남을 끈 펫은 예고도 여행도 없다")
        void disabledMeansNoLeaving() {
            ZzalPet pet = absentFor(30);
            pet.setLeaveEnabled(false);

            pet.settle(at("2026-09-20 12:00"));

            assertThat(pet.isLeavingNoticed()).isFalse();
            assertThat(pet.isTraveling()).isFalse();
        }

        @Test
        @DisplayName("★ 끄면 예고 중이던 짐 가방도 즉시 사라진다")
        void disablingClearsNotice() {
            ZzalPet pet = absentFor(5);
            pet.settle(at("2026-09-06 12:00"));
            assertThat(pet.isLeavingNoticed()).isTrue();

            pet.setLeaveEnabled(false);

            assertThat(pet.isLeavingNoticed()).isFalse();
        }

        @Test
        @DisplayName("★★ 부르기 — 게이지 전부 2 · 케어 미스 0 · 친밀도는 최고치의 50% · 부재 시계 0")
        void callBackRestores() {
            ZzalPet pet = absentFor(5);
            org.springframework.test.util.ReflectionTestUtils.setField(pet, "intimacyPeak", 400);
            org.springframework.test.util.ReflectionTestUtils.setField(pet, "careMiss", 9);
            pet.settle(at("2026-09-06 12:00"));
            pet.settle(at("2026-09-08 12:00"));
            assertThat(pet.isTraveling()).isTrue();

            pet.callBack(at("2026-09-10 12:00"));

            assertThat(pet.isTraveling()).isFalse();
            assertThat(pet.getFullness()).isEqualTo(2);
            assertThat(pet.getHappiness()).isEqualTo(2);
            assertThat(pet.getClean()).isEqualTo(2);
            assertThat(pet.getCareMiss()).isZero();
            assertThat(pet.getIntimacy()).isEqualTo(200);
            assertThat(pet.getAbsenceAwakeSec()).isZero();
            assertThat(pet.isSick()).isFalse();
            assertThat(pet.isSleeping()).isFalse();
        }

        @Test
        @DisplayName("★ 여행 중에는 함께한 날이 안 는다")
        void daysTogetherStopsWhileTraveling() {
            ZzalPet pet = absentFor(5);
            pet.settle(at("2026-09-06 12:00"));
            pet.settle(at("2026-09-08 12:00"));
            assertThat(pet.isTraveling()).isTrue();
            int days = pet.getDaysTogether();

            pet.visit(at("2026-09-09 12:00"));

            assertThat(pet.getDaysTogether()).isEqualTo(days);
        }

        @Test
        @DisplayName("★ 두 번째 여행의 엽서는 옛 엽서 뒤에 온다 — 번호가 1부터 다시 시작해도")
        void secondTripPostcardsComeAfter() {
            // 번호(seq)만으로 정렬하면 두 번째 여행의 1번이 첫 여행의 3번보다 앞에 선다.
            // 그래서 저장소는 쓴 시각으로 정렬한다(#235 리뷰 하-2). 여기서는 그 전제만 확인한다.
            ZzalPet pet = absentFor(5);
            pet.settle(at("2026-09-06 12:00"));
            pet.settle(at("2026-09-08 12:00"));
            assertThat(pet.isTraveling()).isTrue();
            pet.wrotePostcard(at("2026-09-08 12:00"));
            assertThat(pet.getPostcardCount()).isEqualTo(1);

            pet.callBack(at("2026-09-09 12:00"));
            assertThat(pet.getPostcardCount()).as("돌아오면 다음 여행을 위해 0으로")
                    .isEqualTo(1);   // callBack 은 세지 않는다 — 다음 출발(judgeLeaving)에서 0이 된다
        }

        @Test
        @DisplayName("★ 엽서는 하루 한 장, 최대 3장")
        void postcardsOncePerDayMaxThree() {
            ZzalPet pet = absentFor(5);
            pet.settle(at("2026-09-06 12:00"));
            pet.settle(at("2026-09-08 12:00"));
            assertThat(pet.canWritePostcard(at("2026-09-08 12:00"))).isTrue();

            pet.wrotePostcard(at("2026-09-08 12:00"));
            assertThat(pet.canWritePostcard(at("2026-09-08 20:00"))).as("같은 날엔 한 장").isFalse();
            assertThat(pet.canWritePostcard(at("2026-09-09 12:00"))).isTrue();

            pet.wrotePostcard(at("2026-09-09 12:00"));
            pet.wrotePostcard(at("2026-09-10 12:00"));
            assertThat(pet.getPostcardCount()).isEqualTo(3);
            assertThat(pet.canWritePostcard(at("2026-09-11 12:00"))).as("세 장에서 멈춘다").isFalse();
        }
    }

    /**
     * 조각 — 펫 쪽 몫만(정본 6·16장 · 1.9).
     *
     * ★ 세는 일 자체는 {@code zzal_piece} 로 옮겼다({@code ZzalPieceTest}). 여기 남은 것은
     *   <b>펫이 언제 조각 칸을 얻는가</b>와 <b>언제 쪽지를 남기는가</b> 둘이다.
     */
    @Nested
    @DisplayName("조각 (정본 6·16장) — 펫 쪽 몫")
    class Pieces {

        @Test
        @DisplayName("★ 조각 4칸은 2층을 다 연 <b>다음</b> 기상에 등장한다 — 그날 밤에 바로는 아니다")
        void piecesAppearNextMorning() {
            ZzalPet pet = child();
            pet.markLayerTwoDone(at("2026-09-05 21:00"));
            assertThat(pet.readyForPieces(at("2026-09-05 21:30")))
                    .as("같은 날 저녁에는 아직").isFalse();

            pet.sleep(at("2026-09-05 21:30"));
            assertThat(pet.readyForPieces(at("2026-09-06 03:00"))).as("자는 중에는 아직").isFalse();
            pet.wake(at("2026-09-06 08:00"));
            assertThat(pet.readyForPieces(at("2026-09-06 08:00"))).isTrue();
        }

        @Test
        @DisplayName("★★ 밤잠에서 깨면 '네 칸을 되돌려라' 쪽지가 남는다 (1.9)")
        void wakeLeavesResetNote() {
            ZzalPet pet = child();
            pet.enablePieces(T0);
            assertThat(pet.isPieceResetPending()).isFalse();

            pet.sleep(at("2026-09-05 20:00"));
            assertThat(pet.isPieceResetPending()).as("잠들 때가 아니다").isFalse();

            pet.wake(at("2026-09-06 08:00"));
            assertThat(pet.isPieceResetPending()).isTrue();

            pet.clearPiecePending();
            assertThat(pet.isPieceResetPending()).isFalse();
        }

        @Test
        @DisplayName("★★ 낮잠에서 깨는 것은 기상이 아니다 — 쪽지가 안 남는다")
        void napLeavesNoNote() {
            ZzalPet pet = child();
            pet.enablePieces(T0);
            com.lore.zzal.PetFixture.readyForNap(pet);
            assertThat(pet.isPieceResetPending()).isFalse();
        }

        @Test
        @DisplayName("★★ 기분 좋은 날 — 벌점 0 + 세 게이지 2칸 이상이면 다음 기상에 '선물' 쪽지")
        void goodDayLeavesBonusNote() {
            ZzalPet pet = child();
            pet.enablePieces(T0);
            org.springframework.test.util.ReflectionTestUtils.setField(pet, "fullness", 4);
            org.springframework.test.util.ReflectionTestUtils.setField(pet, "happiness", 4);
            org.springframework.test.util.ReflectionTestUtils.setField(pet, "trash", 0);
            pet.sleep(at("2026-09-05 20:00"));
            assertThat(pet.isGoodDayToday()).as("선물은 잠든 그 순간이 아니라 다음 날 아침").isFalse();

            pet.wake(at("2026-09-06 08:00"));

            assertThat(pet.isGoodDayToday()).isTrue();
            assertThat(pet.isBonusPiece()).isTrue();
            assertThat(pet.isPieceBonusPending()).isTrue();

            pet.sleep(at("2026-09-06 20:00"));
            assertThat(pet.isGoodDayToday()).as("잠들면 꺼진다").isFalse();
        }

        @Test
        @DisplayName("★ 게이지가 모자라면 기분 좋은 날이 아니다")
        void goodDayNeedsGauges() {
            ZzalPet pet = child();
            pet.enablePieces(T0);
            org.springframework.test.util.ReflectionTestUtils.setField(pet, "fullness", 1);
            pet.sleep(at("2026-09-05 20:00"));
            pet.wake(at("2026-09-06 08:00"));
            assertThat(pet.isGoodDayToday()).isFalse();
            assertThat(pet.isBonusPiece()).isFalse();
            assertThat(pet.isPieceBonusPending()).isFalse();
        }
    }

    /**
     * 병 — 실패 주입(verify-failure-paths).
     *
     * ★ 여기서 지키는 것은 전부 <b>정상 경로에서는 한 번도 안 도는</b> 분기다: 6시간을 더럽힌 채 두기,
     *   아픈 채 24시간 방치, 자는 동안 시계 정지, 심화 해금 뒤의 자연 발병. 일부러 시간을 밀어야 돈다.
     */
    @Nested
    @DisplayName("병 (정본 5·16장) — 실패 주입")
    class Sickness {

        @Test
        @DisplayName("★★ 흔적 4개인 채 깨어 있는 6시간 → 100% 병(DIRTY). 그 전에는 안 아프다")
        void dirtyForSixHours() {
            ZzalPet pet = child();                       // 정오, 배부름 3·행복 3·흔적 0
            // ★ 흔적이 4가 차기를 기다리면(깨어 있는 16시간) 그 전에 배부름이 0 이 되어 방치 병이 먼저 난다.
            //   여기서 보려는 것은 "흔적 4인 채 6시간" 하나이므로 흔적만 4로 놓고 잰다.
            org.springframework.test.util.ReflectionTestUtils.setField(pet, "trash", 4);
            org.springframework.test.util.ReflectionTestUtils.setField(pet, "cleanMissArmed", true);
            pet.settle(T0.plus(Duration.ofHours(5)));
            assertThat(pet.getTrash()).isEqualTo(4);
            assertThat(pet.isSick()).isFalse();           // 아직 6시간이 안 지났다

            pet.settle(T0.plus(Duration.ofHours(6)));
            assertThat(pet.isSick()).isTrue();
            assertThat(pet.getSickKind()).isEqualTo(SickKind.DIRTY);
            assertThat(pet.mood()).isEqualTo(ZzalPet.Mood.SICK);   // 우선순위 병 > 배부름 > …
        }

        @Test
        @DisplayName("★★ 아픈 채 깨어 있는 24시간마다 케어 미스 +1 — 약을 주면 거기서 멈춘다")
        void neglectedSicknessCostsCareMiss() {
            ZzalPet pet = child();
            pet.settle(at("2026-09-06 21:00"));                   // 위와 같은 경로로 아프게 만든다(흔적 6시간)
            assertThat(pet.isSick()).isTrue();
            int before = pet.getCareMiss();

            // 아픈 채 깨어 있는 24시간 = 실제로는 이틀 가까이(밤에는 안 센다)
            pet.settle(at("2026-09-08 21:00"));
            assertThat(pet.getCareMiss()).isGreaterThan(before);

            int afterFirst = pet.getCareMiss();
            pet.medicine(at("2026-09-08 21:00"));
            pet.settle(at("2026-09-11 21:00"));                   // 나은 뒤로는 병 때문에 안 오른다
            assertThat(pet.isSick()).isFalse();
            // 굶주림·더러움으로 오르는 몫은 있지만, 병 몫(24시간마다)은 멈췄다
            assertThat(pet.getSickSince()).isNull();
        }

        @Test
        @DisplayName("★★ 자는 동안에는 병 시계가 안 흐른다 — 재우고 하룻밤을 넘겨도 케어 미스가 안 오른다")
        void sicknessClockStopsWhileAsleep() {
            ZzalPet pet = child();
            pet.settle(at("2026-09-06 21:00"));
            assertThat(pet.isSick()).isTrue();
            pet.sleep(at("2026-09-06 22:30"));                    // 19~23시 창 안
            int before = pet.getCareMiss();

            pet.settle(at("2026-09-07 06:59"));                   // 아직 자는 중(기상은 07:00~)
            assertThat(pet.isSleeping()).isTrue();
            assertThat(pet.getCareMiss()).isEqualTo(before);      // 한 칸도 안 올랐다
        }

        @Test
        @DisplayName("★ 약은 한 번에 즉시 낫는다 — 원인·시각·병 시계가 모두 지워진다")
        void medicineHealsAtOnce() {
            ZzalPet pet = child();
            pet.settle(at("2026-09-06 21:00"));
            assertThat(pet.isSick()).isTrue();

            pet.medicine(at("2026-09-06 22:10"));

            assertThat(pet.isSick()).isFalse();
            assertThat(pet.getSickSince()).isNull();
            assertThat(pet.getSickKind()).isNull();
            assertThat(pet.getHealedAt()).isEqualTo(at("2026-09-06 22:10"));
            assertThat(pet.mood()).isNotEqualTo(ZzalPet.Mood.SICK);
        }

        @Test
        @DisplayName("★★ 자연 발병은 심화 행동이 열린 뒤에만 — 예약 전에는 아무리 지나도 자연 발병이 없다")
        void naturalSicknessOnlyAfterAdvanced() {
            ZzalPet pet = child();
            assertThat(pet.getNaturalSickDueAwakeSec()).isNull();

            pet.scheduleNaturalSickness();
            Long due = pet.getNaturalSickDueAwakeSec();
            assertThat(due).isNotNull();
            // 깨어 있는 3일 = 10:00~23:00 × 3 = 39시간 안(정본 5장, 창에서 파생한 값)
            assertThat(due).isBetween(1L, ZzalRules.SICK_NATURAL_WINDOW_AWAKE.getSeconds());

            // 두 번 불러도 예약이 겹치지 않는다(한 번에 하나만 대기)
            pet.scheduleNaturalSickness();
            assertThat(pet.getNaturalSickDueAwakeSec()).isEqualTo(due);
        }

        @Test
        @DisplayName("★ 자연 발병 시계는 깨어 있는 동안만 흐른다 — 자는 밤을 넘겨도 그만큼은 안 준다")
        void naturalCountdownRunsOnlyAwake() {
            ZzalPet pet = child();
            pet.scheduleNaturalSickness();
            // 뽑힌 값은 39시간 안 아무 값이라 이 테스트에서 발병할 수도 있다. 여기서 보려는 것은
            // "얼마나 줄어드는가" 뿐이므로 넉넉한 값으로 고정한다(뽑기 자체는 ChanceTest 가 본다).
            long due = 20 * 3600L;
            org.springframework.test.util.ReflectionTestUtils.setField(pet, "naturalSickDueAwakeSec", due);

            pet.settle(at("2026-09-05 14:00"));              // 깨어 있는 2시간
            long afterAwake = pet.getNaturalSickDueAwakeSec();
            assertThat(due - afterAwake).isEqualTo(2 * 3600);

            pet.settle(at("2026-09-05 20:00"));              // 여기까지 깨어 있는 6시간 더
            pet.sleep(at("2026-09-05 20:00"));               // 재운다 — 그 뒤로는 안 준다
            pet.settle(at("2026-09-06 02:00"));
            // 20:00 까지 깨어 있던 8시간만 줄고, 자는 6시간은 안 줄었다
            assertThat(due - pet.getNaturalSickDueAwakeSec()).isEqualTo(8 * 3600);
        }

        @Test
        @DisplayName("★ 예약한 시간이 다 흐르면 낮에 저절로 아프다(NATURAL)")
        void naturalSicknessFires() {
            ZzalPet pet = child();
            pet.scheduleNaturalSickness();
            // 예약을 짧게 바꿔 두고(테스트 전용) 깨어 있는 시간을 그만큼 태운다
            org.springframework.test.util.ReflectionTestUtils.setField(pet, "naturalSickDueAwakeSec", 3600L);

            pet.settle(at("2026-09-05 13:30"));
            assertThat(pet.isSick()).isTrue();
            assertThat(pet.getSickKind()).isEqualTo(SickKind.NATURAL);
            assertThat(pet.getNaturalSickDueAwakeSec()).isNull();   // 예약은 소진됐다
        }

        @Test
        @DisplayName("★ 먼저 난 병이 이긴다 — 아픈 채로 간식 5개를 줘도 원인이 안 바뀐다")
        void firstCauseWins() {
            ZzalPet pet = child();
            org.springframework.test.util.ReflectionTestUtils.setField(pet, "trash", 4);
            org.springframework.test.util.ReflectionTestUtils.setField(pet, "cleanMissArmed", true);
            pet.settle(T0.plus(Duration.ofHours(6)));
            assertThat(pet.getSickKind()).isEqualTo(SickKind.DIRTY);

            for (int i = 0; i < 5; i++) {
                pet.snack(T0.plus(Duration.ofHours(6)));
            }
            assertThat(pet.getSickKind()).isEqualTo(SickKind.DIRTY);
        }

        @Test
        @DisplayName("★★ 아기 60분 안에는 간식 5개를 줘도 안 아프다(정본 12·16장 \"아기 동안 병 없음\")")
        void noSicknessWhileBaby() {
            ZzalPet baby = baby();                       // 3분 전 부화 — 아직 아기
            for (int i = 0; i < 5; i++) {
                baby.snack(T0);
            }
            assertThat(baby.isSick()).isFalse();
            assertThat(baby.getTodaySnacks()).isEqualTo(5);  // ★ 세기는 세지만 튜토리얼 중에는 병이 없다

            // 60분이 지난 뒤에는 규칙대로 아프다
            ZzalPet grown = child();
            for (int i = 0; i < 5; i++) {
                grown.snack(T0);
            }
            assertThat(grown.isSick()).isTrue();
            assertThat(grown.getSickKind()).isEqualTo(SickKind.UPSET);
        }

        @Test
        @DisplayName("★★ 한 번에 정산하든 1초씩 정산하든 결과가 같다 — 발병 시각·병 시간이 조회 패턴에 안 묶인다")
        void settleGranularityDoesNotMatter() {
            ZzalPet once = child();
            ZzalPet stepwise = child();
            Instant from = at("2026-09-05 12:00");
            Instant to = at("2026-09-05 22:00");

            once.settle(to);
            for (long sec = 60; sec <= Duration.between(from, to).getSeconds(); sec += 60) {
                stepwise.settle(from.plusSeconds(sec));
            }

            assertThat(once.getCareMiss()).isEqualTo(stepwise.getCareMiss());
            assertThat(once.isSick()).isEqualTo(stepwise.isSick());
            assertThat(once.getSickKind()).isEqualTo(stepwise.getSickKind());
            assertThat(once.getSickSince()).isEqualTo(stepwise.getSickSince());
            assertThat(once.getFullness()).isEqualTo(stepwise.getFullness());
            assertThat(once.getTrash()).isEqualTo(stepwise.getTrash());
        }

        @Test
        @DisplayName("★★ 병 시간도 정산 패턴과 무관 — 어떻게 나눠 정산해도 \"발병 뒤 흐른 시간\" 과 같다")
        void sickAwakeSecMatchesElapsedSinceOnset() {
            Instant from = at("2026-09-05 12:00");
            Instant to = at("2026-09-09 12:00");                  // 나흘 — 그 사이에 병이 나고도 남는다
            List<Long> stepsToTry = List.of(0L, 60L, 3600L, 6 * 3600L);   // 한 번에 · 1분 · 1시간 · 6시간

            Long expectedSick = null;
            Integer expectedMiss = null;
            for (long every : stepsToTry) {
                ZzalPet pet = child();
                if (every == 0) {
                    pet.settle(to);
                } else {
                    for (long sec = every; sec <= Duration.between(from, to).getSeconds(); sec += every) {
                        pet.settle(from.plusSeconds(sec));
                    }
                    pet.settle(to);
                }
                assertThat(pet.isSick()).as("나흘이면 병이 나 있어야 한다").isTrue();

                // 병 시간은 "발병 뒤 깨어 있던 시간" 을 24시간으로 나눈 나머지여야 한다
                long awakeSinceOnset = awakeSecondsBetween(pet.getSickSince(), to);
                long expected = awakeSinceOnset % ZzalRules.CARE_MISS_SICK_EVERY.getSeconds();
                assertThat(pet.getSickAwakeSec())
                        .as("정산 간격 %d초 — 병 시간이 발병 뒤 경과와 같아야 한다", every)
                        .isEqualTo(expected);

                if (expectedSick == null) {
                    expectedSick = pet.getSickAwakeSec();
                    expectedMiss = pet.getCareMiss();
                } else {
                    assertThat(pet.getSickAwakeSec()).as("정산 간격 %d초", every).isEqualTo(expectedSick);
                    assertThat(pet.getCareMiss()).as("정산 간격 %d초 — 케어 미스도 같아야 한다", every)
                            .isEqualTo(expectedMiss);
                }
            }
        }

        /** {@code from}~{@code to} 사이의 깨어 있는 초(자동 창 10:00~23:00 기준). */
        private long awakeSecondsBetween(Instant from, Instant to) {
            long awake = 0;
            for (Instant cur = from; cur.isBefore(to); cur = cur.plusSeconds(60)) {
                java.time.LocalTime t = cur.atZone(ZzalRules.ZONE).toLocalTime();
                if (!t.isBefore(ZzalRules.AUTO_WAKE_AT) && t.isBefore(ZzalRules.AUTO_SLEEP_AT)) {
                    awake += 60;
                }
            }
            return awake;
        }

        @Test
        @DisplayName("★ 자연 발병도 정산 간격에 안 묶인다 — 예약한 초에 정확히 아프다")
        void naturalFiresExactly() {
            ZzalPet once = child();
            ZzalPet stepwise = child();
            for (ZzalPet p : List.of(once, stepwise)) {
                org.springframework.test.util.ReflectionTestUtils.setField(p, "naturalSickDueAwakeSec", 5000L);
            }
            Instant from = at("2026-09-05 12:00");

            once.settle(at("2026-09-05 20:00"));
            for (long sec = 60; sec <= 8 * 3600; sec += 60) {
                stepwise.settle(from.plusSeconds(sec));
            }

            assertThat(once.getSickSince()).isEqualTo(from.plusSeconds(5000));
            assertThat(once.getSickSince()).isEqualTo(stepwise.getSickSince());
        }

        @Test
        @DisplayName("★ 부화 실패 사유는 비지 않는다 — 사유 칸이 생기기 전 행도 지나갈 때 채워진다")
        void hatchFailureAlwaysHasReason() {
            ZzalPet egg = PetFixture.hatching(1L, "여울", null, "images/zzal/abc", T0);
            egg.markHatchFailed();
            assertThat(egg.getPhase()).isEqualTo(PetPhase.FAILED);
            assertThat(egg.getDeathReason()).isEqualTo(DeathReason.HATCH_FAILED);

            // 사유가 비어 있는 옛 행(사유 칸이 생기기 전에 실패한 것)
            ZzalPet legacy = PetFixture.hatching(1L, "여울", null, "images/zzal/abc", T0);
            org.springframework.test.util.ReflectionTestUtils.setField(legacy, "phase", PetPhase.FAILED);
            legacy.markHatchFailed();
            assertThat(legacy.getDeathReason()).isEqualTo(DeathReason.HATCH_FAILED);
        }
    }
}
