package com.lore.common.response;

/**
 * 모든 도메인이 공유하는 공통 응답 구조 (뼈대).
 *
 * story / comic / trailer 가 각자 다른 모양으로 응답하면 프론트에서 도메인마다
 * 파싱을 다르게 해야 하므로, 응답 껍데기는 여기 한 곳에서만 정의한다.
 *
 * 실제 필드 구성(에러 코드 체계, 페이징 등)은 백엔드 담당자가 확정.
 */
public class ApiResponse<T> {

    private final boolean success;
    private final T data;
    private final String message;

    private ApiResponse(boolean success, T data, String message) {
        this.success = success;
        this.data = data;
        this.message = message;
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> fail(String message) {
        return new ApiResponse<>(false, null, message);
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
}
