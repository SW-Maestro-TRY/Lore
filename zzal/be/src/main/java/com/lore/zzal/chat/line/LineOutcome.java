package com.lore.zzal.chat.line;

/**
 * 대사 한 줄이 어떻게 나왔나 — 턴 행 {@code outcome} 칸과 이벤트 {@code zzal_chat_llm} 의 {@code reason} 에 남는다(#709).
 *
 * <ul>
 *   <li>{@link #OK} — 첫 호출에 받았다</li>
 *   <li>{@link #RETRIED_OK} — 첫 호출이 실패(빈 줄·JSON 깨짐·시간 초과·오류)해 한 번 더 불러 받았다</li>
 *   <li>{@link #FAILED_CLOSED} — 두 번 다 실패해 중립 닫는 말({@link LineChain#CLOSING_LINE})로 판을 닫았다</li>
 *   <li>{@link #TRUNCATED} — 받았지만 DB 칸(160자)을 넘어 잘라 저장했다(재호출 여부와 무관하게 이것이 앞선다)</li>
 * </ul>
 */
public enum LineOutcome {
    OK("ok"), RETRIED_OK("retried_ok"), FAILED_CLOSED("failed_closed"), TRUNCATED("truncated");

    private final String code;

    LineOutcome(String code) {
        this.code = code;
    }

    /** DB·이벤트에 남는 값. */
    public String code() {
        return code;
    }
}
