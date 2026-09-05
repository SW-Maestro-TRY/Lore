package com.lore.zzal.feedback;

import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.response.ApiResponse;
import com.lore.zzal.feedback.dto.FeedbackRequests;
import com.lore.zzal.feedback.dto.FeedbackResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 후기 API.
 *
 * 주소는 펫 API 와 같은 규칙이다 — 내 것은 {@code me} 밑. 주소에 남의 번호를 넣을 자리가
 * 없어서 남의 데이터를 건드리는 실수 자체가 불가능해진다.
 *
 * <h3>★ 이메일을 받지 않는다</h3>
 * 가입할 때 이미 받았다. 두 곳에 두면 지켜야 할 곳이 늘고 파기 시점도 따로 관리해야 한다.
 */
@Tag(name = "후기", description = "결과물에 대한 후기 — 한 사람이 한 펫에 한 번")
@RestController
@RequestMapping("/api/zzal/v1/me/pets/{petId}/feedback")
public class FeedbackController {

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @Operation(summary = "후기 남기기", description = """
            별점(필수)·칩(선택)·자유 글(선택)을 받는다.

            - **한 사람이 한 펫에 한 번**이다. 두 번째는 409(ZZAL_FEEDBACK_ALREADY_SUBMITTED)
            - 응답은 조회와 **같은 모양**이다 — 낸 뒤에 다시 물어볼 필요가 없다
            - 칩은 정해진 값만 받는다. 없는 값을 보내면 400 이다
            - **보상은 지금 나가지 않는다.** 무엇을 줄지 아직 안 정해졌고, 정해지면 설정값만
              바꾸면 붙는다. 화면에 "무엇을 드립니다" 라고 쓰지 말 것""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "남겼음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "별점이 1~5 밖이거나 없는 칩(INVALID_INPUT)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "없는 펫 또는 남의 펫(ZZAL_PET_NOT_FOUND)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "이미 냈음(ZZAL_FEEDBACK_ALREADY_SUBMITTED)")})
    @PostMapping
    public ApiResponse<FeedbackResponses.Submitted> submit(@LoginUser Long userId,
                                                           @PathVariable Long petId,
                                                           @Valid @RequestBody FeedbackRequests.Submit request) {
        Instant now = Instant.now();
        ZzalFeedback feedback = feedbackService.submit(
                userId, petId, request.rating(), request.tags(), request.text(), now);
        return ApiResponse.ok(FeedbackResponses.Submitted.of(feedback));
    }

    @Operation(summary = "내 후기 조회", description = """
            이 펫에 **이미 냈는지**와, 냈다면 그 내용.

            - 안 냈어도 에러가 아니다 — `submitted` 가 false 로 온다
            - 화면은 이 값을 보고 후기 칸을 띄울지 정한다. 이미 낸 사람에게 또 띄우지 않기 위해서다
            - ★ 이 판정을 펫 상태 응답에 얹지 않은 이유는 그쪽이 3초마다 도는 폴링이기 때문이다""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "조회 성공(안 냈으면 submitted=false)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "없는 펫 또는 남의 펫(ZZAL_PET_NOT_FOUND)")})
    @GetMapping
    public ApiResponse<FeedbackResponses.Submitted> mine(@LoginUser Long userId, @PathVariable Long petId) {
        return ApiResponse.ok(feedbackService.find(userId, petId)
                .map(FeedbackResponses.Submitted::of)
                .orElseGet(FeedbackResponses.Submitted::none));
    }
}
