package com.lore.zzal.admin.dto;

import com.lore.zzal.motion.HumanVerdict;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 관리자 검수 API 가 받는 것들. */
public final class AdminRequests {

    private AdminRequests() {
    }

    /**
     * 움짤 하나에 대한 상훈님 판정.
     *
     * ★ 등급만 받지 않고 note 를 함께 받는 이유 — 판정 코멘트가 등급보다 정보가 많다.
     *   "REGENERATE" 만 쌓이면 게이트를 어느 방향으로 강화할지 못 정하지만,
     *   "발이 잘림" 이 세 번 쌓이면 그게 곧 다음에 만들 검사 항목이 된다.
     *   그래서 비워도 되게 두되(강제하면 판정 속도가 병목이 된다) 자리는 열어 둔다.
     */
    @Schema(description = "검수 판정 — 좋음(OK) 또는 다시 굽기(REGENERATE)")
    public record Verdict(

            @Schema(description = "OK(이대로 좋다) · REGENERATE(다시 구워야 한다)", example = "OK")
            @NotNull HumanVerdict verdict,

            @Schema(description = "왜 그렇게 봤는지. 선택이지만 REGENERATE 일 때 적어 두면 게이트 강화 재료가 된다",
                    example = "발이 잘림")
            @Size(max = 500) String note,

            @Schema(description = "OK 일 때 어느 판을 고르는가. 한 모션에 후보가 최대 일곱이라"
                    + "(API 1 + 맥미니 3 x 2라운드) 번호로 지목한다."
                    + " 비우면 지금 대표로 올라와 있는 판을 고른 것으로 본다.",
                    example = "12")
            Long candidateId) {
    }

    /**
     * 맥미니가 다시 만든 판들을 등록한다 — <b>한 라운드를 통째로</b>.
     *
     * <h3>★ 왜 한 판씩이 아니라 여럿인가</h3>
     * 맥미니는 <b>3판을 나란히</b> 굽는다(정본 1.9). 한 판씩 올리면 첫 판이 올라오는 순간 검수 대기로 바뀌어,
     * 아직 두 판이 오는 중인데 <b>사람이 먼저 보게 된다.</b> 그러면 "나온 판을 전부 보여 주고 고른다" 가 깨진다.
     *
     * ★ 키는 presign 으로 올린 <b>자기 것이고 아직 안 쓴 것</b>이어야 한다({@code S3Service.consume} 가 판정).
     *   그냥 받아 적으면 아무 문자열이나 그림 자리에 들어간다.
     */
    @Schema(name = "AdminUpload", description = "맥미니 재생성 결과 등록 — 한 라운드의 판을 전부 한 번에")
    public record Upload(

            @Schema(description = "이번 라운드에 나온 판들. 맥미니는 3판을 나란히 굽고 한 번에 올린다")
            @NotNull @Size(min = 1, max = 7) @Valid List<Candidate> candidates) {
    }

    /** 맥미니가 구운 판 하나. */
    @Schema(name = "AdminUploadCandidate", description = "맥미니가 구운 판 하나")
    public record Candidate(

            @Schema(description = "presign 으로 올린 완성본 키", example = "images/zzal/tmp/ab12.webp")
            @NotBlank @Size(max = 300) String imageKey,

            @Schema(description = "16프레임 격자 원본 키. 판정 화면이 같이 본다",
                    example = "images/zzal/tmp/ab12_grid.png")
            @Size(max = 300) String gridKey,

            @Schema(description = "맥미니가 잰 게이트 점수. 줄 세우는 데만 쓴다(통과 판정에는 안 쓴다)",
                    example = "0.91")
            Double gateScore) {
    }
}
