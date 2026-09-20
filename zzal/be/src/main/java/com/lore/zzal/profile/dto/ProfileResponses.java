package com.lore.zzal.profile.dto;

import com.lore.zzal.profile.ZzalUserProfile;
import io.swagger.v3.oas.annotations.media.Schema;

public final class ProfileResponses {

    private ProfileResponses() {
    }

    /** 지금까지 답한 것. 아직 안 답한 칸은 null 이다. */
    @Schema(description = "저장된 사용자 정보. 미응답 항목은 null 이다")
    public record Profile(String callMe, String visitTime, String relation,
                          String draws, String ageBand, String cameFrom) {

        public static Profile of(ZzalUserProfile p) {
            return new Profile(p.getCallMe(), p.getVisitTime(), p.getRelation(),
                    p.getDraws(), p.getAgeBand(), p.getCameFrom());
        }
    }
}
