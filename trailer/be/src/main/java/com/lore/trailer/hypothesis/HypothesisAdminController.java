package com.lore.trailer.hypothesis;

import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.response.ApiResponse;
import com.lore.trailer.hypothesis.dto.HypothesisRequests;
import com.lore.trailer.hypothesis.dto.HypothesisResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영자용 가설 API — 판정 안 된 가설을 가져가고(2-8), 판정을 넣는다(2-9).
 *
 * <p>판정은 서버가 아니라 운영자(사용자)의 PC 에서 {@code judge.py} 가 한다(NA later.md 1-2). 그 흐름의 3번과 5번이
 * 이 두 API 다. 로그인한 사용자 가운데 {@code users.role} 이 ADMIN 인 사람만 부를 수 있다({@link TrailerAdminGuard}).
 */
@Tag(name = "가설 운영자", description = "판정 안 된 가설을 가져가고 판정을 넣는다. 운영자(ADMIN)만")
@RestController
@RequestMapping("/api/trailer/v1/admin/hypotheses")
public class HypothesisAdminController {

    private final HypothesisService service;

    public HypothesisAdminController(HypothesisService service) {
        this.service = service;
    }

    @Operation(summary = "판정 안 된 가설 가져가기", description = """
            `judgementStatus` 가 `PENDING` 인 가설을 **맡긴 순서(오래된 것이 앞)**로 준다. 먼저 맡긴 독자가 먼저 판정을 받는다.
            운영자가 `judge.py` 를 돌리기 전에 부른다.
            - 한 줄에 `judge.py` 의 입력이 다 든다 — `chapter` · `claim` · `cards[]`(복사한 카드) · `notes{}` · 해시 둘
            - 해시 둘은 독자가 맡길 때의 값이다. `judge.py` 가 자기 파일과 같은 자료인지 이 값으로 본다
            - 운영자가 아니면 403(ADMIN_ONLY). 없으면 빈 배열""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "판정 안 된 가설. 오래된 것이 앞"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "운영자가 아님(ADMIN_ONLY)")})
    @GetMapping
    public ApiResponse<HypothesisResponses.PendingList> pending(@LoginUser Long userId) {
        return ApiResponse.ok(service.pendingForOperator(userId));
    }

    @Operation(summary = "판정 넣기", description = """
            `judge.py` 가 끝난 뒤 결과를 넣는다. `id` 의 줄을 찾아 판정 칸을 채우고 `judgementStatus` 를 바꾸고 `judgedAt` 을 찍는다.
            - `COMPLETE` 면 `judgement` 필수(grade · reason · support · against — `cited_cards` 도 그대로 실린다). `presentation` 은 있을 때만
            - `FAILED` 면 `failureMessage` 필수(독자에게 보인다)
            - **이미 판정한 가설도 덮어쓴다** — 다시 돌린 결과를 넣을 수 있게
            - 몸통의 `id` 는 lore 의 요청 id 다. judge.py 의 `request_id`(입력의 해시)가 아니다
            - 없는 `id` 는 404(TRAILER_HYPOTHESIS_NOT_FOUND). 운영자가 아니면 403(ADMIN_ONLY). 모양이 틀리면 400(INVALID_INPUT)""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "판정을 넣은 가설(2-6 의 모양)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "모양이 틀림(INVALID_INPUT)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "운영자가 아님(ADMIN_ONLY)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없는 id(TRAILER_HYPOTHESIS_NOT_FOUND)")})
    @PostMapping("/judge")
    public ApiResponse<HypothesisResponses.Hypothesis> judge(@LoginUser Long userId,
                                                             @RequestBody HypothesisRequests.Judge body) {
        return ApiResponse.ok(service.judgeForOperator(userId, body));
    }
}
