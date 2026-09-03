package com.lore.zzal.pet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 돌보기 규칙 테스트.
 *
 * ★ 이 테스트들이 가능한 것은 {@link ZzalPet} 이 시각을 <b>인자로 받기</b> 때문이다.
 *   안에서 {@code Instant.now()} 를 부르면 "6시간 뒤" 를 만들 수 없어 사실상 검증이 불가능해진다.
 *
 * 여기서 지키는 것은 "코드가 안 터지는가" 가 아니라 <b>시간이 흘렀을 때 값이 맞는가</b> 이다.
 * 수치 시스템의 버그는 대개 예외를 던지지 않고, 며칠 뒤 사용자 화면에서만 드러난다.
 */
@DisplayName("펫 — 시간과 돌봄")
class ZzalPetTest {

    private static final Instant T0 = Instant.parse("2026-09-03T09:00:00Z");

    private ZzalPet alivePet() {
        ZzalPet pet = ZzalPet.hatch(1L, "여울", null, "images/zzal/abc", T0);
        pet.markAlive("images/zzal/sheet", "생김새", T0);
        return pet;
    }

    @Nested
    @DisplayName("시간 계산")
    class Elapsed {

        @Test
        @DisplayName("★ 자주 들여다봐도 제때 배가 고파진다 — 버려지는 시간이 없다")
        void frequentPollingStillGetsHungry() {
            ZzalPet pet = alivePet();
            pet.feed(T0);
            pet.feed(T0);
            pet.feed(T0);                                   // 포만감 4
            assertThat(pet.getFullness()).isEqualTo(4);

            // 포만감은 4시간에 1칸인데 7시간마다 들여다본다.
            // 앵커를 조회 시각으로 옮기는 구현이면 매번 3시간을 버려 2칸만 줄어든다.
            Instant t = T0;
            for (int i = 0; i < 2; i++) {
                t = t.plus(Duration.ofHours(7));
                pet.applyElapsed(t);
            }

            assertThat(pet.getFullness()).isEqualTo(1);     // 14시간 → 3칸
        }

        @Test
        @DisplayName("★ 앵커는 지나간 칸 수만큼만 민다 — 남은 시간으로 확인한다")
        void anchorKeepsRemainder() {
            ZzalPet pet = alivePet();
            pet.feed(T0);
            pet.feed(T0);                                   // 재고 1, 충전 시계가 T0 에서 시작
            assertThat(pet.getFood()).isEqualTo(1);

            Instant t = T0.plus(Duration.ofHours(5));
            pet.applyElapsed(t);                            // 4시간짜리 한 칸이 지났다(나머지 1시간)

            assertThat(pet.getFood()).isEqualTo(2);
            // 앵커가 T0+4시간에 있어야 다음 밥까지 3시간 남는다.
            // 조회 시각으로 밀어버리는 구현이면 4시간이 나온다.
            assertThat(pet.foodRemainingSeconds(t)).isEqualTo(Duration.ofHours(3).getSeconds());
        }

        @Test
        @DisplayName("부화만 하고 한 번도 안 만졌으면 굶지 않는다 — 시계는 첫 돌봄에 켜진다")
        void clockStartsAtFirstCare() {
            ZzalPet pet = alivePet();

            pet.applyElapsed(T0.plus(Duration.ofDays(3)));

            assertThat(pet.getFullness()).isEqualTo(ZzalRules.WAKE_FULLNESS);
            assertThat(pet.getHappiness()).isEqualTo(ZzalRules.WAKE_HAPPINESS);
            assertThat(pet.getCareStartedAt()).isNull();
        }

        @Test
        @DisplayName("오래 방치했다 밥을 주면 그대로 남는다 — 밀린 시간이 한꺼번에 깎지 않는다")
        void feedingAfterNeglectSticks() {
            ZzalPet pet = alivePet();
            pet.feed(T0);

            Instant later = T0.plus(Duration.ofDays(3));
            pet.applyElapsed(later);
            assertThat(pet.getFullness()).isZero();

            pet.feed(later);
            pet.applyElapsed(later);

            assertThat(pet.getFullness()).isEqualTo(1);
        }

        @Test
        @DisplayName("밥은 4시간에 하나씩 차고 상한을 넘지 않는다")
        void foodCharges() {
            ZzalPet pet = alivePet();
            pet.feed(T0);
            pet.feed(T0);
            assertThat(pet.getFood()).isEqualTo(1);

            pet.applyElapsed(T0.plus(Duration.ofHours(4)));
            assertThat(pet.getFood()).isEqualTo(2);

            // 하루가 지나도 상한을 넘지 않는다
            pet.applyElapsed(T0.plus(Duration.ofDays(1)));
            assertThat(pet.getFood()).isEqualTo(ZzalRules.MAX_FOOD);
        }

        @Test
        @DisplayName("가득 찬 뒤 하나를 먹으면 충전 시계가 다시 켜진다 — 쌓인 시간이 한꺼번에 들어오지 않는다")
        void chargeRestartsAfterFull() {
            ZzalPet pet = alivePet();
            pet.feed(T0);                                   // 시계 켜짐, 재고 2
            pet.applyElapsed(T0.plus(Duration.ofDays(2)));  // 오래 지나 재고는 가득
            assertThat(pet.getFood()).isEqualTo(ZzalRules.MAX_FOOD);

            Instant t = T0.plus(Duration.ofDays(2));
            pet.feed(t);                                    // 재고 2
            pet.applyElapsed(t);

            assertThat(pet.getFood()).isEqualTo(2);
            assertThat(pet.foodRemainingSeconds(t)).isEqualTo(ZzalRules.FOOD_CHARGE.getSeconds());
        }
    }

