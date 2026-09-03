package com.lore.zzal.pet;

import java.time.Duration;

/**
 * 자캐 다마고치의 규칙 — 숫자와 판정만. 스프링도 DB 도 모른다.
 *
 * ★ 여기가 정본이다. 같은 숫자가 프론트 `zzal/fe/tamagotchi/rules.ts` 에도 있지만,
 *   그쪽은 버튼에 "2회분" 같은 미리보기를 그리기 위한 사본이고 판정은 하지 않는다.
 *   브라우저가 보낸 수치를 그대로 믿으면 개발자도구로 게이지를 채울 수 있기 때문이다.
 *
 * 확정 상태(2026-09-03)
 *   ✔ 잠 시간표 — 5분 → 15분 → 1시간 → 3시간, 마지막 값 고정
 *   ⚠️ 나머지 시간 값은 아직 미확정이다. 지금은 `수치설계-3안-0824.md` 의 2026-08-25 확정본을
 *      그대로 옮겨 뒀는데, 현대 다마고치(Paradise 2025)가 배고픔 30분에 1칸인 것과 견주면
 *      우리는 8배 느슨하다. <b>구조는 값과 무관하게 돌아가므로</b> 이 파일의 숫자만 고치면 된다.
 *      바꿀 때는 문서를 먼저 고치고 여기를 맞춘다(반대로 하면 근거가 사라진다).
 */
public final class ZzalRules {

    private ZzalRules() {
    }

    // ── 게이지 ────────────────────────────────────────────────────────────

    /** 게이지 칸 수. 포만감·행복 공통. */
    public static final int MAX_GAUGE = 5;

    /** 바닥에 쌓이는 쓰레기 최대. 5단계면 캐릭터가 거의 안 보인다. */
    public static final int MAX_TRASH = 5;

    /** 밥 재고 상한. */
    public static final int MAX_FOOD = 3;

    /** 갓 깨어난 펫의 포만감. 배고픈 채로 시작해야 첫 밥에 이유가 생긴다. */
    public static final int WAKE_FULLNESS = 1;

    /**
     * 갓 깨어난 펫의 행복.
     *
     * 가득 채우면 쓰다듬을 누를 이유가 사라지고, 그러면 "쓰다듬 먼저, 그다음 훈련" 이라는
     * 이 게임의 유일한 선택이 통째로 없어진다.
     */
    public static final int WAKE_HAPPINESS = 3;

    // ── 시간이 하는 일 ────────────────────────────────────────────────────

    /** 포만감이 1칸 줄어드는 데 걸리는 시간. */
    public static final Duration FULLNESS_DROP = Duration.ofHours(4);

    /** 행복이 1칸 줄어드는 데 걸리는 시간. */
    public static final Duration HAPPINESS_DROP = Duration.ofHours(6);

    /** 쓰레기가 1칸 늘어나는 데 걸리는 시간. */
    public static final Duration TRASH_RISE = Duration.ofHours(8);

    /** 밥이 1개 차는 데 걸리는 시간. */
    public static final Duration FOOD_CHARGE = Duration.ofHours(4);

    // ── 돌봄이 하는 일 ────────────────────────────────────────────────────

    /** 밥 한 번의 포만감 회복량. */
    public static final int FEED_FULLNESS = 1;

    /** 밥 한 번에 늘어나는 쓰레기. 먹으면 치울 것이 생긴다. */
    public static final int FEED_TRASH = 1;

    /** 쓰다듬 한 번의 행복 회복량. */
    public static final int PET_HAPPINESS = 1;

    // ── 훈련과 해금 ───────────────────────────────────────────────────────

    /**
     * n번째 해금에 필요한 훈련 횟수. 1 → 2 → 3 → 4 로 오르고 <b>4에서 고정</b>한다.
     * 계속 올리면 13번째 해금에 13회가 필요해져 사실상 멈춘다.
     */
    private static final int[] TRAIN_PRICE = {1, 2, 3, 4};

    /** 행복이 이 칸 이상이면 훈련 한 번이 2회분. "기분이 좋으면 잘 배운다". */
    public static final int HAPPY_BONUS_AT = 4;

    /** 훈련 한 번이 도는 시간. 도는 동안 다른 돌봄은 계속 된다. */
    public static final Duration TRAIN_DURATION = Duration.ofMinutes(1);

    /**
     * n번째 잠의 길이. 자고 일어나면 하나를 배운다.
     *
     * ★ 첫 두 잠이 짧아야 하는 이유 — 첫날 순서는 해금을 <b>두 번</b> 지나간다. 5분·15분이면
     *   첫 세션에만 20분을 기다리게 되어, 이 게임이 무엇인지 알기 전에 떠난다.
     *   3분·5분이면 8분이라 한자리에서 두 바퀴를 볼 수 있다(2026-09-03 상훈님 확정).
     *
     * ★ 뒤로 갈수록 벌어지는 것은 원조 다마고치의 골격이다(5분 → 1시간 → 하루).
     *   조작을 익히는 구간과 기다리는 구간을 분리한다.
     *
     * ★ <b>마지막 값에서 고정</b>한다. 계속 늘리면 12번째 해금에 며칠이 걸려 사실상 멈춘다
     *   (훈련 가격을 4에서 멈추는 것과 같은 이유).
     *
     * 근거 = `수치설계-3안-0824.md` 2026-08-25 확정본, 2026-09-03 상훈님 재확인.
     */
    private static final Duration[] SLEEP_DURATIONS = {
            Duration.ofMinutes(3),
            Duration.ofMinutes(5),
            Duration.ofHours(1),
            Duration.ofHours(3)
    };

    /** 지금 몇 개를 열었을 때, 다음 잠이 얼마나 긴가. */
    public static Duration sleepDuration(int unlockedCount) {
        int i = Math.min(Math.max(unlockedCount, 0), SLEEP_DURATIONS.length - 1);
        return SLEEP_DURATIONS[i];
    }

    // ★ 모을 수 있는 움직임의 총 수는 여기 없다. 정본은 설정(app.zzal.motions)을 읽는
    //   MotionCatalog.total() 하나다. 예전에는 여기 13이 박혀 있었는데, 목록에 2개만 넣어도
    //   완주가 13개를 요구해 "다 모았다" 가 거짓말을 했다. 같은 사실의 정본은 하나여야 한다.

    /** 지금 몇 개를 열었을 때, 다음 하나에 훈련 몇 번이 드는가. */
    public static int priceOf(int unlockedCount) {
        return TRAIN_PRICE[Math.min(Math.max(unlockedCount, 0), TRAIN_PRICE.length - 1)];
    }

    /** 지금 훈련을 한 번 하면 몇 회분이 쌓이는가. 이 값이 버튼에 미리 보인다. */
    public static int trainGain(int happiness) {
        return happiness >= HAPPY_BONUS_AT ? 2 : 1;
    }

    // ── 부화 ──────────────────────────────────────────────────────────────

    /**
     * 부화에 걸리는 시간(예상).
     *
     * ⚠️ 부화 완료 판정에 쓰지 않는다 — 완료를 정하는 것은 실제 생성이 끝났는지다.
     *    이 값은 화면에 "얼마나 남았나" 를 그리기 위한 것뿐이다.
     *    실측 정상 경로는 2분 10초~20초이고(2026-08-26, 5캐릭터), 리미트를 다 쓰면 10분이다.
     */
    public static final Duration HATCH_ESTIMATE = Duration.ofMinutes(10);
}
