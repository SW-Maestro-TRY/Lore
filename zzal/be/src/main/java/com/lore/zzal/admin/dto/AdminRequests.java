package com.lore.zzal.admin.dto;

import com.lore.zzal.motion.HumanVerdict;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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
            @Size(max = 500) String note) {
    }

    /**
     * 맥미니가 다시 만든 그림을 등록한다.
     *
     * ★ presign 으로 올린 <b>자기 키</b>여야 한다({@code S3Service.consume} 가 주인·재사용을 판정).
     *   키를 그냥 받아 적으면 아무 문자열이나 그림 자리에 들어간다.
     */
    @Schema(description = "맥미니 재생성 결과 등록")
    public record Upload(

            @Schema(description = "presign 으로 올린 이미지 키", example = "images/zzal/tmp/ab12.webp")
            @NotBlank @Size(max = 300) String imageKey) {
    }
}
