package com.lore.piecemaker.adreport;

import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Piece Maker 광고 집계")
@RestController
@RequestMapping("/api/piece-maker/v1/admin/ad-cohort")
public class AdCohortController {
    private final AdCohortService service;

    public AdCohortController(AdCohortService service) {
        this.service = service;
    }

    @Operation(summary = "광고 첫 유입 후 24시간 정상 열람 집계", description = """
            운영자 전용 읽기 API. campaign은 영문·숫자·점·밑줄·하이픈 1~64자, from/to/asOf는 ISO-8601 시각이다.
            from은 포함, to는 제외하며 from < to <= asOf <= 현재, 조회 기간은 최대 31일이다. asOf 생략 시 현재를 쓴다.
            계정별 최초로 연결된 광고 유입을 전체 이력에서 고른 뒤 캠페인·기간으로 거른다. 계정별 중복은 없다.
            기존 제출자·이전 정상 열람자·관리자·탈퇴자·설정한 테스트 계정·모호한 연결은 제외한다.
            만료·선택 삭제·수집 검증 공백으로 신규 여부를 판단할 수 없는 계정은 HISTORY_UNVERIFIABLE로 제외한다.
            history에는 실제 조회 시점의 보관 범위와 운영 검증으로 설정한 연속 수집 구간을 반환한다.
            보관 범위를 벗어난 from은 400이다. 오래된 asOf로 삭제 자료를 복원하지 않는다.
            24시간 이내(경계 포함) 첫 정상 열람, 관찰 중, 기간 내 미완료를 구분한다.
            계정 연결이 없거나 모호한 유입 수는 계정 수와 별도로 반환한다. 계정번호나 개인정보는 반환하지 않는다.
            서버 수신 시각에 근거한 내부 UTM 집계이며 Meta 기여 전환 보고서가 아니다.
            수집이 중지되어도 저장된 이력을 조회할 수 있고 collectionEnabled=false로 표시한다.""")
    @GetMapping
    public ApiResponse<AdCohortReport> report(@LoginUser Long userId,
                                            @RequestParam("campaign") String campaign,
                                            @RequestParam("from") String from,
                                            @RequestParam("to") String to,
                                            @RequestParam(value = "asOf", required = false) String asOf) {
        return ApiResponse.ok(service.report(userId, campaign, from, to, asOf));
    }
}
