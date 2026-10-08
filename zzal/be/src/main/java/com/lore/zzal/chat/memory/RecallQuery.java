package com.lore.zzal.chat.memory;

import com.lore.zzal.chat.ChatSlot;

import java.time.Instant;

/**
 * 기억을 꺼낼 때 넘기는 상황. v1 은 아무것도 안 보고 최근 답을 그대로 준다.
 *
 * ★ v2 의 꺼내기 규칙(시간대·쿨다운·"한 대화에 최대 1개")은 이 값만 보고 정할 수 있게 칸을 둔다 —
 *   점심 시간대에 음식 취향, 일정은 전날·끝난 뒤 같은 판정이 {@code now}·{@code slot} 으로 된다.
 *
 * @param now      펫 시계의 지금
 * @param slot     이번 부름
 * @param userText 이번에 사용자가 한 말(부름을 만들 때는 null)
 */
public record RecallQuery(Instant now, ChatSlot slot, String userText) {
}
