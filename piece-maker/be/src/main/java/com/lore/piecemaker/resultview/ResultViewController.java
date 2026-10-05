package com.lore.piecemaker.resultview;

import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 정상 판정이 실제 화면에 보였다는 신호를 계정당 한 번 저장한다(API 2-11). */
@Tag(name = "가설")
@RestController
@RequestMapping("/api/piece-maker/v1/hypotheses")
public class ResultViewController {
    private final ResultViewService service;

    public ResultViewController(ResultViewService service) {
        this.service = service;
    }

    @Operation(summary = "첫 정상 판정 열람 기록", description = """
            로그인한 독자의 정상 판정이 활성 탭의 화면에 실제로 보였을 때 부른다. 요청 몸통은 없다.
            서버는 소유자와 COMPLETE 상태 및 판정 형식을 검사하고 계정당 보관 범위의 첫 한 줄만 저장한다.
            보관 중 새 탭·재열람·동시 요청은 firstView=false와 기존 viewedAt을 돌려준다. 만료 뒤에는 새 기록이 될 수 있다.
            firstView=true는 생애 최초나 신규 광고 전환을 뜻하지 않는다. DB 저장 실패는 성공으로 답하지 않는다.
            app.analytics.enabled=false이면 409로 답하며 기록하지 않는다. 같은 설정에서는 재시도하지 않는다.
            viewedAt은 최초 저장 시 서버가 기록한 시각이다. 기능 도입 전 열람 이력을 복원하지 않는다.
            이 기록은 광고 24시간 성과 집계 그 자체가 아니다.
            Meta 설정과 시험 계정 검토가 완료된 경우, 제외 대상이 아닌 첫 저장 응답에만 metaEvent를 발급한다.
            metaEvent는 광고 출처와 무관한 보관 기록상 첫 정상 열람 신호이며 Meta 수신·광고 기여를 보장하지 않는다.
            응답 유실·픽셀 차단 시 내부 열람은 유지되지만 Meta 신호는 누락될 수 있다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "계정의 첫 열람 기록 또는 기존 기록"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "정상 판정이 아님(INVALID_INPUT)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "로그인 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "기록 비활성화(PIECE_MAKER_RESULT_VIEW_DISABLED). 저장되지 않았으며 재시도하지 않음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없는 번호 또는 남의 가설(PIECE_MAKER_HYPOTHESIS_NOT_FOUND)")
    })
    @PostMapping("/{id}/result-view")
    public ApiResponse<ResultViewResponse> record(@LoginUser Long userId, @PathVariable("id") String id) {
        return ApiResponse.ok(service.record(userId, id));
    }
}
