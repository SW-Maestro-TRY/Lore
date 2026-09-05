package com.lore.zzal.pet;

import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.UnlockRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.lore.zzal.pet.AwakeClockTest.kst;
import static org.assertj.core.api.Assertions.assertThat;

/** 2층 해금 — 정본 6장 조건표를 카운터에 대 본다. 저장하지 않고 계산한다. */
@DisplayName("해금 규칙 — 카운터에서 계산")
class UnlockRulesTest {

    private static final Instant T0 = kst("2026-09-05 12:00");
    private static final MotionCatalog CATALOG = new MotionCatalog("", "", "v1");

    private static ZzalPet baby() {
        ZzalPet pet = ZzalPet.hatch(1L, "여울", null, "k", T0);
        pet.markAlive("s", "i", T0);
        return pet;
    }

    @Test
    @DisplayName("1층 8종은 처음부터, 2층 8종은 잠김, 선물은 기본 행동이 없다")
    void initialState() {
        ZzalPet pet = baby();
        assertThat(UnlockRules.unlockedKeys(pet, CATALOG)).containsExactly(
                "base", "eat", "joy", "sad", "sick", "practice", "shy", "call");
        assertThat(UnlockRules.openedLayerTwo(pet, CATALOG)).isZero();
        assertThat(UnlockRules.isUnlocked(pet, CATALOG.bySeq(101).orElseThrow(), CATALOG)).isFalse();
    }

    @Test
    @DisplayName("목욕 3회 → 씻기(12). 카운터는 부화 순간부터 누적")
    void bathThreeTimesOpensWash() {
        ZzalPet pet = baby();
        pet.bath(T0);
        pet.bath(T0);
        assertThat(UnlockRules.current(pet, UnlockRule.Kind.BATH, CATALOG)).isEqualTo(2);
        assertThat(UnlockRules.isUnlocked(pet, CATALOG.bySeq(12).orElseThrow(), CATALOG)).isFalse();
        pet.bath(T0);
        assertThat(UnlockRules.isUnlocked(pet, CATALOG.bySeq(12).orElseThrow(), CATALOG)).isTrue();
        assertThat(UnlockRules.openedLayerTwo(pet, CATALOG)).isEqualTo(1);
    }

    @Test
    @DisplayName("재우기·깨우기 합쳐 3회 → 자기(11). 낮잠도 센다")
    void sleepWakeCounts() {
        ZzalPet pet = baby();
        pet.sleep(T0.plusSeconds(60));                              // 낮잠 1
        pet.wake(T0.plusSeconds(60 * 6));                           // 2
        assertThat(UnlockRules.isUnlocked(pet, CATALOG.bySeq(11).orElseThrow(), CATALOG)).isFalse();
        pet.settle(kst("2026-09-05 19:00"));
        pet.sleep(kst("2026-09-05 19:00"));                         // 3
        assertThat(UnlockRules.isUnlocked(pet, CATALOG.bySeq(11).orElseThrow(), CATALOG)).isTrue();
    }

    @Test
    @DisplayName("★ '2층 6종 열림'(16)은 자기 자신을 빼고 센다 — 나머지 7종 중 6")
    void sitExcludesItself() {
        ZzalPet pet = baby();
        assertThat(UnlockRules.current(pet, UnlockRule.Kind.LAYER2_OPEN, CATALOG)).isZero();
        assertThat(CATALOG.bySeq(16).orElseThrow().unlockRule().target()).isEqualTo(6);
        assertThat(CATALOG.bySeq(16).orElseThrow().unlockRule().hint()).isEqualTo("다른 동작 6개 배우기");
    }
}
