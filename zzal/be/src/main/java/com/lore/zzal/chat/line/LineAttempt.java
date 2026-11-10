package com.lore.zzal.chat.line;

import java.math.BigDecimal;

/**
 * 생성기 한 번의 결과. 실패해도 돈이 나갔을 수 있어 비용은 늘 채운다.
 *
 * @param text       대사. 실패면 null
 * @param motion     반응 동작 키(부름이거나 못 골랐으면 null)
 * @param generator  생성기 이름 — llm
 * @param model      부른 모델
 * @param costUsd    나간 돈(0 이상)
 * @param failReason 실패 사유(성공이면 null) — timeout · error · parse · blank
 * @param millis     걸린 시간
 * @param extract    대사와 함께 받은 추출 칸(실패면 {@link LineExtract#NONE})
 */
public record LineAttempt(String text, String motion, String generator, String model, BigDecimal costUsd,
                          String failReason, long millis, LineExtract extract) {

    public boolean ok() {
        return failReason == null && text != null;
    }

    public static LineAttempt fail(String generator, String model, BigDecimal cost, String reason, long millis) {
        return new LineAttempt(null, null, generator, model, cost == null ? BigDecimal.ZERO : cost, reason, millis,
                LineExtract.NONE);
    }
}
