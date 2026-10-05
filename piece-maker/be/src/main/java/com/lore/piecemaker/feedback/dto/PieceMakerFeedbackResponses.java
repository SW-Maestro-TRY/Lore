package com.lore.piecemaker.feedback.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** 피드백 API 의 응답. 이름은 {@code PieceMaker} 로 시작한다 — 응답 레코드의 이름은 명세 전체에서 겹치지 않아야 한다. */
public final class PieceMakerFeedbackResponses {

    private PieceMakerFeedbackResponses() {
    }

    /** 저장된 피드백 하나. 보낸 독자가 누구인지는 돌려주지 않는다. */
    @Schema(name = "PieceMakerFeedback", description = "저장된 피드백 하나")
    public record Feedback(

            @Schema(description = "피드백 id", example = "3")
            long id,

            @Schema(description = "종류", allowableValues = {"ERROR_REPORT", "JUDGEMENT_REVIEW"}, example = "JUDGEMENT_REVIEW")
            String kind,

            @Schema(description = "본문. 앞뒤 빈칸을 뗀 글")
            String body,

            @Schema(description = "받은 시각(UTC)", example = "2026-10-01T09:43:00Z")
            Instant createdAt) {

        public static Feedback from(com.lore.piecemaker.feedback.PieceMakerFeedback saved) {
            return new Feedback(saved.getId(), saved.getKind().name(), saved.getBody(), saved.getCreatedAt());
        }
    }
}
