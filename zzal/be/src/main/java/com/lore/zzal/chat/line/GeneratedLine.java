package com.lore.zzal.chat.line;

import java.math.BigDecimal;

/**
 * 사슬이 내놓은 최종 대사와 그 출처.
 *
 * @param text           대사(반드시 있다)
 * @param motion         반응 동작(부름이면 null)
 * @param generator      실제로 대사를 낸 생성기 — template · llm
 * @param model          LLM 을 불렀으면 그 모델(폴백이어도 남긴다)
 * @param costUsd        이번에 나간 돈
 * @param fallbackReason LLM 이 실패해 템플릿으로 떨어졌으면 그 사유, 아니면 null
 */
public record GeneratedLine(String text, String motion, String generator, String model, BigDecimal costUsd,
                            String fallbackReason) {
}
