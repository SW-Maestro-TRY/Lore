package com.lore.trailer.hypothesis;

import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.response.ApiResponse;
import com.lore.trailer.hypothesis.dto.HypothesisRequests;
import com.lore.trailer.hypothesis.dto.HypothesisResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 가설 API — 독자가 복선 카드로 만든 가설을 맡기고, 판정을 되묻고, 보관함을 본다. 모두 로그인이 필요하다.
 *
 * <p>경로는 사용자가 정했다(NA decisions.md 1-18). {@code /me/} 를 쓰지 않는 것은 공통 경로 규칙의 예외다.
 * 명세의 정본은 NA {@code migration/front_back_protocol.md} 2-5~2-7 이다.
 */
@Tag(name = "가설", description = "복선 카드로 만든 가설을 맡기고 판정을 되묻는다. 로그인 필요")
@RestController
@RequestMapping("/api/trailer/v1/hypotheses")
public class HypothesisController {

    private final HypothesisService service;

    public HypothesisController(HypothesisService service) {
        this.service = service;
    }

    @Operation(summary = "가설 맡기기", description = """
            독자가 "가설 판정하기"를 누르면 부른다. 저장이 곧 맡기기다 — 서버는 줄을 넣고 `judgementStatus` 를
            `PENDING` 으로 둔다. 판정은 운영자가 따로 돌려 넣는다. 화면은 응답의 `id` 로 되묻는다.
            - 담은 카드는 그 회차 N 으로 가린 값으로 **복사해 둔다**. 뒤에 카드 표가 바뀌어도 이 가설의 카드는 그대로다
            - 맡긴 뒤에는 제목 · 주장 · 카드 · 해석을 고칠 수 없다. 새 가설은 새로 맡긴다
            - `stateDigest` · `cardsDigest` 가 카드 표의 값과 다르면 400(TRAILER_DIGEST_MISMATCH) — 페이지를 새로 열어야 한다
            - 회차가 없거나 범위 밖이면 400(TRAILER_INVALID_CHAPTER). 카드 표가 비어 있으면 503(TRAILER_LEDGER_NOT_LOADED)
            - 주장이 비었거나, 카드가 없거나 겹치거나 N화 기록에 없거나, 해석이 담지 않은 카드의 것이거나,
              글이 위 끝(제목 180 · 주장 6,000 · 해석 4,000자)을 넘으면 400(INVALID_INPUT)에 무엇이 틀렸는지 적어 준다""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "맡겼음. 저장된 가설(PENDING)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "입력이 틀림(INVALID_INPUT · TRAILER_INVALID_CHAPTER · TRAILER_DIGEST_MISMATCH)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503",
                    description = "카드 표가 비어 있음(TRAILER_LEDGER_NOT_LOADED)")})
    @PostMapping
    public ApiResponse<HypothesisResponses.Hypothesis> submit(@LoginUser Long userId,
                                                              @RequestBody HypothesisRequests.Submit body) {
        return ApiResponse.ok(service.submit(userId, body));
    }

    @Operation(summary = "가설 하나와 판정 되묻기", description = """
            맡긴 가설을 요청 id 로 연다. 보관함에서 하나를 열 때, 판정을 맡긴 뒤 결과를 되물을 때 부른다.
            몇 초마다 되물을지는 화면이 정한다 — 판정은 운영자가 따로 넣어서 시간이 걸린다.
            - `judgementStatus` 가 `PENDING` 이면 판정 칸 셋은 null
            - `COMPLETE` 면 `judgement`(judge.py 출력 그대로 — grade · reason · support · against · cited_cards)와
              `presentation`(편집본. 없을 수 있음), `judgedAt`
            - `FAILED` 면 독자에게 보일 `failureMessage`
            - 없는 번호와 **남의 가설은 같은 404**(TRAILER_HYPOTHESIS_NOT_FOUND) — 번호를 바꿔 가며 남의 가설을 찾아낼 수 없다""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "가설 하나"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "없는 번호 또는 남의 가설(TRAILER_HYPOTHESIS_NOT_FOUND)")})
    @GetMapping("/{id}")
    public ApiResponse<HypothesisResponses.Hypothesis> get(@LoginUser Long userId,
                                                           @Parameter(description = "요청 id. 맡길 때 받은 값", example = "17")
                                                           @PathVariable("id") String id) {
        return ApiResponse.ok(service.get(userId, id));
    }
}
