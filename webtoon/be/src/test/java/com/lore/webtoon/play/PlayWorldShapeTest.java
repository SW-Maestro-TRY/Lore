package com.lore.webtoon.play;

import static org.assertj.core.api.Assertions.assertThat;

import com.lore.webtoon.lorebook.LoreBook;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 세계관 만들기의 저장 모양 — 화면이 보내는 본문을 그대로 받고, 체험 시작에 넘길 설정집·상황을 만든다. */
class PlayWorldShapeTest {

    private static Map<String, Object> body() {
        return Map.of(
                "name", "잿빛 항구", "genre", "판타지", "one", "안개가 걷히지 않는 항구.",
                "rule", "밤에는 등대 불을 끄지 않는다.\n바다에서 이름을 부르면 대답이 온다.",
                "visibility", "public",
                "cast", List.of(Map.of("id", "a1", "name", "델", "role", "등대지기", "desc", "말수가 적다"),
                        Map.of("id", "a1", "name", "같은 id", "role", "", "desc", ""),
                        Map.of("id", "", "name", "", "role", "이름 없는 줄", "desc", "")),
                "events", List.of(
                        Map.of("id", "local1", "title", "첫 밤", "line", "", "situation", "주인공이 등대에 도착한다.\n둘째 줄",
                                "place", "등대", "spot", "새 등대지기", "cast", List.of("a1", "없는id"),
                                "examples", List.of("p", "q", "r")),
                        Map.of("id", "local2", "title", "", "situation", ""),
                        Map.of("id", "w1-e7", "title", "이미 있던 사건", "situation", "다시 저장한 사건")),
                "examples", List.of("x", "y", "z"));
    }

    @Test
    void 저장_본문을_화면_모양_그대로_받고_사건_id_를_세계에_묶는다() {
        Map<String, Object> doc = PlayWorldService.cleanDoc(body(), "w1");
        List<?> events = (List<?>) doc.get("events");
        assertThat(events).hasSize(2);                                   // 상황이 빈 사건은 뺀다
        Map<?, ?> first = (Map<?, ?>) events.get(0);
        assertThat(first.get("id")).isEqualTo("w1-e1");
        assertThat(first.get("no")).isEqualTo("EP 01");
        assertThat(first.get("line")).isEqualTo("주인공이 등대에 도착한다.");  // 한 줄이 비면 상황 첫 줄
        assertThat(first.get("cast")).isEqualTo(List.of("a1"));          // 없는 인물은 뺀다
        assertThat(((Map<?, ?>) events.get(1)).get("id")).isEqualTo("w1-e7");   // 이미 묶인 id 는 그대로
        List<?> cast = (List<?>) doc.get("cast");
        assertThat(cast).hasSize(2);
        assertThat(((Map<?, ?>) cast.get(1)).get("id")).isEqualTo("c1");  // 겹친 id 는 새로
        assertThat(doc.get("visibility")).isEqualTo("public");
        assertThat(first.get("examples")).isEqualTo(List.of("p", "q", "r"));  // 둘로 줄이는 것은 내 캐릭터인지 본 뒤(서비스)
        assertThat(PlayWorldService.allExamples(doc)).containsExactly("x", "y", "z", "p", "q", "r");
    }

    @Test
    void 체험_설정집은_규칙_줄과_인물로_만들고_고른_설정집을_뒤에_붙인다() {
        Map<String, Object> doc = PlayWorldService.cleanDoc(body(), "w1");
        Map<String, Object> mine = Map.of("name", "내 설정집", "source", "manual", "fixed_settings", "고정",
                "progress_state", "", "entries", List.of(), "confirmed", true);
        Map<String, Object> book = PlayWorldService.lorebook(doc, List.of(mine));
        List<?> books = (List<?>) book.get("books");
        assertThat(books).hasSize(2);
        List<?> entries = (List<?>) ((Map<?, ?>) books.get(0)).get("entries");
        assertThat(entries).hasSize(4);                                  // 규칙 둘 + 인물 둘
        assertThat(((Map<?, ?>) entries.get(0)).get("always")).isEqualTo(true);
        assertThat(((Map<?, ?>) entries.get(2)).get("content")).isEqualTo("등대지기 — 말수가 적다");
        assertThat(LoreBook.clean(book, true)).isNotEmpty();               // 웹툰 만들기가 받는 모양이다
    }

    @Test
    void 체험_상황에는_장소_자리_나오는_인물이_붙는다() {
        Map<String, Object> doc = PlayWorldService.cleanDoc(body(), "w1");
        Map<?, ?> first = (Map<?, ?>) ((List<?>) doc.get("events")).get(0);
        String s = PlayWorldService.situation(first, Map.of("a1", "델"));
        assertThat(s).startsWith("주인공이 등대에 도착한다.")
                .contains("장소: 등대").contains("주인공의 자리: 새 등대지기").contains("이 사건에 나오는 인물: 델");
    }

    @Test
    void AI_가_지은_것은_빈_칸만_채우고_사용자가_정한_사건_인물_고르기는_남긴다() {
        Map<String, Object> draft = PlayWorldService.cleanDoc(body(), "w1");
        Map<String, Object> made = Map.of("name", "잿빛 항구", "genre", "판타지", "one", "소개",
                "rules", List.of("규칙 하나"), "spot", "세계 자리",
                "cast", List.of(Map.of("name", "델", "role", "등대지기", "desc", "말수가 적다"),
                        Map.of("name", "새 인물", "role", "선장", "desc", "웃는다")),
                "events", List.of(Map.of("title", "첫 밤", "line", "한 줄", "place", "등대", "situation", "주인공이 등대에 도착한다.", "spot", ""),
                        Map.of("title", "둘째", "line", "둘", "place", "부두", "situation", "새 상황", "spot", "선원")));
        Map<String, Object> doc = PlayWorldService.merge(draft, made, "w1");
        List<?> cast = (List<?>) doc.get("cast");
        assertThat(((Map<?, ?>) cast.get(0)).get("id")).isEqualTo("a1");  // 같은 이름은 원래 id
        List<?> events = (List<?>) doc.get("events");
        Map<?, ?> first = (Map<?, ?>) events.get(0);
        assertThat(first.get("id")).isEqualTo("w1-e1");
        assertThat(first.get("cast")).isEqualTo(List.of("a1"));
        assertThat(first.get("examples")).isEqualTo(List.of("p", "q", "r"));  // 사용자가 고른 예시는 남는다
        assertThat(first.get("spot")).isEqualTo("새 등대지기");             // 자리는 사용자가 적은 것만
        assertThat(doc.get("rule")).isEqualTo("규칙 하나");
    }
}
