package com.lore.common.exception;

import com.lore.common.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 어느 컨트롤러에서 예외가 나든 여기로 모여 공통 응답 형태로 바뀐다.
 *
 * 이게 없으면 Spring 기본 에러 화면(timestamp/status/path 형식)이 나가서
 * 프론트가 성공일 때와 실패일 때 서로 다른 모양을 파싱해야 한다.
 *
 * 새 파일 추가이므로 기존 동작을 바꾸지 않는다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 업무 규칙 위반 — 우리가 의도적으로 던진 예외 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        ErrorCode code = e.getErrorCode();
        log.warn("[{}] {}", code.name(), e.getMessage());
        return ResponseEntity.status(code.getStatus())
                .body(ApiResponse.fail(code, e.getMessage()));
    }

    /** @Valid 검증 실패 — 요청 본문이 규칙에 안 맞을 때 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .findFirst()
                .orElse(ErrorCode.INVALID_INPUT.getDefaultMessage());
        return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
                .body(ApiResponse.fail(ErrorCode.INVALID_INPUT, message));
    }

    /**
     * 본문을 못 읽은 경우 — 깨진 JSON, enum 에 없는 값, 타입이 안 맞는 값.
     *
     * ★ 안 잡으면 아래 "그 밖의 모든 예외" 로 떨어져 <b>500 + "서버 오류가 발생했습니다"</b> 가 된다.
     *   보낸 쪽이 잘못한 것인데 서버가 터진 것처럼 보이고, 화면은 둘을 구분할 수 없다.
     *   {@code {"action":"DANCE"}} 처럼 없는 값을 보내면 실제로 그랬다(2026-09-04 발견).
     *
     * ★ 원인을 그대로 내보내지 않는다 — 예외 메시지에 클래스 이름·패키지·필드 구조가 들어 있어
     *   그대로 주면 서버 내부가 드러난다. 자세한 것은 로그에만 남긴다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        log.debug("본문을 읽지 못함", e);
        return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
                .body(ApiResponse.fail(ErrorCode.INVALID_INPUT));
    }

    /**
     * 없는 주소를 부른 경우.
     *
     * ★ 안 잡으면 아래 "그 밖의 모든 예외" 로 떨어져 <b>500 + "서버 오류가 발생했습니다"</b> 가 된다.
     *   그러면 화면은 주소를 잘못 부른 것과 서버가 실제로 터진 것을 구분할 수 없고,
     *   로그에도 ERROR 가 쌓여 진짜 오류가 그 사이에 묻힌다.
     *   설정으로 꺼 둔 API(개발용 도구 등)를 불렀을 때도 이 길로 온다.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NoResourceFoundException e) {
        log.debug("없는 주소 — {}", e.getResourcePath());
        return ResponseEntity.status(ErrorCode.NOT_FOUND.getStatus())
                .body(ApiResponse.fail(ErrorCode.NOT_FOUND));
    }

    /** 그 밖의 모든 예외 — 상세 원인은 로그에만 남기고 밖으로는 내보내지 않는다 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("처리되지 않은 예외", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getStatus())
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR));
    }
}
