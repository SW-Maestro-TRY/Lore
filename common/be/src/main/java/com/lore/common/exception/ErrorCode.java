package com.lore.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 서비스 전체가 공유하는 에러 코드.
 *
 * 프론트는 사람이 읽는 문구(message)가 아니라 이 코드(name)로 분기한다.
 * 문구는 나중에 바뀌지만 코드는 안 바뀌기 때문이다.
 *
 * 도메인이 늘면 접두어로 구분한다. (COMMON_* / COMIC_* / WEBTOON_* / TRAILER_*)
 */
public enum ErrorCode {

    // 공통
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다");

    // 도메인별 코드는 각 담당자가 아래에 추가한다.
    // 예) COMIC_NOT_FOUND(HttpStatus.NOT_FOUND, "만화를 찾을 수 없습니다"),
    //     WEBTOON_NOT_FOUND(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다"),

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
