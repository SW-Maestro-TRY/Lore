package com.lore.zzal.chat.session;

/** 세션이 닫힌 까닭. 열려 있으면 없음(null). */
public enum CloseReason {
    /** 왕복 상한에 닿아 아이가 닫기 턴으로 끝냈다. */
    CLOSED,
    /** 다음 부름 시각(또는 잠)이 와서 조용히 닫혔다 — 패널티 0. */
    EXPIRED,
    /** 사용자가 답하다 말았다(마지막 펫 턴 뒤 {@link TurnPlanner#ABANDON_AFTER}). 닫기 턴은 안 만든다. */
    ABANDONED
}
