package com.lore.zzal.pet;

/**
 * 2층(격자 2장·8종) 그림이 어디까지 왔나(#696 — 1층 우선 부화).
 *
 * ★ 부화 완료(ALIVE)는 1층에서 끝나고, 2층은 부화 뒤 별도 작업({@code GenKind.LAYER2})으로 굽는다.
 *   READY 전까지 2층 8종은 사용자에게 "연습 중" 이다(조건을 채워도 열리지 않는다 — {@code UnlockRules}).
 */
public enum Layer2Status {

    /** 아직 안 구웠다(부화 직후 · 관리자 재시도 직후 · 1층 교체 직후). */
    PENDING,

    /** 굽는 중. */
    RUNNING,

    /** 다 구웠다 — 조건을 채운 동작부터 열린다. */
    READY,

    /** 재시도를 다 썼거나 관리자가 결함으로 표시했다 — 관리자 목록에 오른다. 사용자에게는 여전히 "연습 중". */
    FAILED
}
