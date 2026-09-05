package com.lore.zzal.admin.dto;

import com.lore.zzal.motion.GateVerdict;
import com.lore.zzal.motion.ZzalMotion;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** 관리자 검수 API 가 돌려주는 것들. */
public final class AdminResponses {

    private AdminResponses() {
    }

    /**
     * 검수 대기 중인 움짤 한 칸.
     *
     * ★★ 이 화면은 <b>남의 데이터를 본다.</b> 그래서 검수에 실제로 쓰이는 것만 담는다 —
     *    펫 이름·주인이 누구인지·펫 번호는 "이 움짤이 잘 구워졌나" 를 판단하는 데
     *    아무 도움이 안 되면서, 화면에 띄우는 순간 개인정보 표면이 된다.
     *    보이지 않는 칸은 유출될 수도 없다.
     *
     * ★ 대신 게이트가 뭐라 했는지({@code gateVerdict}·{@code gateNote}·{@code gateVersion})는
     *   전부 담는다. 사람 판정과 나란히 놓고 일치율을 봐야 "PASS 는 사람 없이 지급" 으로
     *   넘어갈 시점을 감이 아니라 숫자로 정할 수 있다({@link ZzalMotion} 주석 참고).
     */
    @Schema(description = "검수 대기 중인 움짤 하나")
    public record Pending(

            @Schema(description = "모션 번호. 판정을 보낼 때 이 번호를 쓴다", example = "9") Long motionId,

            @Schema(description = "동작 이름", example = "교감1_머리쓰다듬") String name,

            @Schema(description = """
                    움짤이 사는 곳. **전체 주소가 아니라 뒷부분만** 준다 — 앞에 붙는 CDN 주소는
                    배포처마다 다르다(모션 API 와 같은 약속).

                    ★ 이미 `images/` 로 시작한다. 화면이 CDN 을 앞에 붙일 때 이 앞머리가
                    겹치지 않게 해야 한다""",
                    example = "images/zzal/pets/23/motions/9/motion.webp")
            String imageKey,

            @Schema(description = "기계 게이트의 판정. PASS · REVIEW · FAIL", example = "REVIEW")
            GateVerdict gateVerdict,

            @Schema(description = "게이트가 남긴 근거(무엇에 걸렸는지)", example = "발 위치가 흔들림")
            String gateNote,

            @Schema(description = "어느 게이트 버전이 내린 판정인가", example = "g1") String gateVersion,

            @Schema(description = "몇 번 구웠나", example = "1") int attempts,

            @Schema(description = "언제 만들어졌나") Instant createdAt) {

        public static Pending from(ZzalMotion motion) {
            return new Pending(
                    motion.getId(),
                    motion.getName(),
                    motion.getImageKey(),
                    motion.getGateVerdict(),
                    motion.getGateNote(),
                    motion.getGateVersion(),
                    motion.getAttempts(),
                    motion.getCreatedAt());
        }
    }
}
