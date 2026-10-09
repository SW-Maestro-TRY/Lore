package com.lore.zzal.chat.session;

/** 펫 턴의 종류 — 지시문 [이번 턴] 의 "종류" 칸. 코드가 정한다(LLM 이 고르지 않는다). */
public enum TurnType {
    FIRST_MEET("첫 만남"),
    GREETING("오늘 첫 인사"),
    REUNION("오랜만"),
    CONTINUE("이어 말하기"),
    CLOSE("닫기");

    private final String label;

    TurnType(String label) {
        this.label = label;
    }

    /** 지시문에 들어가는 이름. */
    public String label() {
        return label;
    }

    /** 대화를 여는 턴인가(폴백 문형의 "첫 만남" 묶음). */
    public boolean opening() {
        return this == FIRST_MEET || this == GREETING || this == REUNION;
    }
}
