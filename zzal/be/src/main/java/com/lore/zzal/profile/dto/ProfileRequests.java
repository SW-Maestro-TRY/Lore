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
    @Schema(description = "부화 대기 중 여울이 묻는 6문항. 한 문항씩 보내도 되고 한꺼번에 보내도 된다")
    public record Patch(

            @Schema(description = "아이가 나를 부르는 말. 게임 내내 대사에 쓰인다", example = "주인님")
            @Size(max = 20) String callMe,

            @Schema(description = "주로 오는 시각", example = "밤")
            @Size(max = 20) String visitTime,

            @Schema(description = "이 아이와의 사이", example = "내 자캐")
            @Size(max = 20) String relation,

            @Schema(description = "그림을 그리는지", example = "가끔")
            @Size(max = 20) String draws,

            @Schema(description = "나이대", example = "20대")
            @Size(max = 20) String ageBand,

            @Schema(description = "서비스를 알게 된 경로", example = "엑스(트위터)")
            @Size(max = 40) String cameFrom) {
    }
}
