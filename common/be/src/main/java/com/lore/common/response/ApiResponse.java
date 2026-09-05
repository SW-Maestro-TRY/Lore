package com.lore.common.response;

import com.lore.common.exception.ErrorCode;

/**
 * 모든 도메인이 공유하는 공통 응답 구조.
 *
 * webtoon / zzal / trailer 가 각자 다른 모양으로 응답하면 프론트에서 도메인마다
 * 파싱을 다르게 해야 하므로, 응답 껍데기는 여기 한 곳에서만 정의한다.
 *
 * 성공: { "success": true,  "data": {...}, "message": null, "error": null }
 * 실패: { "success": false, "data": null,  "message": "입력값이 올바르지 않습니다",
 *         "error": { "code": "INVALID_INPUT", "message": "입력값이 올바르지 않습니다" } }
 *
 * ★ error.code 를 추가한 이유
 *   프론트는 문구가 아니라 코드로 분기해야 나중에 문구를 바꿔도 화면이 안 깨진다.
 *   기존 success / data / message 세 필드는 그대로 두었으므로 먼저 붙인 코드도 계속 동작한다.
 */
public class ApiResponse<T> {

    private final boolean success;
    private final T data;
    private final String message;
    private final ErrorBody error;

    private ApiResponse(boolean success, T data, String message, ErrorBody error) {
        this.success = success;
        this.data = data;
        this.message = message;
        this.error = error;
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, null);
    }

    /** 본문 없이 성공만 알릴 때 (예: 삭제) */
    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, null, null, null);
    }

    /** 문구만 있는 실패 응답 (하은님이 만든 원래 형태 유지) */
    public static <T> ApiResponse<T> fail(String message) {
        return new ApiResponse<>(false, null, message, new ErrorBody(null, message));
    }

    /** 코드가 있는 실패 응답 — 이쪽을 기본으로 쓴다 */
    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(false, null, message, new ErrorBody(errorCode.name(), message));
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode) {
        return fail(errorCode, errorCode.getDefaultMessage());
    }

    public boolean isSuccess() {
        return success;
    }

    public T getData() {
        return data;
    }

    public String getMessage() {
        return message;
    }

    public ErrorBody getError() {
        return error;
    }

    /** 실패 상세. code 는 ErrorCode 의 이름(예: INVALID_INPUT). */
    public record ErrorBody(String code, String message) {
    }
}
