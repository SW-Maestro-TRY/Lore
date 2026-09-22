package com.lore.zzal.wish;

import com.lore.common.analytics.AnalyticsService;
import com.lore.common.analytics.AnonIdResolver;
import com.lore.common.analytics.dto.EventRequests;
import com.lore.common.auth.jwt.LoginUser;
import com.lore.zzal.wish.dto.MotionWishRequests;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 동작 요청 API — "이런 동작도 보고 싶어요".
 *
 * 주소는 펫 API 와 같은 규칙이다 — 내 것은 {@code me} 밑. 주소에 남의 번호를 넣을 자리가
 * 없어서 남의 데이터를 건드리는 실수 자체가 불가능해진다.
 *
 * <h3>★ 글과 셈을 <b>다른 곳에</b> 남긴다</h3>
 * 사람이 쓴 글은 {@code zzal_motion_wish} 에만 들어가고, 행동 기록으로는 {@code motion_wish_submitted}
 * 라는 <b>고정된 이름 한 줄</b>만 나간다. 기록층은 사람이 쓴 글을 일부러 안 받는 곳이라
 * ({@code AnalyticsService} 의 허용 키 목록) 거기에 글을 실으면 조용히 버려지거나, 더 나쁘게는
 * 버려지지 않고 남는다. 무엇을 바랐는지는 표에서 읽고, 몇 명이 바랐는지는 기록에서 읽는다.
 */
@Tag(name = "동작 요청", description = "보고 싶은 동작을 자유 글로 남긴다")
@RestController
@RequestMapping("/api/zzal/v1/me/pets/{petId}/motion-wish")
public class MotionWishController {

    private static final Logger log = LoggerFactory.getLogger(MotionWishController.class);

    /**
     * 행동 기록에 남길 이름. <b>고정</b>이다.
     *
     * ★ 사람이 쓴 글은 여기에 섞이지 않는다. 이름 하나만 나가므로 "몇 명이 몇 번 남겼나" 는
     *   세어지고, "무엇을 바랐나" 는 기록층에서 읽을 수 없다 — 그게 의도한 경계다.
     */
    private static final String EVENT_NAME = "motion_wish_submitted";

    private final MotionWishService motionWishService;
    private final AnalyticsService analyticsService;
    private final AnonIdResolver anonIdResolver;

    public MotionWishController(MotionWishService motionWishService,
                                AnalyticsService analyticsService,
                                AnonIdResolver anonIdResolver) {
        this.motionWishService = motionWishService;
        this.analyticsService = analyticsService;
        this.anonIdResolver = anonIdResolver;
    }

    @Operation(summary = "보고 싶은 동작 남기기", description = """
            자유 글 한 줄을 받는다. 로그인이 필요하고, 내 펫에만 남길 수 있다.

            - 앞뒤 공백은 서버가 떼고 저장한다. **공백뿐이면 400**(INVALID_INPUT)
            - 길이는 받은 그대로 재서 1~60자. 넘으면 400
            - **한 번 부를 때마다 한 줄**이다 — 후기와 달리 여러 번 남길 수 있다
            - 한 아이에 **하루 20줄**까지. 넘으면 409(ZZAL_MOTION_WISH_DAILY_LIMIT),
              한국 시각 자정에 풀린다
            - 성공하면 **본문 없이 204** 다. 남긴 글을 되돌려줄 이유가 없고,
              화면은 방금 자기가 보낸 값을 이미 들고 있다""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "남겼음(본문 없음)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "빈 글·공백뿐·60자 초과(INVALID_INPUT)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "로그인이 필요함"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "없는 펫 또는 남의 펫(ZZAL_PET_NOT_FOUND)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "오늘 몫을 다 씀(ZZAL_MOTION_WISH_DAILY_LIMIT)")})
    @PostMapping
    public ResponseEntity<Void> submit(@LoginUser Long userId,
                                       @PathVariable Long petId,
                                       @Valid @RequestBody MotionWishRequests.Submit request,
                                       @RequestHeader(value = "User-Agent", required = false) String userAgent,
                                       HttpServletRequest httpRequest,
                                       HttpServletResponse httpResponse) {
        Instant now = Instant.now();
        motionWishService.submit(userId, petId, request.text(), now);
        record(userId, now, httpRequest, httpResponse, userAgent);
        return ResponseEntity.noContent().build();
    }

    /**
     * "남겼다" 는 사실 한 줄.
     *
     * <h3>★★ 기록이 이 기능을 멈추게 하면 안 된다</h3>
     * 글은 이미 저장됐다. 여기서 예외가 위로 올라가면 사용자는 <b>남겼는데 500</b> 을 보고
     * 한 번 더 누른다 — 그때 줄이 하나 더 쌓인다. 그래서 무슨 일이 나든 삼키고 로그만 남긴다
     * ({@code AnalyticsController} 가 같은 이유로 같은 모양을 한다).
     *
     * <h3>★ 화면 경로를 안 싣는다</h3>
     * 이 주소에는 펫 번호가 박혀 있어 그대로 실으면 경로가 줄마다 달라진다. 셈에 쓸 수 없는
     * 값이고, 어느 화면에서 눌렀는지는 이벤트 이름이 이미 말한다.
     */
    private void record(Long userId, Instant now,
                        HttpServletRequest httpRequest, HttpServletResponse httpResponse, String userAgent) {
        if (!analyticsService.isEnabled()) {
            return;
        }
        try {
            String anonId = anonIdResolver.resolve(httpRequest, httpResponse);
            analyticsService.collect(
                    new EventRequests.Batch(null, null,
                            List.of(new EventRequests.Event(EVENT_NAME, now.toEpochMilli(), null, null))),
                    anonId, userId, userAgent);
            analyticsService.linkIdentity(anonId, userId);
        } catch (RuntimeException ex) {
            log.warn("동작 요청 기록 실패 — 글은 이미 저장됐다(userId={})", userId, ex);
        }
    }
}
