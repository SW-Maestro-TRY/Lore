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

/**
 * 사용자 행동 기록을 받는 곳 (팀 공용).
 *
 * <h3>왜 로그인 없이 열려 있나</h3>
 * 가장 알고 싶은 것이 <b>"가입하지 않고 나간 사람이 어디서 멈췄나"</b> 이기 때문이다.
 * 로그인 뒤에만 받으면 그 답을 영영 못 얻는다. 대신 누구나 부를 수 있는 주소가 되므로
 * 상한(개수·본문 크기·분당 요청)과 허용 키 검사를 {@link AnalyticsService} 가 반드시 한다.
 *
 * <h3>★ 기록이 화면을 멈추게 하면 안 된다</h3>
 * 이 API 는 사실상 <b>실패하지 않는다</b> — 저장에서 무슨 일이 나든 202 를 돌려준다.
 * 화면은 이 응답을 읽지도 않는다(떠나는 순간에는 sendBeacon 이라 읽을 수조차 없다).
 * 여기서 500 을 돌려줘 봐야 그걸 처리하는 코드가 화면에 생기고, 결국 곁다리인 기록이
 * 본 기능을 멈추게 한다. 무슨 일이 있었는지는 서버 로그에만 남긴다.
 * <p>
 * 다만 <b>본문이 규격을 벗어난 경우만</b>은 400 으로 분명히 거절한다. 그건 사용자의 사정이
 * 아니라 부르는 쪽의 버그이거나 일부러 두드리는 것이고, 조용히 삼키면 영영 안 고쳐진다.
 */
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

            응답은 항상 202 다 — 기록이 실패해도 화면은 계속 돌아야 한다.""")
    @PostMapping
    public ResponseEntity<ApiResponse<Void>> collect(@Valid @RequestBody EventRequests.Batch batch,
                                                     @RequestHeader(value = "User-Agent", required = false) String userAgent,
                                                     HttpServletRequest request,
                                                     HttpServletResponse response) {
        // 꺼져 있으면 쿠키도 발급하지 않는다. "안 모은다" 는 흔적조차 안 남긴다는 뜻이어야 한다.
        if (!analyticsService.isEnabled()) {
            return ResponseEntity.accepted().body(ApiResponse.ok());
        }

        // ★ 본문 크기는 여기서 재지 않는다 — @RequestBody 가 이미 다 읽은 뒤라 늦다.
        //   RequestSizeLimitFilter 가 읽기 전에 막는다.
        if (batch.events().size() > analyticsService.getMaxBatch()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "한 번에 보낼 수 있는 이벤트는 %d건까지입니다".formatted(analyticsService.getMaxBatch()));
        }

        String anonId = anonIdResolver.resolve(request, response);
        Long userId = currentUserId();

        try {
            analyticsService.collect(batch, anonId, userId, userAgent);
            // ★ 저장과 다른 트랜잭션이어야 해서 여기서 따로 부른다(AnalyticsService#linkIdentity 주석).
            analyticsService.linkIdentity(anonId, userId);
        } catch (RuntimeException ex) {
            // 여기서 위로 던지면 500 이 나가고, 그걸 본 화면이 재시도를 하게 된다.
            // 기록은 잃어도 되는 것이라 잃고 만다.
            log.warn("행동 기록 저장 실패 — 이벤트 {}건을 버린다", batch.events().size(), ex);
        }

        // 202 — "받아 두었다". 실제로 몇 줄이 저장됐는지는 알려주지 않는다.
        // 몇 개가 버려졌는지를 화면에 알려주면 허용 키 목록을 밖에서 알아낼 수 있고,
        // 무엇보다 화면이 그 숫자를 보고 무언가 하기 시작한다.
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok());
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
