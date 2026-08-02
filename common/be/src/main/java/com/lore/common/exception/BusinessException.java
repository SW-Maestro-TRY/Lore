package com.lore.common.exception;

/**
 * 업무 규칙 위반을 나타내는 예외.
 *
 * 서비스 계층에서 이걸 던지면 GlobalExceptionHandler 가 받아
 * ErrorCode 에 정해진 HTTP 상태와 공통 응답 형태로 바꿔준다.
 * 즉 컨트롤러마다 try-catch 를 쓰지 않는다.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
