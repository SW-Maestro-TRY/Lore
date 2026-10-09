package com.lore.zzal.chat.memory;

import com.lore.zzal.chat.ChatSlot;

import java.time.Instant;

/**
 * 기억 하나 — 문장 하나 + 꼬리표.
 *
 * <h3>v1.5(#709) — 지난 대화 그대로</h3>
 * 기억은 "최근 3일 동안 판에서 오간 말" 그대로다. {@code kind} 는 누가 한 말인지({@link #PET_LINE}·{@link #USER_LINE}),
 * {@code sessionId}·{@code slot} 은 어느 판의 말인지(지시문이 판마다 날짜 줄을 붙인다).
 * v2(종류 10가지·등급·확인 횟수·활성·마지막 사용일)는 이 record 에 칸을 더해 받는다.
 *
 * @param text      한 말 그대로
 * @param kind      {@link #PET_LINE} · {@link #USER_LINE}
 * @param at        한 시각(펫 시계)
 * @param sessionId 판(옛 부름이면 null)
 * @param slot      판의 부름. 모르면 null
 */
public record Memory(String text, String kind, Instant at, Long sessionId, ChatSlot slot) {

    /** 펫(너)이 한 말. */
    public static final String PET_LINE = "pet_line";

    /** 사용자(상대)가 한 말. */
    public static final String USER_LINE = "user_line";

    public boolean byUser() {
        return USER_LINE.equals(kind);
    }
}