    @Nested
    @DisplayName("훈련")
    class Train {

        @Test
        @DisplayName("훈련은 즉시 쌓이지 않고, 시간이 지나야 거둬진다")
        void trainNeedsTime() {
            ZzalPet pet = alivePet();
            pet.startTrain(T0);
            assertThat(pet.getTrainStack()).isZero();
            assertThat(pet.isTraining()).isTrue();

            pet.applyElapsed(T0.plus(ZzalRules.TRAIN_DURATION));

            assertThat(pet.getTrainStack()).isEqualTo(1);
            assertThat(pet.isTraining()).isFalse();
        }

        @Test
        @DisplayName("★ 회득량은 시작 시점에 정해진다 — 그 사이 행복이 떨어져도 약속대로 준다")
        void gainIsFixedAtStart() {
            ZzalPet pet = alivePet();
            pet.pet(T0);                                    // 행복 4 = 보너스 문턱
            assertThat(ZzalRules.trainGain(pet.getHappiness())).isEqualTo(2);

            pet.startTrain(T0);
            assertThat(pet.getTrainGain()).isEqualTo(2);

            // 훈련을 걸어두고 한참 뒤에 들어왔다 — 그 사이 행복이 떨어져 1회분 조건이 됐다
            Instant later = T0.plus(Duration.ofHours(7));
            pet.applyElapsed(later);

            assertThat(pet.getHappiness()).isEqualTo(3);
            assertThat(pet.getTrainStack()).isEqualTo(2);
        }

        @Test
        @DisplayName("해금 값은 1 → 2 → 3 → 4 로 오르고 4에서 멈춘다")
        void priceCapsAtFour() {
            assertThat(ZzalRules.priceOf(0)).isEqualTo(1);
            assertThat(ZzalRules.priceOf(1)).isEqualTo(2);
            assertThat(ZzalRules.priceOf(2)).isEqualTo(3);
            assertThat(ZzalRules.priceOf(3)).isEqualTo(4);
            assertThat(ZzalRules.priceOf(12)).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("잠과 해금")
    class Sleep {

        /** 값을 다 치러 재울 수 있는 상태로 만든다. */
        private ZzalPet readyToSleep() {
            ZzalPet pet = alivePet();
            pet.feed(T0);
            pet.startTrain(T0);
            pet.applyElapsed(T0.plus(ZzalRules.TRAIN_DURATION));
            return pet;
        }

        @Test
        @DisplayName("★ 자는 동안 수치가 멈춘다 — 자고 일어났더니 굶어 있으면 재우는 것이 손해가 된다")
        void statsFreezeWhileSleeping() {
            ZzalPet pet = readyToSleep();
            int before = pet.getFullness();

            Instant bed = T0.plus(Duration.ofMinutes(1));
            pet.goToSleep(bed);
            pet.applyElapsed(bed.plus(Duration.ofHours(6)));

            assertThat(pet.getFullness()).isEqualTo(before);
        }

        @Test
        @DisplayName("깨우고 하나를 배우면 치른 값이 빠진다")
        void wakingUnlocks() {
            ZzalPet pet = readyToSleep();
            Instant bed = T0.plus(Duration.ofMinutes(1));
            pet.goToSleep(bed);

            Instant morning = bed.plus(ZzalRules.SLEEP_DURATION);
            assertThat(pet.canWake(morning)).isTrue();
            pet.wakeUp(morning);
            pet.unlockOne();

            assertThat(pet.getUnlockedCount()).isEqualTo(1);
            assertThat(pet.getTrainStack()).isZero();
            assertThat(pet.isSleeping()).isFalse();
            assertThat(pet.trainPrice()).isEqualTo(2);       // 다음은 2번
        }

        @Test
        @DisplayName("★ 굽는 데 실패해 못 배웠으면 연습을 빼앗지 않는다")
        void failedBakeKeepsTrainStack() {
            ZzalPet pet = readyToSleep();
            int paid = pet.getTrainStack();
            Instant bed = T0.plus(Duration.ofMinutes(1));
            pet.goToSleep(bed);

            // 깨어나기는 하지만 배운 것이 없다 → unlockOne() 을 부르지 않는다
            pet.wakeUp(bed.plus(ZzalRules.SLEEP_DURATION));

            assertThat(pet.getUnlockedCount()).isZero();
            assertThat(pet.getTrainStack()).isEqualTo(paid);
            assertThat(pet.isSleeping()).isFalse();
        }

        @Test
        @DisplayName("덜 잤으면 못 깨운다")
        void cannotWakeEarly() {
            ZzalPet pet = readyToSleep();
            Instant bed = T0.plus(Duration.ofMinutes(1));
            pet.goToSleep(bed);

            assertThat(pet.canWake(bed.plus(Duration.ofHours(5)))).isFalse();
        }

        @Test
        @DisplayName("★ 깨운 직후에 밀린 시간이 한꺼번에 들어오지 않는다 — 앵커를 잔 만큼 민다")
        void noBacklogAfterWaking() {
            ZzalPet pet = readyToSleep();
            int before = pet.getFullness();

            Instant bed = T0.plus(Duration.ofMinutes(1));
            pet.goToSleep(bed);

            Instant morning = bed.plus(ZzalRules.SLEEP_DURATION);
            pet.wakeUp(morning);
            pet.unlockOne();
            pet.applyElapsed(morning);

            assertThat(pet.getFullness()).isEqualTo(before);
        }
    }
}
