package com.lore.piecemaker.feedback;

import com.lore.common.response.ApiResponse;
import com.lore.piecemaker.feedback.dto.PieceMakerFeedbackRequests;
import com.lore.piecemaker.feedback.dto.PieceMakerFeedbackResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 피드백 API — 오류 신고와 판정 후기를 받는다. 로그인 없이도 보낼 수 있다.
 *
 * <p>경로가 {@code /public/} 아래에 있는 까닭: 공통 보안 규칙은 {@code /public/**} 의 GET 만 열어 두므로,
 * 이 POST 하나는 {@code WebSecurityConfig} 와 문서 생성기({@code ApiDocsConfig})에 따로 적어 두었다.
 * {@code @LoginUser} 를 쓰지 않는다 — 그 리졸버는 로그인이 없으면 401 을 던진다. 여기서는 있으면 적고 없으면 비운다.
 */
@Tag(name = "피드백", description = "오류 신고와 판정 후기를 받는다. 로그인 없이도 보낼 수 있다")
@RestController
@RequestMapping("/api/piece-maker/v1/public/feedback")
public class PieceMakerFeedbackController {

    private final PieceMakerFeedbackService service;

    public PieceMakerFeedbackController(PieceMakerFeedbackService service) {
        this.service = service;
    }

    @Operation(summary = "피드백 보내기", description = """
            독자가 오류를 신고하거나 판정 후기를 남길 때 부른다. 로그인 없이도 보낼 수 있다.
            - `kind` 는 `ERROR_REPORT`(오류 신고) 또는 `JUDGEMENT_REVIEW`(판정 후기). 다른 값이면 400(INVALID_INPUT)
            - `body` 는 앞뒤 빈칸을 뗀 뒤 1자 이상 2,000자까지. 비었거나 넘으면 400(INVALID_INPUT)
            - 로그인한 상태로 보내면 누가 보냈는지 함께 적는다. 응답에는 보낸 사람을 싣지 않는다
            - 보낸 뒤에는 고칠 수 없다. 서버는 받았다는 뜻으로 저장된 줄을 돌려준다""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "저장됨"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "입력이 틀림(INVALID_INPUT) — kind 가 둘 중 하나가 아니거나 body 가 비었거나 2,000자를 넘음")})
    @PostMapping
    public ApiResponse<PieceMakerFeedbackResponses.Feedback> create(@RequestBody PieceMakerFeedbackRequests.Create body) {
        return ApiResponse.ok(service.create(currentUserIdOrNull(), body));
    }

    /** 로그인했으면 그 번호, 아니면 null. JWT 필터가 넣는 주체는 {@code Long} 이고 익명은 글("anonymousUser")이다. */
    private static Long currentUserIdOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof Long userId ? userId : null;
    }
}
