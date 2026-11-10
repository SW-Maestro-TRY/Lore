package com.lore.zzal.chat.line;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

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
    Completion complete(String system, String user, String model, Duration timeout) throws Exception;

    /** 이전 어댑터·목은 2메시지 계약으로 동작하며, HTTP 어댑터는 native 이력을 지원한다. */
    default Completion complete(String system, List<Message> history, String user, String model, Duration timeout)
            throws Exception {
        StringBuilder compatible = new StringBuilder();
        if (!history.isEmpty()) {
            compatible.append("[지금까지]\n");
            for (Message message : history) {
                compatible.append(message.role().equals("assistant") ? "너: " : "상대: ")
                        .append(message.content()).append('\n');
            }
            compatible.append('\n');
        }
        return complete(system, compatible.append(user).toString(), model, timeout);
    }

    record Message(String role, String content) {
        public Message {
            if (!"user".equals(role) && !"assistant".equals(role)) {
                throw new IllegalArgumentException("History role must be user or assistant");
            }
            java.util.Objects.requireNonNull(content);
        }
    }

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
