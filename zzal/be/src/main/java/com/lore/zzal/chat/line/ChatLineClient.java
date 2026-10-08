package com.lore.zzal.chat.line;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * 대사용 텍스트 호출 한 번. 시험에서는 목으로 갈아끼운다.
 *
 * ★ 그림 쪽 {@code TextClient} 를 안 쓰는 이유 — 그쪽은 gpt-5 단가가 박혀 있고 시간 제한이 120초,
 *   JSON 응답 형식·추론 강도 설정이 없다. 대사는 4초 안에 끝나야 하고 모델마다 단가가 다르다.
 */
public interface ChatLineClient {

    /**
     * @throws java.util.concurrent.TimeoutException 시간 안에 못 받았을 때. 돈이 나갔는지는 모른다
     * @throws Exception 그 밖의 실패. 비용을 알면 {@link BilledException} 으로 감싼다
     */
    Completion complete(String prompt, String model, Duration timeout) throws Exception;

    /** @param text 모델이 낸 글 그대로(JSON 문자열이어야 한다) */
    record Completion(String text, BigDecimal costUsd, long inputTokens, long outputTokens) {
    }

    /** 응답은 받았지만(=돈은 나갔지만) 쓸 수 없을 때. */
    class BilledException extends Exception {
        private final BigDecimal costUsd;

        public BilledException(String message, BigDecimal costUsd) {
            super(message);
            this.costUsd = costUsd;
        }

        public BigDecimal costUsd() {
            return costUsd;
        }
    }
}
