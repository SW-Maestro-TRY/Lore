package com.lore.piecemaker.feedback.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 피드백 API 의 요청 몸통. 검사는 서비스가 한다 — 틀린 칸마다 무엇이 틀렸는지 한국어로 말하기 위해서다. */
public final class PieceMakerFeedbackRequests {

    private PieceMakerFeedbackRequests() {
    }

    /** 피드백 보내기. 종류와 본문 둘이 전부다. */
    @Schema(name = "PieceMakerFeedbackCreate", description = "피드백 보내기. 로그인 없이도 보낼 수 있다")
    public record Create(

            @Schema(description = "종류. ERROR_REPORT(오류 신고) 또는 JUDGEMENT_REVIEW(판정 후기)",
                    allowableValues = {"ERROR_REPORT", "JUDGEMENT_REVIEW"}, example = "JUDGEMENT_REVIEW")
            String kind,

            @Schema(description = "본문. 2,000자까지. 비면 400", example = "인용한 카드가 제 가설과 맞지 않았어요")
            String body) {
    }
}
