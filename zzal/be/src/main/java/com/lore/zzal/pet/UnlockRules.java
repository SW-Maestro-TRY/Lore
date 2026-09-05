package com.lore.zzal.pet;

import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.MotionLayer;
import com.lore.zzal.motion.MotionSpec;
import com.lore.zzal.motion.UnlockRule;

import java.util.List;

/**
 * 기본 행동(2프레임)이 열렸나 — 정본 6장 2층 조건표를 펫의 누적 카운터에 대 본다. 순수 자바.
 *
 * <h3>★ 저장하지 않고 계산한다</h3>
 * "열렸다" 를 표에 적어 두면 카운터와 표가 어긋날 수 있다(카운터는 9인데 표는 잠김). 조건은 전부
 * 부화 순간부터 누적되는 카운터의 함수이므로 매번 계산해도 같은 답이고, 정본 표가 바뀌면
 * {@link MotionCatalog} 한 곳만 고치면 된다. "이번 행동으로 새로 열렸다"(폭죽)는 행동 전후를 비교해 얻는다.
 *
 * <h3>16번 "2층 6종 열림" 은 자기 자신을 뺀다(16장)</h3>
 * 그래서 재귀가 아니라 "나머지 2층 7종 중 몇 개" 로 센다.
 */
public final class UnlockRules {

    private UnlockRules() {
    }

    /** 이 조건 종류의 현재 카운터 값. */
    public static int current(ZzalPet pet, UnlockRule.Kind kind, MotionCatalog catalog) {
        return switch (kind) {
            case ALWAYS -> 0;
            case CHAT_ANSWERS -> pet.getChatAnswers();
            case SLEEP_WAKE -> pet.getSleepWakeCount();
            case BATH -> pet.getBathCount();
            case GAME_STARTS -> pet.getGameStarts();
            case ZERO_MISS_DAYS -> pet.getZeroMissDays();
            case LAYER2_OPEN -> openedLayerTwoExcluding(pet, catalog, UnlockRule.Kind.LAYER2_OPEN);
            case FIRST_GIFT, SECOND_GIFT -> 0;
        };
    }

    /** 기본 행동이 열렸나. 선물은 기본 행동이 없으니 항상 false(심화만 있다). */
    public static boolean isUnlocked(ZzalPet pet, MotionSpec spec, MotionCatalog catalog) {
        if (spec.isGift()) {
            return false;
        }
        UnlockRule rule = spec.unlockRule();
        if (rule.kind() == UnlockRule.Kind.ALWAYS) {
            return true;
        }
        return current(pet, rule.kind(), catalog) >= rule.target();
    }

    /** 열린 2층 동작 수. 기능 해금(배경 = 4종)에 쓴다. */
    public static int openedLayerTwo(ZzalPet pet, MotionCatalog catalog) {
        return (int) catalog.basic().stream()
                .filter(m -> m.layer() == MotionLayer.BASIC_2)
                .filter(m -> isUnlocked(pet, m, catalog))
                .count();
    }

    /** 열린 기본 행동 key 목록(공유·다운로드 대상). */
    public static List<String> unlockedKeys(ZzalPet pet, MotionCatalog catalog) {
        return catalog.basic().stream()
                .filter(m -> isUnlocked(pet, m, catalog))
                .map(MotionSpec::key)
                .toList();
    }

    /** 그 종류(LAYER2_OPEN)를 조건으로 가진 동작 자신을 빼고 센다. */
    private static int openedLayerTwoExcluding(ZzalPet pet, MotionCatalog catalog, UnlockRule.Kind self) {
        return (int) catalog.basic().stream()
                .filter(m -> m.layer() == MotionLayer.BASIC_2)
                .filter(m -> m.unlockRule().kind() != self)
                .filter(m -> isUnlocked(pet, m, catalog))
                .count();
    }
}
