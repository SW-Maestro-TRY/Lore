package com.lore.zzal.pet;

import java.time.Duration;

/**
 * 자캐 다마고치의 규칙 — 숫자와 판정만. 스프링도 DB 도 모른다.
 *
 * ★ 여기가 정본이다. 같은 숫자가 프론트 `zzal/fe/tamagotchi/rules.ts` 에도 있지만,
 *   그쪽은 버튼에 "2회분" 같은 미리보기를 그리기 위한 사본이고 판정은 하지 않는다.
 *   브라우저가 보낸 수치를 그대로 믿으면 개발자도구로 게이지를 채울 수 있기 때문이다.
 *
 * 근거 = `~/.claude/soma/lore/수치설계-3안-0824.md` 의 2026-08-25 확정본.
 * 숫자를 바꿔야 할 때는 그 문서를 먼저 고치고 여기를 맞춘다(반대로 하면 근거가 사라진다).
 *
 * ⚠️ 이번 브랜치(#36)에서는 부화만 다루므로 아래 값 중 초기값만 쓰인다.
 *    돌보기·훈련·해금 판정은 #133 에서 이 파일에 채운다.
 */
public final class ZzalRules {

    private ZzalRules() {
    }

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

    /**
     * 부화에 걸리는 시간(예상).
     *
     * ⚠️ 부화 완료 판정에 쓰지 않는다 — 완료를 정하는 것은 실제 생성이 끝났는지다.
     *    이 값은 화면에 "얼마나 남았나" 를 그리기 위한 것뿐이다.
     *    실측 정상 경로는 2분 10초~20초이고(2026-08-26, 5캐릭터), 리미트를 다 쓰면 10분이다.
     */
    public static final Duration HATCH_ESTIMATE = Duration.ofMinutes(10);
}
