package com.lore.piecemaker.adlanding;

import com.lore.common.analytics.AnonIdResolver;
import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@Tag(name = "광고 측정")
@RestController
@RequestMapping("/api/piece-maker/v1/ad-landings")
public class AdLandingController {
    private final AdLandingService service;
    private final AnonIdResolver identities;
    public AdLandingController(AdLandingService service, AnonIdResolver identities) { this.service = service; this.identities = identities; }

    @Operation(summary = "광고 URL 최초 수신 기록", description = "실제 광고 URL 진입 때 호출한다. 같은 쿠키와 requestKey의 재시도는 최초 서버 시각을 유지한다. 광고 정보를 바꾸거나 expectedUserId가 현재 인증과 다르면 409. 새 기록의 로그인 계정은 즉시 귀속하지만, 기존 익명 기록은 별도 claim으로 연결한다. 분석 비활성화는 쿠키 발급·저장 없이 409. 잘못된 본문은 400, 쿠키별 분당 상한은 429, DB 실패는 5xx이며 다시 시도할 수 있다.")
    @PostMapping
    public ApiResponse<AdLandingResponse> capture(@Valid @RequestBody AdLandingRequests.Capture input,
                                                  HttpServletRequest request, HttpServletResponse response) {
        service.requireEnabled();
        AdLandingService.validate(input);
        var auth = SecurityContextHolder.getContext().getAuthentication();
        Long userId = auth != null && auth.getPrincipal() instanceof Long id ? id : null;
        AdLandingService.requireExpectedUser(userId, input.expectedUserId());
        return ApiResponse.ok(service.capture(identities.resolve(request, response), userId, input));
    }

    @Operation(summary = "이번 광고 방문을 로그인 계정에 연결", description = "기존 익명 쿠키와 서버 발급 landingId가 모두 일치해야 한다. expectedUserId가 인증 계정과 다르면 409이며 변경하지 않는다. 다른 계정이 같은 방문을 요구하면 원래 소유자는 유지하되 모호함을 표시해 집계에서 제외한다. 쿠키나 기록이 없으면 404. 새 쿠키를 발급하거나 과거 익명 이벤트 전체를 연결하지 않는다.")
    @PostMapping("/{id}/claim")
    public ApiResponse<AdLandingResponse.Claim> claim(@LoginUser Long userId, @PathVariable String id,
                                                      @Valid @RequestBody AdLandingRequests.Claim input, HttpServletRequest request) {
        return ApiResponse.ok(service.claim(AdLandingCookie.readExisting(request), userId, id, input.expectedUserId()));
    }
}
