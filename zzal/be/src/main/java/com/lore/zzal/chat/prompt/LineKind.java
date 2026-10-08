package com.lore.zzal.chat.prompt;

/** 대사가 필요한 자리 세 가지. */
public enum LineKind {
    /** 부름 — 아이가 먼저 거는 한 줄. */
    CALL,
    /** 답 — 사용자가 부름에 답한 말에 대한 대사. */
    REPLY,
    /** 재언급 — 답에 대한 대사이면서 전에 들은 말 하나를 꺼낸다. */
    RECALL;

    /** 분석 이벤트·로그에 쓰는 이름. */
    public String code() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
