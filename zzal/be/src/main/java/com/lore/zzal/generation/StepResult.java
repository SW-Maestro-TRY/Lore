package com.lore.zzal.generation;

import java.math.BigDecimal;

/**
 * 단계 하나가 만들어 낸 것.
 *
 * @param name      산출물 이름. 뒤 단계가 이 이름으로 꺼내 쓴다
 * @param imageKey  이미지면 S3 키, 아니면 null
 * @param text      글이면 내용, 아니면 null
 * @param costUsd   이 단계에 든 돈. 우리 계산으로 끝나는 단계는 0
 */
public record StepResult(String name, String imageKey, String text, BigDecimal costUsd) {

    public static StepResult image(String name, String imageKey, BigDecimal costUsd) {
        return new StepResult(name, imageKey, null, costUsd);
    }

    public static StepResult text(String name, String text, BigDecimal costUsd) {
        return new StepResult(name, null, text, costUsd);
    }

    /** 돈이 안 드는 단계(후처리 등). */
    public static StepResult free(String name) {
        return new StepResult(name, null, null, BigDecimal.ZERO);
    }
}
