package com.lore.trailer.hypothesis.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/** 가설 API 의 요청 몸통. 검사는 서비스가 한다 — 틀린 칸마다 무엇이 틀렸는지 한국어로 말하기 위해서다. */
public final class HypothesisRequests {

    private HypothesisRequests() {
    }

    /** 가설 맡기기(2-5). 화면이 Python 서버에 보내던 몸통(screen_api.md 3-2)과 칸이 같다. 이름만 camelCase 다. */
    @Schema(name = "TrailerHypothesisSubmit", description = "가설 맡기기. 저장이 곧 맡기기다 — 서버는 PENDING 으로 두고 운영자가 판정을 넣는다")
    public record Submit(

            @Schema(description = "독자가 읽은 회차 N. 1부터 장부의 가장 뒤 회차까지", example = "200")
            Integer chapter,

            @Schema(description = "가설 제목. 180자까지. 비어도 된다")
            String title,

            @Schema(description = "독자의 주장. 6,000자까지. 비면 400")
            String claim,

            @Schema(description = "담은 카드의 번호. 하나 이상 100장까지. 겹치지 않음. 배열 순서가 근거의 순서", example = "[\"T2\", \"T374\"]")
            List<String> cards,

            @Schema(description = "카드 번호마다 독자의 해석. 4,000자까지. 담은 카드의 번호만 열쇠로 온다")
            Map<String, String> notes,

            @Schema(description = "장부 정보(cards/meta)의 stateDigest. 카드 표의 값과 다르면 400 TRAILER_DIGEST_MISMATCH")
            String stateDigest,

            @Schema(description = "장부 정보(cards/meta)의 cardsDigest. 위와 같다")
            String cardsDigest) {
    }

    /** 운영자가 판정을 넣는다(2-9). judge.py 의 출력을 그대로 싣는다 — 서버는 모양만 보고 안은 읽지 않는다. */
    @Schema(name = "TrailerHypothesisJudge", description = "운영자가 판정을 넣는다. judge.py 출력을 그대로 싣는다. 이미 판정한 가설이면 덮어쓴다")
    public record Judge(

            @Schema(description = "판정한 가설의 요청 id(2-8 의 items[].id). judge.py 의 request_id 가 아니다", example = "17")
            Long id,

            @Schema(description = "COMPLETE 또는 FAILED", allowableValues = {"COMPLETE", "FAILED"}, example = "COMPLETE")
            String judgementStatus,

            @Schema(description = "judge.py 출력의 judgement(grade · reason · support · against · cited_cards). COMPLETE 면 필수", nullable = true)
            Map<String, Object> judgement,

            @Schema(description = "편집본(editor 출력). 있을 때만", nullable = true)
            Map<String, Object> presentation,

            @Schema(description = "독자에게 보일 실패 문구. FAILED 면 필수", nullable = true)
            String failureMessage) {
    }
}
