package com.lore.zzal.chat.memory;

import java.time.Instant;

/**
 * 기억 하나 — 문장 하나 + 꼬리표.
 *
 * <h3>★ v1 은 꼬리표가 하나뿐이다</h3>
 * v1 의 기억은 "사용자가 부름에 한 답" 그대로라 {@code kind} 는 늘 {@link #RECENT_ANSWER} 다.
 * v2(종류 10가지·등급·확인 횟수·활성·마지막 사용일)는 이 record 에 칸을 더해 받는다 —
 * 지시문 조립({@code PromptAssembler})은 {@code text} 와 {@code kind} 만 읽으므로 칸이 늘어도 안 깨진다.
 *
 * @param text 기억 문장(사용자가 한 말 그대로)
 * @param kind 종류. v1 은 {@link #RECENT_ANSWER}
 * @param at   생긴 시각. 모르면 null
 */
public record Memory(String text, String kind, Instant at) {

    /** v1 — 부름에 한 답 그대로. */
    public static final String RECENT_ANSWER = "recent_answer";

    public static Memory recentAnswer(String text, Instant at) {
        return new Memory(text, RECENT_ANSWER, at);
    }
}
