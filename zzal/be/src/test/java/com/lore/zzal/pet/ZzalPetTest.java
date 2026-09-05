package com.lore.zzal.pet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

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

    /** 방금 부화한 아기(T0). 배부름 1·행복 3·흔적 0·밥 3. */
    private static ZzalPet baby() {
        ZzalPet pet = ZzalPet.hatch(1L, "여울", null, "images/zzal/abc", T0.minus(Duration.ofMinutes(3)));
        pet.markAlive("images/zzal/sheet", "생김새", T0);
        return pet;
    }

    /**
     * 어린이(아기 60분이 끝난 펫). T0 에 배부름 3·행복 3·흔적 0·밥 0(충전 시계 T0 에서 시작)·누적초 0.
     *
     * 11:00 에 부화해 아기 한 시간을 그냥 보내면(배부름 20칸·행복 15칸·흔적 4개가 떨어져 0/0/4),
     * 정오에 밥 3·간식 3·청소 1 로 채운 상태다. 아기 60분은 3600초라 세 누적초가 정확히 0 으로 떨어진다.
     */
    private static ZzalPet child() {
        Instant hatched = T0.minus(Duration.ofMinutes(60));
        ZzalPet pet = ZzalPet.hatch(1L, "여울", null, "images/zzal/abc", hatched);
        pet.markAlive("images/zzal/sheet", "생김새", hatched);
        pet.settle(T0);
        assertThat(pet.getFullness()).isZero();
        assertThat(pet.getTrash()).isEqualTo(4);
        pet.feed(T0);
        pet.feed(T0);
        pet.feed(T0);
        pet.snack(T0);
        pet.snack(T0);
        pet.snack(T0);
        pet.clean(T0);
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
        @DisplayName("★ 아기 60분은 원조 아기 속도 — 배부름 3분·행복 4분·흔적 15분")
        void babySpeed() {
            ZzalPet pet = baby();
            pet.settle(T0.plus(Duration.ofMinutes(3)));
            assertThat(pet.getFullness()).isZero();           // 1 → 0
            assertThat(pet.getHappiness()).isEqualTo(3);
            pet.settle(T0.plus(Duration.ofMinutes(4)));
            assertThat(pet.getHappiness()).isEqualTo(2);
            pet.settle(T0.plus(Duration.ofMinutes(15)));
            assertThat(pet.getTrash()).isEqualTo(1);           // 12장 15분 "첫 똥"
            pet.settle(T0.plus(Duration.ofMinutes(60)));
            assertThat(pet.getTrash()).isEqualTo(4);
        }

        @Test
        @DisplayName("60분이 지나면 어린이 속도 — 그 뒤 3시간에야 한 칸")
        void afterBabyChildSpeed() {
            ZzalPet pet = baby();
            pet.settle(T0.plus(Duration.ofMinutes(60)));
            pet.grantFood(T0.plus(Duration.ofMinutes(60)));
            pet.feed(T0.plus(Duration.ofMinutes(60)));        // 배부름 1
            pet.settle(T0.plus(Duration.ofMinutes(60 + 179)));
            assertThat(pet.getFullness()).isEqualTo(1);
            pet.settle(T0.plus(Duration.ofMinutes(60 + 180)));
            assertThat(pet.getFullness()).isZero();
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
        @DisplayName("★ 아기 60분 중 23:00 을 넘기면 60분이 끝나는 순간 잠든다")
        void babyCrossingEleven() {
            Instant hatched = at("2026-09-05 22:30");
            ZzalPet pet = ZzalPet.hatch(1L, "여울", null, "k", hatched);
            pet.markAlive("s", "i", hatched);

            pet.settle(at("2026-09-05 23:15"));
            assertThat(pet.isSleeping()).isFalse();
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
        @DisplayName("★ 낮잠(12장 40분) — 아기 때 한 번, 5분 뒤 깨우기, 10분 뒤 자동 기상, 횟수에 포함")
        void nap() {
            ZzalPet pet = baby();
            Instant t40 = T0.plus(Duration.ofMinutes(40));
            pet.settle(t40);
            assertThat(pet.sleepKindAvailable(t40)).isEqualTo(SleepKind.NAP);
            pet.sleep(t40);
            assertThat(pet.getSleepKind()).isEqualTo(SleepKind.NAP);
            assertThat(pet.getSleepWakeCount()).isEqualTo(1);

            assertThat(pet.canWake(t40.plus(Duration.ofMinutes(4)))).isFalse();
            assertThat(pet.canWake(t40.plus(Duration.ofMinutes(5)))).isTrue();

            int intimacy = pet.getIntimacy();
            pet.settle(t40.plus(Duration.ofMinutes(11)));      // 안 깨움 → 10분 뒤 자동
            assertThat(pet.isSleeping()).isFalse();
            assertThat(pet.getNapCount()).isEqualTo(1);
            assertThat(pet.getIntimacy()).isEqualTo(intimacy);   // 낮잠은 재우기·깨우기 둘 다 보상 0(해석 16)
            assertThat(pet.getWokeAt()).isEqualTo(T0);          // 낮잠은 기상 시각이 아니다

            // 두 번째 낮잠은 없다(해석 3). 아직 아기지만 창 밖.
            assertThat(pet.sleepKindAvailable(t40.plus(Duration.ofMinutes(12)))).isNull();
        }

        @Test
        @DisplayName("낮잠 보상 없음 — 수동으로 깨워도 친밀도 0, 밤잠만 +10 (해석 16)")
        void napGivesNoReward() {
            ZzalPet pet = baby();
            Instant t = T0.plus(Duration.ofMinutes(40));
            pet.settle(t);
            pet.sleep(t);
            pet.settle(t.plus(Duration.ofMinutes(5)));
            pet.wake(t.plus(Duration.ofMinutes(5)));
            assertThat(pet.getIntimacy()).isZero();
            assertThat(pet.getSleepWakeCount()).isEqualTo(2);   // 횟수(2층 11번 조건)에는 든다
        }

        @Test
        @DisplayName("★ 낮잠에서 깬 순간이 이미 밤이고 아기 60분도 끝났으면 그 자리에서 밤잠에 든다 — 행동 응답 = 최신 상태")
        void wakingFromNapIntoNightSleepsImmediately() {
            Instant hatched = at("2026-09-05 22:35");
            ZzalPet pet = ZzalPet.hatch(1L, "여울", null, "k", hatched);
            pet.markAlive("s", "i", hatched);
            pet.settle(at("2026-09-05 23:30"));
            pet.sleep(at("2026-09-05 23:30"));                   // 낮잠(아기 60분 안)
            pet.settle(at("2026-09-05 23:36"));
            pet.wake(at("2026-09-05 23:36"));                    // 60분(23:35) 지났고 밤
            assertThat(pet.isSleeping()).isTrue();
            assertThat(pet.getSleepKind()).isEqualTo(SleepKind.NIGHT);
            assertThat(pet.getSleptAt()).isEqualTo(at("2026-09-05 23:36"));
            assertThat(pet.getNapCount()).isEqualTo(1);
            // 아직 아기면 깨어 있는다 — 유예(16장)
            ZzalPet late = ZzalPet.hatch(1L, "여울", null, "k", at("2026-09-05 22:50"));
            late.markAlive("s", "i", at("2026-09-05 22:50"));
            late.sleep(at("2026-09-05 23:30"));
            late.settle(at("2026-09-05 23:36"));
            late.wake(at("2026-09-05 23:36"));
            assertThat(late.isSleeping()).isFalse();
        }

        @Test
        @DisplayName("낮잠 동안 게이지 정지")
        void napFreezes() {
            ZzalPet pet = baby();
            pet.settle(T0.plus(Duration.ofMinutes(2)));         // 배부름 나머지 2분
            pet.sleep(T0.plus(Duration.ofMinutes(2)));
            pet.settle(T0.plus(Duration.ofMinutes(7)));         // 낮잠 5분
            assertThat(pet.getFullness()).isEqualTo(1);
            pet.wake(T0.plus(Duration.ofMinutes(7)));
            pet.settle(T0.plus(Duration.ofMinutes(8)));         // 나머지 2분 + 1분 = 3분
            assertThat(pet.getFullness()).isZero();
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
        @DisplayName("★ 아기 60분 동안은 없다 — 0 이 된 지 6시간이 지나도 60분 뒤부터 센다")
        void noneDuringBaby() {
            ZzalPet pet = baby();                               // 12:00 부화 → 12:03 배부름 0
            pet.settle(at("2026-09-05 18:59"));                 // 아기 포함이면 18:03 에 +1 이었을 것
            assertThat(pet.getCareMiss()).isZero();
            pet.settle(at("2026-09-05 19:00"));                 // 13:00 부터 6h — 셋 다 0 이라 한꺼번에 3
            assertThat(pet.getCareMiss()).isEqualTo(3);
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
            assertThat(pet.getSnackStreak()).isEqualTo(1);

            pet.settle(at("2026-09-05 19:00"));
            pet.sleep(at("2026-09-05 19:00"));
            assertThat(pet.getTodayPetCount()).isZero();
            assertThat(pet.isTodayBathDone()).isFalse();
            assertThat(pet.getSnackStreak()).isZero();
            assertThat(pet.getTodayCareIntimacy()).isZero();
        }

        @Test
        @DisplayName("낮잠은 경계가 아니다 — 카운터가 남는다")
        void napIsNotBoundary() {
            ZzalPet pet = baby();
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
        @DisplayName("간식 연속 — 다른 행동이 하나라도 끼면 0 (해석 2)")
        void snackStreak() {
            ZzalPet pet = baby();
            pet.snack(T0);
            pet.snack(T0);
            pet.snack(T0);
            pet.snack(T0);
            assertThat(pet.getSnackStreak()).isEqualTo(4);
            pet.pet(T0);
            assertThat(pet.getSnackStreak()).isZero();
            for (int i = 0; i < 5; i++) {
                pet.snack(T0);
            }
            assertThat(pet.getSnackStreak()).isEqualTo(ZzalRules.SNACK_STREAK_SICK_AT);
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
        @DisplayName("부화 = 시계 켜짐. 초기값 배부름 1·행복 3·흔적 0·밥 3(해석 11)")
        void hatchStartsClock() {
            ZzalPet pet = baby();
            assertThat(pet.getSettledAt()).isEqualTo(T0);
            assertThat(pet.getWokeAt()).isEqualTo(T0);
            assertThat(pet.babyUntil()).isEqualTo(T0.plus(Duration.ofMinutes(60)));
            assertThat(pet.getFullness()).isEqualTo(1);
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

            ZzalPet egg = ZzalPet.hatch(1L, "알", null, "k", T0);
            egg.release(T0);
            assertThat(egg.getPhase()).isEqualTo(PetPhase.HATCHING);
        }

        @Test
        @DisplayName("ALIVE 가 아니면 정산해도 아무 일 없다")
        void settleIgnoresNonAlive() {
            ZzalPet egg = ZzalPet.hatch(1L, "알", null, "k", T0);
            egg.settle(at("2026-09-08 12:00"));
            assertThat(egg.isSleeping()).isFalse();
            assertThat(egg.getSettledAt()).isNull();
        }
    }
}
