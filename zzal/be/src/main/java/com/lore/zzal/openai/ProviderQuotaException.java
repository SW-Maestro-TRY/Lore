package com.lore.zzal.openai;

import java.time.Duration;

/**
 * OpenAI 가 한도로 막았다 — 429, 또는 본문에 잔액·한도가 적힌 응답(설계 2-2).
 * 창구는 그 줄을 {@link #retryAfter()} 동안 막고, 막힌 동안의 새 요청도 보내지 않고 이 예외로 돌려준다.
 *
 * ★ 돈은 나가지 않았다(OpenAI 는 실패 요청에 과금하지 않는다).
 * ★ 같은 키를 쓰므로 곧바로 다시 보내면 또 막힌다 — 부르는 쪽은 다시 부르지 않는다.
 */
public class ProviderQuotaException extends Exception {
    private final Duration retryAfter;
    private final int status;

    public ProviderQuotaException(int status, Duration retryAfter, String detail) {
        super("OpenAI 한도(HTTP %d, %d초 뒤 다시): %s".formatted(status, retryAfter.toSeconds(), detail));
        this.status = status;
        this.retryAfter = retryAfter;
    }

    /** 이 줄이 다시 열리기까지. 0 이면 상태 코드 없이 줄 차단에 걸린 것(보내지 않음). */
    public Duration retryAfter() {
        return retryAfter;
    }

    /** OpenAI 가 준 상태 코드. 줄 차단에 걸려 보내지 않았으면 0. */
    public int status() {
        return status;
    }
}
