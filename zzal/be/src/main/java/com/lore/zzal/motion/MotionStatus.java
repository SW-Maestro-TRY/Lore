package com.lore.zzal.motion;

/**
 * 동작 한 행(기본 행동 + 심화 행동)이 지금 어디까지 왔나 — 심화 행동(16프레임) 쪽 상태(플랜 T1 핵심 판단 3).
 *
 * ★ "구웠는가" 와 "보여줘도 되는가" 는 다른 질문이다. 다 구웠는데 검수 대기인 것(REVIEW), 맥미니가 다시 굽는
 *   것(LOCAL_REQUESTED)이 따로 있어야 한다. 기본 행동이 열렸는지는 이 상태와 무관하다(UnlockRules).
 */
public enum MotionStatus {

    /** 심화 행동을 아직 안 굽는다. 부화 때 18행이 이 상태로 생긴다. */
    NONE,

    /** 밤 큐에 올랐다(nightOf). 스위프가 집어 간다. */
    QUEUED,

    /** 서버(API)가 굽는 중. */
    BAKING,

    /** 다 구워져 검수 대기. 사용자에게 아직 안 보인다. */
    REVIEW,

    /** 검수에서 반려 → 맥미니(codex) 재생성 요청. */
    LOCAL_REQUESTED,

    /** 공개. 아침 첫 정산에 "배워왔어요". */
    OPEN,

    /** 그 밤 실패. 조각은 소모하지 않고 다음 밤에 다시. */
    FAILED,

    /** @deprecated v1(재우기 때 굽던 시절). 옛 행에만 남아 있다. 새 코드는 안 쓴다. */
    @Deprecated
    PENDING
}
