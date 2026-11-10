package com.lore.zzal.openai;

/**
 * OpenAI 쪽 고장 — 5xx 또는 연결 실패(설계 2-2). 같은 입력으로 다시 하면 대개 된다.
 * 줄은 막지 않는다. 다시 할지는 부르는 쪽이 정한다(창구는 자동으로 다시 보내지 않는다).
 */
public class ProviderServerException extends Exception {
    private final int status;

    public ProviderServerException(int status, String detail, Throwable cause) {
        super(status > 0 ? "OpenAI 서버 오류(HTTP %d): %s".formatted(status, detail)
                : "OpenAI 연결 실패: " + detail, cause);
        this.status = status;
    }

    /** 5xx 상태 코드. 연결 실패면 0. */
    public int status() {
        return status;
    }
}
