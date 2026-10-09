package com.lore.zzal.chat.line;

import java.math.BigDecimal;

/**
 * 사슬이 내놓은 최종 대사와 그 출처.
 *
 * @param text       대사(반드시 있다 — 실패면 중립 닫는 말)
 * @param motion     반응 동작(부름이면 null)
 * @param generator  실제로 대사를 낸 곳 — llm · fixed(중립 닫는 말)
 * @param model      부른 모델
 * @param costUsd    이번에 나간 돈(재호출까지 합)
 * @param failReason 실패가 있었으면 그 사유(재호출로 살렸어도 첫 사유를 남긴다. 둘 다 실패면 "첫/둘째"), 없으면 null
 * @param outcome    어떻게 나왔나
 * @param latencyMs  걸린 시간(재호출까지 합)
 * @param attempts   부른 횟수(1 또는 2)
 * @param extract    추출 칸(실패면 {@link LineExtract#NONE})
 */
public record GeneratedLine(String text, String motion, String generator, String model, BigDecimal costUsd,
                            String failReason, LineOutcome outcome, long latencyMs, int attempts, LineExtract extract) {

    /** 이 줄로 판을 닫아야 하나(두 번 다 실패). */
    public boolean closesSession() {
        return outcome == LineOutcome.FAILED_CLOSED;
    }
}
