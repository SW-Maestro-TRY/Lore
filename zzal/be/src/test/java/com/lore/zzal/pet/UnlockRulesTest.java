package com.lore.zzal.pet;

import com.lore.zzal.PetFixture;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.MotionSpec;
import com.lore.zzal.motion.UnlockRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.lore.zzal.pet.AwakeClockTest.kst;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2층 해금 — 조건표를 카운터에 대 본다. 저장하지 않고 계산한다.
 *
 * <h3>★ 경계값으로 본다</h3>
 * 여덟 종을 전부 {@code target-1} 에서 잠김 · {@code target} 에서 열림으로 확인한다. 목표치 하나가
 * 밀리면 화면은 "왜 아직 안 열리지" 로만 보이고, 어느 숫자가 틀렸는지는 드러나지 않는다.
 */
@DisplayName("해금 규칙 — 카운터에서 계산")
class UnlockRulesTest {

    private static final Instant T0 = kst("2026-09-05 12:00");
    private static final MotionCatalog CATALOG = new MotionCatalog("", "", "v1");

    private static ZzalPet baby() {
        day = 5;
        ZzalPet pet = PetFixture.hatching(1L, "여울", null, "k", T0);
        pet.markAlive("s", "i", T0);
        pet.skipTutorial(T0);
        return pet;
    }

    @Test
    @DisplayName("1층 8종은 처음부터, 2층 8종은 잠김, 선물은 기본 행동이 없다")
    void initialState() {
        ZzalPet pet = baby();
        assertThat(UnlockRules.unlockedKeys(pet, CATALOG)).containsExactly(
                "base", "eat", "joy", "sad", "sick", "pet", "hello", "sleep");
        assertThat(UnlockRules.openedLayerTwo(pet, CATALOG)).isZero();
        assertThat(UnlockRules.isUnlocked(pet, CATALOG.bySeq(101).orElseThrow(), CATALOG)).isFalse();
    }

    @Test
    @DisplayName("★ 밥 9회 → 밥 먹기(9). 8회까지는 잠겨 있다")
    void feedsOpenEatRice() {
        assertOpensAt("eat_rice", (pet, n) -> {
            for (int i = 0; i < n; i++) {
                pet.grantFood(T0);
                pet.feed(T0);
            }
        });
    }

    @Test
    @DisplayName("★ 간식 9개 → 간식 먹기(10). 하루 4개까지만 세므로 사흘이 걸린다")
    void snacksOpenEatSnack() {
        ZzalPet pet = baby();
        MotionSpec spec = CATALOG.byKey("eat_snack").orElseThrow();

        feedSnacksOverDays(pet, 8);
        assertThat(UnlockRules.current(pet, UnlockRule.Kind.SNACKS, CATALOG)).isEqualTo(8);
        assertThat(UnlockRules.isUnlocked(pet, spec, CATALOG)).isFalse();

        feedSnacksOverDays(pet, 1);
        assertThat(UnlockRules.isUnlocked(pet, spec, CATALOG)).isTrue();
    }

    @Test
    @DisplayName("★ 청소 13회 → 청소(11)")
    void cleansOpenSweep() {
        assertOpensAt("sweep", (pet, n) -> {
            for (int i = 0; i < n; i++) {
                pet.clean(T0);
            }
        });
    }

    @Test
    @DisplayName("★ 목욕 3회 → 씻기(12)")
    void bathOpensWash() {
        assertOpensAt("wash", (pet, n) -> {
            for (int i = 0; i < n; i++) {
                pet.bath(T0);
            }
        });
    }

    @Test
    @DisplayName("★ 채팅 답 4회 → 답하기(13)")
    void chatAnswersOpenReply() {
        assertOpensAt("reply", (pet, n) -> {
            for (int i = 0; i < n; i++) {
                pet.answerChat();
            }
        });
    }

    @Test
    @DisplayName("★ 쓰다듬 4회 → 쓰다듬 받기(14). 하루 친밀도 한도(3회)를 넘겨도 전부 센다")
    void petsOpenPetted() {
        assertOpensAt("petted", (pet, n) -> {
            for (int i = 0; i < n; i++) {
                pet.pet(T0);
            }
        });
    }

