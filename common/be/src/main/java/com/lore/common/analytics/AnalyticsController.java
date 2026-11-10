package com.lore.common.analytics;

import com.lore.common.analytics.dto.EventRequests;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Nonblocking client telemetry. A 202 receipt acknowledges committed rows, never an attempted write. */
@Tag(name = "행동 기록", description = "사용자 행동 수집 (팀 공용, 로그인 불필요)")
@RestController
@RequestMapping("/api/v1/events")
public class AnalyticsController {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsController.class);


    private final AnalyticsService analyticsService;
    private final AnonIdResolver anonIdResolver;

    public AnalyticsController(AnalyticsService analyticsService, AnonIdResolver anonIdResolver) {
        this.analyticsService = analyticsService;
        this.anonIdResolver = anonIdResolver;
    }

    @Operation(summary = "행동 기록 수집", description = """
            화면에서 모은 이벤트를 묶음으로 받는다. 로그인 없이도 받는다.

            ★ 익명 번호는 본문으로 받지 않는다. 쿠키(lore_anon_id)만 신뢰하고,
              쿠키가 없으면 이 응답에서 새로 발급한다(별도 발급 API 를 두면 왕복이 한 번 늘어
              첫 이벤트를 놓친다).

            ★ props 는 허용된 키만 저장된다. 나머지는 조용히 버려진다.
              referrer 의 쿼리스트링, User-Agent 원문, IP 는 저장하지 않는다.

            202는 저장된 이벤트와 중복 이벤트의 ID를 확인한다. 저장 실패/비활성은 503, 요청 제한은 429다.""")
    @PostMapping
    public ResponseEntity<ApiResponse<AnalyticsService.Receipt>> collect(@Valid @RequestBody EventRequests.Batch batch,
                                                     @RequestHeader(value = "User-Agent", required = false) String userAgent,
                                                     HttpServletRequest request,
                                                     HttpServletResponse response) {
        // 꺼져 있으면 쿠키도 발급하지 않는다. "안 모은다" 는 흔적조차 안 남긴다는 뜻이어야 한다.
        if (!analyticsService.isEnabled()) {
            return ResponseEntity.status(503).body(ApiResponse.ok(AnalyticsService.Receipt.unavailable("disabled")));
        }

        // ★ 본문 크기는 여기서 재지 않는다 — @RequestBody 가 이미 다 읽은 뒤라 늦다.
        //   RequestSizeLimitFilter 가 읽기 전에 막는다.
        if (batch.events().size() > analyticsService.getMaxBatch()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "한 번에 보낼 수 있는 이벤트는 %d건까지입니다".formatted(analyticsService.getMaxBatch()));
        }

        String anonId = anonIdResolver.resolve(request, response);
        // 화면이 읽을 수 있는 번호 사본 — 쿠키가 지워진 뒤 옛 번호를 잇는 데 쓴다(AnonIdResolver#HINT_COOKIE).
        anonIdResolver.syncHint(request, response, anonId);
        Long userId = currentUserId();

        AnalyticsService.Receipt receipt;
        try {
            receipt = analyticsService.collectAcknowledged(batch, anonId, userId, userAgent);
        } catch (RuntimeException ex) {
            log.warn("행동 기록 저장 실패 — 재전송 가능, batchSize={}", batch.events().size(), ex);
            return ResponseEntity.status(503).body(ApiResponse.ok(AnalyticsService.Receipt.unavailable("storage_unavailable")));
        }
        if (!receipt.status().equals("persisted")) {
            return ResponseEntity.status(receipt.status().equals("rate_limited") ? 429 : 503)
                    .header("Retry-After", "60").body(ApiResponse.ok(receipt));
        }
        // Identity linking is best effort and cannot turn a committed receipt into a failed write.
        try {
            analyticsService.linkIdentity(anonId, userId);
            if (userId != null) {
                for (String previous : AnalyticsService.previousAnonIds(batch, anonId)) {
                    analyticsService.linkIdentity(previous, userId);
                }
            }
        } catch (RuntimeException ex) {
            log.warn("행동 기록 신원 연결 실패 — 이벤트는 저장됨", ex);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(receipt));
    }

    /**
     * 로그인 상태면 사용자 번호, 아니면 null.
     *
     * ★ {@code @LoginUser} 를 쓰지 않는 이유 — 그 어노테이션은 비로그인이면 예외를 던진다
     *   (경로 규칙에서 이미 걸러졌어야 한다는 전제). 이 주소는 <b>비로그인이 정상</b>인
     *   유일한 쓰기 API 라 전제가 반대다. 그래서 여기서 직접 꺼낸다.
     * ★ 그래도 값의 출처는 같다 — JwtAuthenticationFilter 가 access 쿠키를 검증해 세워 둔 것이라
     *   본문이나 헤더로 사용자 번호를 사칭할 수 없다.
     */
    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.getPrincipal() instanceof Long userId) ? userId : null;
    }
}
