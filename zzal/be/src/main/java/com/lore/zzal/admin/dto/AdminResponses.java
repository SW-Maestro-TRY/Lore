package com.lore.zzal.admin.dto;

import com.lore.zzal.motion.GateVerdict;
import com.lore.zzal.motion.ZzalMotion;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** 관리자 검수 API 가 돌려주는 것들(api-v2.md 5절). */
public final class AdminResponses {

    private AdminResponses() {
    }

    /**
     * 검수 대기 중인 움짤 한 칸.
     *
     * ★★ 이 화면은 <b>남의 데이터를 본다.</b> 그래서 검수에 실제로 쓰이는 것만 담는다 —
     *    펫 이름·주인이 누구인지는 "이 움짤이 잘 구워졌나" 를 판단하는 데
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

            @Schema(description = "동작 key(영문)", example = "roll") String key,

            @Schema(description = "동작 이름(한글)", example = "구르기") String label,

            @Schema(description = """
                    움짤이 사는 곳. **전체 주소가 아니라 뒷부분만** 준다 — 앞에 붙는 CDN 주소는
                    배포처마다 다르다(모션 API 와 같은 약속).""",
                    example = "images/zzal/pets/23/motions/9/motion.webp")
            String imageKey,

            @Schema(description = "기계 게이트의 판정. PASS · REVIEW · FAIL", example = "REVIEW")
            GateVerdict gateVerdict,

            @Schema(description = "게이트가 남긴 근거(무엇에 걸렸는지)", example = "발 위치가 흔들림")
            String gateNote,

            @Schema(description = "어느 게이트 버전이 내린 판정인가", example = "g1") String gateVersion,

            @Schema(description = "서버가 아는 굽기 횟수 — API 는 시작할 때, 맥미니는 결과를 올릴 때 +1(러너의 헛돈 횟수는 안 보인다)", example = "1") int attempts,

            @Schema(description = "맥미니 재생성 몇 번째인가(최대 2)", example = "0") int regenRound,

            @Schema(description = "어느 밤의 큐에서 나온 것인가") LocalDate nightOf,

            @Schema(description = "언제 만들어졌나") Instant createdAt) {

        public static Pending from(ZzalMotion m, String label) {
            return new Pending(m.getId(), m.getName(), label, m.getImageKey(),
                    m.getGateVerdict(), m.getGateNote(), m.getGateVersion(),
                    m.getAttempts(), m.getRegenRound(), m.getNightOf(), m.getCreatedAt());
        }
    }

    /**
     * 맥미니(codex) 러너가 폴링해 가는 재생성 주문 한 건.
     *
     * ★★ <b>지시문 본문({@code blockText})을 통째로 실어 보낸다.</b> 파일 이름만 주면 맥미니가 레포를 갖고 있어야 하고,
     *    그 순간 "서버가 쓰는 지시문" 과 "맥미니가 쓰는 지시문" 이 조용히 갈라질 수 있다 —
     *    같은 이름의 다른 파일로 구운 그림은 눈으로 봐도 티가 안 난다.
     *
     * ★ 시트와 정체성 문단도 함께 준다. 캐릭터를 유지하려면 부화 때 만든 그 두 개를 그대로 써야 한다.
     */
    @Schema(description = "맥미니가 가져갈 재생성 주문")
    public record RegenRequest(
            @Schema(description = "모션 번호. 다 만들면 이 번호로 업로드한다") Long motionId,
            @Schema(description = "펫 번호") Long petId,
            @Schema(description = "부화 때 만든 캐릭터 시트 키") String sheetImageKey,
            @Schema(description = "부화 때 만든 생김새 문단") String identityText,
            @Schema(description = "동작 key(영문)", example = "roll") String motionKey,
            @Schema(description = "16프레임 지시문 본문") String blockText,
            @Schema(description = "몇 번째 재생성인가(1 또는 2)") int regenRound) {
    }

    /**
     * 그 밤이 어떻게 됐나 — <b>모션 행을 직접 세어</b> 만든다.
     *
     * ★ {@code zzal_night_run} 의 숫자를 안 쓰는 이유 — 그건 "집어서 넘긴 수" 이고 굽기는 그 뒤에 밤새 돈다(B52).
     *   지금 실제로 몇 개가 검수 대기이고 몇 개가 열렸는지는 행을 세어야 나온다.
     */
    @Schema(description = "그 밤 현황")
    public record NightSummary(
            LocalDate nightOf,
            @Schema(description = "아직 안 집힌 것(이월 포함)") long queued,
            @Schema(description = "지금 굽는 중") long baking,
            @Schema(description = "검수 대기") long review,
            @Schema(description = "맥미니 재생성 요청 중") long localRequested,
            @Schema(description = "검수 통과(공개)") long open,
            @Schema(description = "그 밤 실패 — 다음 밤에 다시 오른다") long failed,
            @Schema(description = "그 밤에 든 돈(달러). 실패한 호출도 포함") BigDecimal costUsd) {
    }
}