    @Test
    @DisplayName("★ 게임 4판 → 놀라기(15)")
    void gamesOpenStartle() {
        assertOpensAt("startle", (pet, n) -> {
            for (int i = 0; i < n; i++) {
                pet.startGame();
            }
        });
    }

    @Test
    @DisplayName("★★ 깨우기 4회 → 일어나기(16). 손으로 깬 밤잠만 센다")
    void manualWakesOpenWakeUp() {
        ZzalPet pet = baby();
        MotionSpec spec = CATALOG.byKey("wake_up").orElseThrow();

        wakeNights(pet, 3);
        assertThat(UnlockRules.current(pet, UnlockRule.Kind.WAKES, CATALOG)).isEqualTo(3);
        assertThat(UnlockRules.isUnlocked(pet, spec, CATALOG)).isFalse();

        wakeNights(pet, 1);
        assertThat(UnlockRules.isUnlocked(pet, spec, CATALOG)).isTrue();
    }

    @Test
    @DisplayName("★★ 조건은 그 행동 자체다 — 여덟 종이 서로 다른 카운터를 본다")
    void everyLayerTwoWatchesItsOwnAction() {
        List<UnlockRule.Kind> kinds = CATALOG.basic().stream()
                .filter(m -> m.layer() == com.lore.zzal.motion.MotionLayer.BASIC_2)
                .map(m -> m.unlockRule().kind())
                .toList();

        assertThat(kinds).hasSize(8).doesNotHaveDuplicates()
                .containsExactly(UnlockRule.Kind.FEEDS, UnlockRule.Kind.SNACKS, UnlockRule.Kind.CLEANS,
                        UnlockRule.Kind.BATH, UnlockRule.Kind.CHAT_ANSWERS, UnlockRule.Kind.PET_COUNT,
                        UnlockRule.Kind.GAME_STARTS, UnlockRule.Kind.WAKES);
    }

    // ── 거들기 ───────────────────────────────────────────────────────────

    /** {@code target-1} 에서 잠겨 있고 {@code target} 에서 열리는지. 목표치는 카탈로그에서 읽는다. */
    private void assertOpensAt(String key, Repeat action) {
        MotionSpec spec = CATALOG.byKey(key).orElseThrow();
        int target = spec.unlockRule().target();

        ZzalPet justBefore = baby();
        action.run(justBefore, target - 1);
        assertThat(UnlockRules.isUnlocked(justBefore, spec, CATALOG))
                .as("%s 는 %d 에서 아직 잠겨 있어야 한다", key, target - 1)
                .isFalse();

        ZzalPet atTarget = baby();
        action.run(atTarget, target);
        assertThat(UnlockRules.isUnlocked(atTarget, spec, CATALOG))
                .as("%s 는 %d 에서 열려야 한다", key, target)
                .isTrue();
    }

    /** 하루 4개 상한을 지키며 간식을 {@code count} 개 <b>세지게</b> 먹인다(밤을 넘기면 하루치가 0 이 된다). */
    private static void feedSnacksOverDays(ZzalPet pet, int count) {
        for (int i = 0; i < count; i++) {
            if (pet.getTodaySnacks() >= ZzalRules.SNACK_DAILY_SICK_AT - 1) {
                night(pet);
            }
            pet.snack(T0);
        }
    }

    /** 밤잠을 {@code nights} 번 재우고 <b>손으로</b> 깨운다. */
    private static void wakeNights(ZzalPet pet, int nights) {
        for (int i = 0; i < nights; i++) {
            night(pet);
        }
    }

    /**
     * 하룻밤 — 20:00 에 재우고 다음 날 08:00 에 <b>손으로</b> 깨운다.
     *
     * ★ 재우기·깨우기 창이 정해져 있어(19~23시 · 07~10시) 아무 시각이나 쓸 수 없다.
     *   시험이 날짜를 직접 세는 이유다.
     */
    private static int day = 5;

    private static void night(ZzalPet pet) {
        Instant sleepAt = kst("2026-09-%02d 20:00".formatted(day));
        Instant wakeAt = kst("2026-09-%02d 08:00".formatted(day + 1));
        pet.settle(sleepAt);
        pet.sleep(sleepAt);
        pet.wake(wakeAt);
        day += 1;
    }

    @FunctionalInterface
    private interface Repeat {
        void run(ZzalPet pet, int times);
    }
}
