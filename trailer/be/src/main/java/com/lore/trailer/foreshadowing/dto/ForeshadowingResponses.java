package com.lore.trailer.foreshadowing.dto;

import com.lore.trailer.foreshadowing.Foreshadowing;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 카드 API 셋의 응답. 명세는 NarrativeAnalysis {@code migration/front_back_protocol.md} 2-1~2-4.
 *
 * <p>★ record 마다 {@code @Schema(name = "Foreshadowing…")} 을 붙인다. API 문서는 record 의 짧은 이름을
 * 전역에서 써서 {@code Card} · {@code Page} 같은 이름은 다른 도메인과 부딪친다(SchemaNameCollisionTest 주석).
 */
public final class ForeshadowingResponses {

    private ForeshadowingResponses() {
    }

    /**
     * 카드 한 장(2-4). 목록과 상세가 같은 모양을 준다 — 목록에서 여는 상세는 서버를 다시 부르지 않는다.
     *
     * <p>칸 이름은 화면의 말이다. 표의 {@code thread_id} 가 {@code id}, {@code start_chapter} 가 {@code chapter},
     * {@code thread_kind} 가 {@code kind}, {@code thread_related_people} 가 {@code people} 이다.
     * 표의 {@code id}(DB 번호) · {@code thread_type} · {@code search_text} · 해시 둘은 나가지 않는다.
     */
    @Schema(name = "Foreshadowing", description = "복선 카드 한 장. 회수 칸 셋(status · resolvedChapter · resolution)은 독자가 읽은 회차 N 기준으로 가린 값이다")
    public record Card(

            @Schema(description = "카드 번호. 담기·상세·판정 요청의 열쇠", example = "T12")
            String id,

            @Schema(description = "복선을 심은 회차", example = "12")
            int chapter,

            @Schema(description = "유형의 한국어 이름", example = "약속")
            String kind,

            @Schema(description = "카드 제목")
            String title,

            @Schema(description = "카드 본문")
            String fact,

            @Schema(description = "인물 이름. 없으면 빈 배열")
            List<String> people,

            @Schema(description = "장부의 원문(영어)")
            String excerpt,

            @Schema(description = "연결된 장면의 번호. 없으면 null", example = "V12")
            String scene,

            @Schema(description = "장면의 글. 없으면 빈 글")
            String sceneExcerpt,

            @Schema(description = "open 또는 resolved. N화 뒤에 회수된 복선은 open 으로 온다", allowableValues = {"open", "resolved"})
            String status,

            @Schema(description = "회수된 회차. 미회수(또는 N화 뒤 회수)면 null", example = "214")
            Integer resolvedChapter,

            @Schema(description = "회수 기록의 글. 미회수(또는 N화 뒤 회수)면 null")
            String resolution) {

        /** 표의 한 줄을 N화 독자의 카드로. 회수 칸 셋은 엔티티가 N 으로 가려서 준다. */
        public static Card of(Foreshadowing f, int chapter) {
            return new Card(f.getThreadId(), f.getStartChapter(), f.getThreadKind(), f.getTitle(), f.getFact(),
                    f.people(), f.getExcerpt(), f.getScene(), f.getSceneExcerpt(),
                    f.statusAt(chapter), f.resolvedChapterAt(chapter), f.resolutionAt(chapter));
        }
    }

    /** 장부 정보(2-1). 카드와 상관없이 화면을 열 때 한 번 받는다. 회차를 받지 않는다 — 어느 회차에서 세어도 같다. */
    @Schema(name = "ForeshadowingMeta", description = "장부 정보. 화면을 열 때 한 번 받는다")
    public record Meta(

            @Schema(description = "고를 수 있는 가장 뒤 회차", example = "400")
            int maxChapter,

            @Schema(description = "400화 장부 파일의 sha256. 브라우저 저장 키와 판정 요청에 실린다")
            String stateDigest,

            @Schema(description = "400화 카드 파일의 sha256. 위와 같다")
            String cardsDigest,

            @Schema(description = "유형의 한국어 이름. 카드에 먼저 나온 순서")
            List<String> kinds,

            @Schema(description = "검색창 아래에 권하는 인물. 카드에 먼저 나온 다섯")
            List<String> suggestedPeople) {
    }

    /** 카드 목록 한 쪽(2-2). */
    @Schema(name = "ForeshadowingPage", description = "카드 목록 한 쪽. chapter · page · size 는 요청한 값 그대로다 — 늦게 온 응답을 화면이 버리는 기준")
    public record CardPage(

            @Schema(description = "요청한 회차 N", example = "200")
            int chapter,

            @Schema(description = "요청한 쪽. 0부터", example = "0")
            int page,

            @Schema(description = "요청한 크기", example = "50")
            int size,

            @Schema(description = "찾은 카드의 수(검색어와 유형을 건 뒤)", example = "12")
            long total,

            @Schema(description = "N화 카드의 전체 수(검색어와 유형을 걸기 전)", example = "774")
            long chapterTotal,

            @Schema(description = "다음 쪽이 있나")
            boolean hasNext,

            @Schema(description = "카드. 순서는 표의 id 순 = T 번호 순")
            List<Card> items) {
    }
}
