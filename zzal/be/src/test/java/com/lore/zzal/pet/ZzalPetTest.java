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

    /**
     * 첫날 순서를 끝낸 펫 = <b>수치 시계가 도는</b> 펫.
     *
     * ★ 수치를 다루는 테스트는 전부 이쪽을 쓴다. 튜토리얼 전에는 시간이 지나도 아무것도
     *   줄지 않으므로, {@link #alivePet()} 으로 감소를 검증하면 <b>항상 통과하는 빈 테스트</b>가 된다.
     */
    private ZzalPet tutoredPet() {
        ZzalPet pet = alivePet();
        pet.completeTutorial(T0);
        return pet;
    }

    @Nested
    @DisplayName("시간 계산")
    class Elapsed {

        @Test
        @DisplayName("★ 자주 들여다봐도 제때 배가 고파진다 — 버려지는 시간이 없다")
        void frequentPollingStillGetsHungry() {
            ZzalPet pet = tutoredPet();
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
            ZzalPet pet = tutoredPet();
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
        @DisplayName("부화만 하고 첫날 순서를 안 끝냈으면 굶지 않는다 — 시계는 튜토리얼 완료에 켜진다")
        void clockStartsWhenTutorialIsDone() {
            ZzalPet pet = alivePet();

            pet.applyElapsed(T0.plus(Duration.ofDays(3)));

            assertThat(pet.getFullness()).isEqualTo(ZzalRules.WAKE_FULLNESS);
            assertThat(pet.getHappiness()).isEqualTo(ZzalRules.WAKE_HAPPINESS);
            assertThat(pet.getCareStartedAt()).isNull();
        }

        @Test
        @DisplayName("오래 방치했다 밥을 주면 그대로 남는다 — 밀린 시간이 한꺼번에 깎지 않는다")
        void feedingAfterNeglectSticks() {
            ZzalPet pet = tutoredPet();
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
            ZzalPet pet = tutoredPet();
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
            ZzalPet pet = tutoredPet();
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
    @DisplayName("보내기(놓아주기)")
    class Release {

        @Test
        @DisplayName("보내면 DEAD 가 되고 이유는 RELEASED 다 — 방치·실패와 구분된다")
        void releaseMarksDead() {
            ZzalPet pet = alivePet();
            pet.feed(T0);

            pet.release(T0.plus(Duration.ofHours(1)));

            assertThat(pet.getPhase()).isEqualTo(PetPhase.DEAD);
            assertThat(pet.getDeathReason()).isEqualTo(DeathReason.RELEASED);
            assertThat(pet.isAlive()).isFalse();
        }

        @Test
        @DisplayName("★ 보낸 아이는 자리를 차지하지 않는다 — 슬롯 계산에 DEAD 가 안 들어간다")
        void releasedPetDoesNotOccupyASlot() {
            ZzalPet pet = alivePet();
            pet.release(T0);

            // 자리를 차지하는 단계는 HATCHING·ALIVE 뿐이다. 이 목록에 DEAD 가 끼면
            // 펫을 보내도 "자리 없음" 으로 막혀 새로 시작할 수 없게 된다.
            assertThat(PetPhase.OCCUPYING_SLOT).containsExactly(PetPhase.HATCHING, PetPhase.ALIVE);
            assertThat(PetPhase.OCCUPYING_SLOT).doesNotContain(PetPhase.DEAD, PetPhase.FAILED);
            assertThat(PetPhase.OCCUPYING_SLOT).doesNotContain(pet.getPhase());
        }

        @Test
        @DisplayName("★ 부화 중인 알은 보내지지 않는다 — 굽고 있는 작업이 붕 뜬다")
        void hatchingPetIsNotReleased() {
            ZzalPet egg = ZzalPet.hatch(1L, "여울", null, "images/zzal/abc", T0);

            egg.release(T0.plus(Duration.ofMinutes(1)));

            assertThat(egg.getPhase()).isEqualTo(PetPhase.HATCHING);
            assertThat(egg.getDeathReason()).isNull();
        }

        @Test
        @DisplayName("실패한 알에 불러도 상태가 망가지지 않는다 — 실패 사유가 덮이지 않는다")
        void releasingFailedPetKeepsItsReason() {
            ZzalPet egg = ZzalPet.hatch(1L, "여울", null, "images/zzal/abc", T0);
            egg.markHatchFailed();

            egg.release(T0.plus(Duration.ofMinutes(1)));

            assertThat(egg.getPhase()).isEqualTo(PetPhase.FAILED);
            assertThat(egg.getDeathReason()).isEqualTo(DeathReason.HATCH_FAILED);
        }

        @Test
        @DisplayName("두 번 보내도 안전하다 — 두 번째는 아무 일도 하지 않는다")
        void releasingTwiceIsSafe() {
            ZzalPet pet = alivePet();
            pet.release(T0);
            pet.release(T0.plus(Duration.ofDays(1)));

            assertThat(pet.getPhase()).isEqualTo(PetPhase.DEAD);
            assertThat(pet.getDeathReason()).isEqualTo(DeathReason.RELEASED);
        }

        @Test
        @DisplayName("★★ 보내도 배운 것과 만든 것은 남는다 — 돈을 써서 구운 결과물이고, 재회가 붙을 자리다")
        void releaseKeepsWhatWasMade() {
            ZzalPet pet = alivePet();
            pet.feed(T0);
            pet.startTrain(T0);
            pet.applyElapsed(T0.plus(ZzalRules.TRAIN_DURATION));
            pet.unlockOne();
            assertThat(pet.getUnlockedCount()).isEqualTo(1);

            pet.release(T0.plus(ZzalRules.TRAIN_DURATION));

            assertThat(pet.getUnlockedCount()).isEqualTo(1);
            assertThat(pet.getSheetImageKey()).isEqualTo("images/zzal/sheet");
            assertThat(pet.getSourceImageKey()).isEqualTo("images/zzal/abc");
            assertThat(pet.getIdentityText()).isEqualTo("생김새");
            assertThat(pet.getHatchedAt()).isEqualTo(T0);
        }

        @Test
        @DisplayName("보낸 뒤에는 시간이 흘러도 수치가 움직이지 않는다 — ALIVE 가 아니다")
        void statsStopAfterRelease() {
            ZzalPet pet = alivePet();
            pet.feed(T0);
            int fullness = pet.getFullness();

            pet.release(T0);
            pet.applyElapsed(T0.plus(Duration.ofDays(3)));

            assertThat(pet.getFullness()).isEqualTo(fullness);
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
            ZzalPet pet = tutoredPet();
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
            ZzalPet pet = tutoredPet();
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

            Instant morning = bed.plus(ZzalRules.sleepDuration(0));
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
            pet.wakeUp(bed.plus(ZzalRules.sleepDuration(0)));

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

            assertThat(pet.canWake(bed.plus(Duration.ofMinutes(4)))).isFalse();
        }

        @Test
        @DisplayName("★ 첫 잠은 5분이고 뒤로 갈수록 길어진다 — 첫날에 한 바퀴가 끝나야 한다")
        void sleepGrowsWithProgress() {
            assertThat(ZzalRules.sleepDuration(0)).isEqualTo(Duration.ofMinutes(5));
            assertThat(ZzalRules.sleepDuration(1)).isEqualTo(Duration.ofMinutes(15));
            assertThat(ZzalRules.sleepDuration(2)).isEqualTo(Duration.ofHours(1));
            assertThat(ZzalRules.sleepDuration(3)).isEqualTo(Duration.ofHours(3));
            // 마지막 값에서 고정 — 계속 늘리면 뒤쪽 해금이 사실상 멈춘다
            assertThat(ZzalRules.sleepDuration(11)).isEqualTo(Duration.ofHours(3));
        }

        @Test
        @DisplayName("★ 깨운 직후에 밀린 시간이 한꺼번에 들어오지 않는다 — 앵커를 잔 만큼 민다")
        void noBacklogAfterWaking() {
            ZzalPet pet = readyToSleep();
            int before = pet.getFullness();

            Instant bed = T0.plus(Duration.ofMinutes(1));
            pet.goToSleep(bed);

            Instant morning = bed.plus(ZzalRules.sleepDuration(0));
            pet.wakeUp(morning);
            pet.unlockOne();
            pet.applyElapsed(morning);

            assertThat(pet.getFullness()).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("첫날 순서(튜토리얼)")
    class Tutorial {

        @Test
        @DisplayName("★ 튜토리얼을 안 끝냈으면 시간이 아무리 지나도 수치가 안 준다")
        void statsDoNotDropBeforeTutorialIsDone() {
            ZzalPet pet = alivePet();
            pet.feed(T0);                                   // 첫날 순서의 첫 칸
            int fullness = pet.getFullness();

            pet.applyElapsed(T0.plus(Duration.ofDays(3)));

            assertThat(pet.getFullness()).isEqualTo(fullness);
            assertThat(pet.getHappiness()).isEqualTo(ZzalRules.WAKE_HAPPINESS);
            // 쓰레기는 밥 한 번으로 는 1뿐이다 — 사흘이 지나도 시간으로는 안 쌓였다
            assertThat(pet.getTrash()).isEqualTo(ZzalRules.FEED_TRASH);
            // 시계가 안 켜졌다 = 첫 돌봄이 아니라 튜토리얼 완료가 열쇠다
            assertThat(pet.getCareStartedAt()).isNull();
            assertThat(pet.isTutorialDone()).isFalse();
        }

        @Test
        @DisplayName("★ 튜토리얼을 끝내면 그때부터 준다 — 시계가 그 순간 켜진다")
        void statsStartDroppingAfterTutorial() {
            ZzalPet pet = alivePet();
            pet.feed(T0);
            pet.applyElapsed(T0.plus(Duration.ofDays(3)));   // 아직 안 끝냈으니 그대로

            Instant done = T0.plus(Duration.ofDays(3));
            pet.completeTutorial(done);
            assertThat(pet.isTutorialDone()).isTrue();
            assertThat(pet.getCareStartedAt()).isEqualTo(done);

            // 끝낸 시점을 출발점으로 6시간 = 포만감 1칸(4h), 행복 1칸(6h)
            pet.applyElapsed(done.plus(Duration.ofHours(6)));

            assertThat(pet.getFullness()).isEqualTo(1);      // 2 → 1
            assertThat(pet.getHappiness()).isEqualTo(2);     // 3 → 2
        }

        @Test
        @DisplayName("★ 두 번 알려도 안전하다 — 앵커가 다시 밀리지 않는다")
        void completingTwiceDoesNotResetTheClock() {
            ZzalPet pet = tutoredPet();
            pet.feed(T0);
            pet.feed(T0);
            pet.feed(T0);                                    // 포만감 4

            Instant later = T0.plus(Duration.ofHours(9));
            pet.applyElapsed(later);                         // 2칸 지남(앵커는 T0+8시간)
            assertThat(pet.getFullness()).isEqualTo(2);

            pet.completeTutorial(later);                     // 새로고침 뒤 다시 알림

            assertThat(pet.getTutorialDoneAt()).isEqualTo(T0);
            assertThat(pet.getCareStartedAt()).isEqualTo(T0);
            // ★ 앵커가 later 로 밀렸다면 아래 3시간으로는 한 칸도 안 지나 4가 남는다.
            //   안 밀렸으면 T0+8시간부터 4시간이 지나 한 칸 준다.
            pet.applyElapsed(later.plus(Duration.ofHours(3)));
            assertThat(pet.getFullness()).isEqualTo(1);
        }

        @Test
        @DisplayName("★★ 튜토리얼 중에도 연습은 끝난다 — 이걸 막으면 '연습 → 재우기' 에서 갇힌다")
        void trainingStillFinishesDuringTutorial() {
            ZzalPet pet = alivePet();
            pet.feed(T0);
            pet.startTrain(T0);

            pet.applyElapsed(T0.plus(ZzalRules.TRAIN_DURATION));

            assertThat(pet.isTraining()).isFalse();
            assertThat(pet.getTrainStack()).isEqualTo(1);
            assertThat(pet.isTrainPaid()).isTrue();          // 이제 재울 수 있다
            // 그래도 수치는 그대로다 — 막는 것은 줄어드는 것뿐이다
            assertThat(pet.getHappiness()).isEqualTo(ZzalRules.WAKE_HAPPINESS);
        }

        @Test
        @DisplayName("튜토리얼 중에는 다음 밥까지 남은 시간이 없다 — 안 차는데 0초로 보이면 안 된다")
        void noFoodCountdownBeforeTutorialIsDone() {
            ZzalPet pet = alivePet();
            pet.feed(T0);

            assertThat(pet.foodRemainingSeconds(T0.plus(Duration.ofHours(5)))).isNull();

            pet.completeTutorial(T0.plus(Duration.ofHours(5)));
            assertThat(pet.foodRemainingSeconds(T0.plus(Duration.ofHours(5)))).isNotNull();
        }
    }

    @Nested
    @DisplayName("완주 판정")
    class Complete {

        @Test
        @DisplayName("★ 동작 목록이 2개면 2개를 모았을 때 완주다 — 13이 아니다")
        void completesAtCatalogSize() {
            ZzalPet pet = alivePet();
            assertThat(pet.isComplete(2)).isFalse();

            pet.unlockOne();
            assertThat(pet.isComplete(2)).isFalse();

            pet.unlockOne();
            assertThat(pet.getUnlockedCount()).isEqualTo(2);
            assertThat(pet.isComplete(2)).isTrue();
        }

        @Test
        @DisplayName("목록이 비어 있으면 완주가 아니다 — 0개를 다 모았다고 하면 연습·재우기가 통째로 막힌다")
        void emptyCatalogIsNeverComplete() {
            ZzalPet pet = alivePet();

            assertThat(pet.isComplete(0)).isFalse();

            pet.unlockOne();
            assertThat(pet.isComplete(0)).isFalse();
        }
    }

    @Nested
    @DisplayName("시간 당기기(dev)")
    class AdvanceClock {

        @Test
        @DisplayName("★ 당긴 만큼 실제로 수치가 준다 — 규칙은 그대로 두고 앵커만 민다")
        void rewindingDropsStats() {
            ZzalPet pet = alivePet();
            pet.completeTutorial(T0);
            pet.feed(T0);
            pet.feed(T0);
            pet.feed(T0);                                    // 포만감 4, 재고 0
            assertThat(pet.getFullness()).isEqualTo(4);

            pet.rewindClock(Duration.ofHours(8));
            pet.applyElapsed(T0);                            // '지금' 은 그대로인데 8시간이 흐른 셈

            assertThat(pet.getFullness()).isEqualTo(2);      // 4시간에 1칸 → 2칸
            assertThat(pet.getHappiness()).isEqualTo(2);     // 6시간에 1칸 → 1칸
            assertThat(pet.getTrash()).isEqualTo(4);         // 밥 3번(3) + 8시간에 1칸
            assertThat(pet.getFood()).isEqualTo(2);          // 4시간에 1개 → 2개
        }

        @Test
        @DisplayName("당기면 덜 잔 펫도 깨울 수 있게 된다 — 5분을 손으로 기다리지 않는다")
        void rewindingFinishesSleep() {
            ZzalPet pet = alivePet();
            pet.completeTutorial(T0);
            pet.feed(T0);
            pet.startTrain(T0);
            pet.applyElapsed(T0.plus(ZzalRules.TRAIN_DURATION));
            pet.goToSleep(T0.plus(ZzalRules.TRAIN_DURATION));

            Instant t = T0.plus(ZzalRules.TRAIN_DURATION);
            assertThat(pet.canWake(t)).isFalse();

            pet.rewindClock(ZzalRules.sleepDuration(0));

            assertThat(pet.canWake(t)).isTrue();
        }

        @Test
        @DisplayName("튜토리얼을 안 끝낸 펫은 당겨도 수치가 그대로다 — 시계가 아직 없다")
        void rewindingDoesNothingBeforeTutorial() {
            ZzalPet pet = alivePet();
            pet.feed(T0);
            int fullness = pet.getFullness();

            pet.rewindClock(Duration.ofDays(3));
            pet.applyElapsed(T0);

            assertThat(pet.getFullness()).isEqualTo(fullness);
            assertThat(pet.getCareStartedAt()).isNull();
        }
    }
}
