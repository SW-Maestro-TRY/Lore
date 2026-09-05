package com.lore.zzal.pet;

/**
 * 펫이 지금 어느 단계인가.
 *
 * ★ 프론트의 Phase 와 한 칸이 다르다 — 프론트에는 'none'(아직 아무도 없음)이 있지만
 *   서버에서는 그 상태가 곧 "행이 없음" 이므로 값으로 두지 않는다.
 */
public enum PetPhase {

    /** 알. 그림은 받았고 생성이 도는 중. */
    HATCHING,

    /** 함께 지내는 중. 수치가 흐르는 유일한 단계. */
    ALIVE,

    /**
     * 태어나지 못했다. 생성이 끝내 실패한 경우.
     * 왜 실패했는지는 deathReason 과 gen_job 의 errorCode 에 남는다.
     */
    FAILED,

    /**
     * 살다가 죽었다. 아직 그런 규칙은 없지만(방치 시 죽음 등) 나중에 생길 자리를 미리 갈라 둔다.
     * FAILED 와 합쳐 두면 그 규칙이 생긴 뒤 옛 데이터가 어느 쪽이었는지 알 수 없게 된다.
     */
    DEAD;

    /**
     * <b>자리(슬롯)를 차지하는 단계</b>. 새로 만들 수 있는지 셀 때 이 목록만 센다.
     *
     * ★ 목록을 여기 둔 이유 — 단계가 하나 늘 때 "자리를 먹는가" 를 같이 정하게 만들기 위해서다.
     *   호출하는 쪽마다 "FAILED 는 빼고" 같은 조건을 따로 적으면, 새 단계가 생겼을 때 한 곳만
     *   고쳐지고 나머지는 조용히 틀린 채로 남는다.
     *
     * DEAD 는 들어가지 않는다 — 주인이 보낸(RELEASED) 아이의 행은 남기되 자리는 비워 줘야
     * 다른 그림으로 새로 시작할 수 있다. FAILED 도 태어나지 못했으니 자리를 먹지 않는다.
     */
    public static final java.util.List<PetPhase> OCCUPYING_SLOT = java.util.List.of(HATCHING, ALIVE);
}
