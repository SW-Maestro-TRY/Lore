package com.lore.zzal.game;

/**
 * 무엇을 줄 것인가.
 *
 * ★ 후기와 미니게임이 <b>같은 자리</b>를 쓴다. 둘 다 "무언가 하면 뭘 준다" 는 같은 문제라
 *   각자 지급 로직을 만들면 나중에 두 곳을 고쳐야 한다.
 *
 * 미니게임 승리 = HAPPINESS(정본 7장 확정). 후기는 아직 NONE(기록만 남는다).
 * 설정 {@code app.zzal.reward.*} 이 정한다. TRAIN 은 훈련과 함께 사라졌다(정본 6장 "훈련 행동 없음").
 */
public enum RewardKind {

    /** 아무것도 주지 않는다. */
    NONE,

    /** 밥 하나. 4시간 기다리는 것을 건너뛴다. */
    FOOD,

    /** 행복 한 칸. */
    HAPPINESS
}
