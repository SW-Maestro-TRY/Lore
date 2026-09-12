package com.lore.zzal.profile.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

public final class ProfileRequests {

    private ProfileRequests() {
    }

    /**
     * 6문항의 답. <b>보낸 것만 저장된다</b> — 안 보낸 칸은 그대로 남는다.
     *
     * ★ 선택지 문구는 화면이 정한다. 서버는 길이만 본다.
     */
    @Schema(description = "사용자 정보 6문항. 전달한 항목만 저장하며 나머지는 기존 값을 유지한다")
    public record Patch(

            @Schema(description = "캐릭터가 사용자를 부르는 호칭. 대사 전반에 사용한다", example = "주인님")
            @Size(max = 20) String callMe,

            @Schema(description = "주로 방문하는 시간대", example = "밤")
            @Size(max = 20) String visitTime,

            @Schema(description = "캐릭터와의 관계", example = "내 자캐")
            @Size(max = 20) String relation,

            @Schema(description = "그림 창작 여부", example = "가끔")
            @Size(max = 20) String draws,

            @Schema(description = "연령대", example = "20대")
            @Size(max = 20) String ageBand,

            @Schema(description = "서비스 유입 경로", example = "엑스(트위터)")
            @Size(max = 40) String cameFrom) {
    }
}
