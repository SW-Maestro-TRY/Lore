package com.lore.trailer.hypothesis.dto;

import com.lore.trailer.foreshadowing.dto.ForeshadowingResponses;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 가설 API 의 응답. 이름은 {@code Trailer} 로 시작한다 — 응답 레코드의 이름은 명세 전체에서 겹치지 않아야 한다(found.md 5-8). */
public final class HypothesisResponses {

    private HypothesisResponses() {
    }

    /** 가설 하나(2-6). 맡길 때(2-5), 되물을 때(2-6), 운영자가 판정을 넣을 때(2-9) 모두 이 모양이다. */
    @Schema(name = "TrailerHypothesis", description = "가설 하나. 맡기기 · 되묻기 · 판정 넣기의 응답이 모두 이 모양이다")
    public record Hypothesis(

            @Schema(description = "요청 id. 되묻기와 보관함의 열쇠", example = "17")
            long id,

            @Schema(description = "독자가 읽은 회차 N", example = "200")
            int chapter,

            @Schema(description = "제목. 없으면 빈 글")
            String title,

            @Schema(description = "독자의 주장")
            String claim,

            @Schema(description = "맡길 때 복사한 카드. 칸은 카드 API 의 열둘, 회수 칸은 N화로 가린 값. 순서 그대로")
            List<ForeshadowingResponses.Card> cards,

            @Schema(description = "카드 번호마다 독자의 해석. 담은 카드마다 한 칸(없으면 빈 글)")
            Map<String, String> notes,

            @Schema(description = "판정 상태", allowableValues = {"PENDING", "COMPLETE", "FAILED"}, example = "PENDING")
            String judgementStatus,

            @Schema(description = "판정. judge.py 출력 그대로(grade · reason · support · against · cited_cards). COMPLETE 일 때만", nullable = true)
            Map<String, Object> judgement,

            @Schema(description = "편집본. COMPLETE 이고 편집본이 있을 때만", nullable = true)
            Map<String, Object> presentation,

            @Schema(description = "독자에게 보일 실패 문구. FAILED 일 때만", nullable = true)
            String failureMessage,

            @Schema(description = "맡긴 때(UTC)")
            Instant createdAt,

            @Schema(description = "판정을 넣은 때(UTC). PENDING 이면 null", nullable = true)
            Instant judgedAt) {
    }

    /** 보관함의 한 줄(2-7). 목록을 그리는 데 필요한 것만 — 하나를 누르면 2-6 으로 전부 받는다. */
    @Schema(name = "TrailerHypothesisSummary", description = "보관함의 한 줄. 누르면 가설 하나(2-6)를 받는다")
    public record Summary(

            @Schema(description = "요청 id", example = "17")
            long id,

            @Schema(description = "독자가 읽은 회차 N", example = "200")
            int chapter,

            @Schema(description = "제목. 없으면 빈 글")
            String title,

            @Schema(description = "판정 상태", allowableValues = {"PENDING", "COMPLETE", "FAILED"}, example = "PENDING")
            String judgementStatus,

            @Schema(description = "맡긴 때(UTC)")
            Instant createdAt,

            @Schema(description = "판정을 넣은 때(UTC). PENDING 이면 null", nullable = true)
            Instant judgedAt) {

        public static Summary of(com.lore.trailer.hypothesis.Hypothesis h) {
            return new Summary(h.getId(), h.getChapter(), h.getTitle(), h.getJudgementStatus(), h.getCreatedAt(), h.getJudgedAt());
        }
    }

    /** 내 가설 보관함(2-7). 최신이 앞. */
    @Schema(name = "TrailerHypothesisList", description = "내 가설 보관함. 최신이 앞이다")
    public record MyList(

            @Schema(description = "맡긴 가설. 없으면 빈 배열")
            List<Summary> items) {
    }
}
