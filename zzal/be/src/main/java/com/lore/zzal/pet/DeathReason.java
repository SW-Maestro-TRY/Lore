package com.lore.zzal.pet;

/** 펫이 FAILED·DEAD 가 된 이유. 살아 있으면 비어 있다. */
public enum DeathReason {

    /** 부화 실패 — 생성이 끝내 안 됐다. */
    HATCH_FAILED,

    /** 방치로 죽음 — 아직 규칙 없음. 나중에 쓸 자리. */
    NEGLECTED
}
