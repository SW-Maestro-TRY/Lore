package com.lore.zzal.feedback.dto;

import com.lore.zzal.feedback.ZzalFeedback;
import com.lore.zzal.feedback.dto.FeedbackRequests.Tag;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** 후기 API 가 돌려주는 것들. */
public final class FeedbackResponses {

    private FeedbackResponses() {
    }

    /**
     * 이 펫에 남긴 후기. <b>낸 뒤와 조회가 같은 모양</b>이다.
     *
     * ★ 두 API 가 같은 모양인 것은 화면을 위해서다. 방금 낸 사람과 새로고침으로 들어온 사람이
     *   서로 다른 응답을 받으면, 화면이 "지금 어느 쪽이지" 를 판단하게 된다
     *   (미니게임의 {@code GameResponses.State} 와 같은 규칙).
     *
     * <h3>★ 보상 칸이 없다</h3>
     * 무엇을 줄지 아직 안 정해졌다(2026-09-03). 지금 "무엇을 드립니다" 를 담을 칸을 만들어 두면
     * 화면이 그 자리에 문구를 넣게 되고, 실제로는 아무것도 안 나가므로 <b>지키지 않는 약속</b>이 된다.
     */
    @Schema(description = "이 펫에 남긴 후기. 아직 안 냈으면 submitted 가 false 이고 나머지가 비어 있다")
    public record Submitted(

            @Schema(description = "냈는가. false 면 아래 칸이 전부 비어 있다") boolean submitted,

            @Schema(description = "별점 1~5", example = "4") Integer rating,

            @Schema(description = "고른 칩들. 안 골랐으면 빈 배열") List<Tag> tags,

            @Schema(description = "자유롭게 쓴 말. 안 썼으면 null") String text,

            @Schema(description = "낸 시각(ISO-8601)") Instant createdAt) {

        public static Submitted of(ZzalFeedback f) {
            return new Submitted(true, f.getRating(), parseTags(f.getTags()), f.getText(), f.getCreatedAt());
        }

        /** 아직 안 낸 상태. 화면은 이걸 보고 후기 칸을 그린다. */
        public static Submitted none() {
            return new Submitted(false, null, List.of(), null, null);
        }

        /**
         * 저장된 문자열을 다시 칩으로.
         *
         * ★ 모르는 값은 <b>버린다.</b> 칩 목록에서 무엇을 빼는 날, 이미 그 값으로 저장된 행이
         *   남아 있는데 여기서 예외가 나면 <b>그 사람은 자기 후기를 영영 못 본다.</b>
         *   빠진 칩 하나 때문에 조회 전체가 막히는 쪽이 훨씬 나쁘다.
         */
        private static List<Tag> parseTags(String stored) {
            if (stored == null || stored.isBlank()) {
                return List.of();
            }
            return Arrays.stream(stored.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Submitted::toTagOrNull)
                    .filter(t -> t != null)
                    .toList();
        }

        private static Tag toTagOrNull(String name) {
            try {
                return Tag.valueOf(name);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }
}
