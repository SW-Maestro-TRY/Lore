package com.lore.zzal.motion.dto;

import com.lore.zzal.motion.ZzalMotion;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/** 모션 API 가 돌려주는 것들. */
public final class MotionResponses {

    private MotionResponses() {
    }

    /**
     * 도감 한 칸 — <b>이미 열린 것만</b> 이 모양으로 나간다.
     *
     * ★ 안 연 자리는 여기에 없다. 개수(total)에서 빼면 몇 칸이 잠겼는지 나오고,
     *   화면은 그 자리를 <b>이름 없는 빈 칸</b>으로 그린다. 아직 안 연 동작의 이름을
     *   미리 내려보내지 않는 이유는 {@link ZzalMotion} 주석과 같다 — 생성이 실패하면
     *   다른 동작으로 갈아끼워야 하는데, 이름을 약속해 버리면 그때 어길 말이 생긴다.
     */
    @Schema(description = "열린 동작 하나")
    public record Opened(
            @Schema(description = "이 펫의 몇 번째 동작인가. 0부터", example = "0") int seq,
            @Schema(description = "내부 이름. 실험의 동작 블록 파일명 그대로다(화면에 쓰지 말 것)",
                    example = "교감1_머리쓰다듬") String name,
            @Schema(description = "화면에 보일 이름", example = "머리 쓰다듬") String label,
            @Schema(description = """
                    움짤이 사는 곳. **전체 주소가 아니라 뒷부분만** 준다 — 앞에 붙는 CDN 주소는
                    배포처마다 다르고(로컬·dev·운영), 서버가 그것까지 정하면 화면이 어느 배포에서
                    도는지를 서버가 알아야 한다. 펫 API 의 `imageBase` 와 같은 약속이다.

                    ★ 이미 `images/` 로 시작한다. 화면이 CDN 을 앞에 붙일 때 이 앞머리가
                    겹치지 않게 해야 한다(프론트 `assetUrl()` 한 곳에서 처리한다)""",
                    example = "images/zzal/pets/23/motions/9/motion.webp")
            String imageKey,
            @Schema(description = "열린 시각") Instant openedAt) {

        public static Opened from(ZzalMotion motion) {
            return new Opened(motion.getSeq(), motion.getName(), labelOf(motion.getName()),
                    motion.getImageKey(), motion.getOpenedAt());
        }

        /**
         * 내부 이름을 화면에 보일 말로.
         *
         * ★ 왜 필요한가 — 동작 이름이 실험의 블록 파일명이라 "교감1_머리쓰다듬" 처럼 생겼다.
         *   갈래와 번호는 <b>우리가 자료를 정리하려고 붙인 것</b>이지 사용자가 알 바가 아니다.
         *   그대로 내보내면 화면에 내부 사정이 그대로 드러난다.
         *
         * ★ 규칙을 서버에 둔 이유 — 화면이 이 규칙을 가지면 동작 이름 짓는 법이 바뀔 때마다
         *   양쪽을 고쳐야 하고, 한쪽만 고치면 조용히 어긋난다.
         *
         * ⚠️ 지금은 앞머리를 떼고 붙은 말을 띄우는 정도다. 더 다듬을 여지가 있다.
         */
        static String labelOf(String name) {
            if (name == null || name.isBlank()) {
                return "";
            }
            int at = name.indexOf('_');
            String body = at >= 0 && at + 1 < name.length() ? name.substring(at + 1) : name;
            // "머리쓰다듬" 처럼 붙어 있는 말을 그대로 두면 읽기 나쁘지만, 여기서 억지로 띄우면
            // 동작마다 다르게 틀어진다. 사람이 정한 이름이 생기면 그것으로 대체한다.
            return body;
        }
    }

    /**
     * 도감 전체.
     *
     * ★ 총 개수를 함께 주는 이유 — 화면이 칸 수를 자기가 정하면(예전엔 프론트 상수 13개)
     *   설정에 2개만 넣어도 13칸을 그린다. 그러면 사용자는 영영 안 채워질 11칸을 본다.
     *   정본은 설정(app.zzal.motions) 하나뿐이고, 여기서 그대로 흘려보낸다.
     */
    @Schema(description = "도감 — 열린 것 목록과 다 모으면 몇 개인가")
    public record Dex(
            @Schema(description = "열린 동작들. seq 오름차순") List<Opened> opened,
            @Schema(description = "다 모으면 몇 개인가. 설정(app.zzal.motions)에 적힌 개수가 그대로 나온다. "
                    + "아직 무엇을 열지 안 정했으면 0 이고, 그때는 도감이 완전히 빈다(정상)",
                    example = "2") int total) {

        public static Dex of(List<ZzalMotion> motions, int total) {
            return new Dex(motions.stream().map(Opened::from).toList(), total);
        }
    }
}
