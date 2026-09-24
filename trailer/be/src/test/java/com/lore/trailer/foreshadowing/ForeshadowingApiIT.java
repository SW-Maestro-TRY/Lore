package com.lore.trailer.foreshadowing;

import com.fasterxml.jackson.databind.JsonNode;
import com.lore.trailer.support.TrailerIntegrationTest;
import com.lore.trailer.support.TrailerItSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 카드 API 셋을 표본 25장으로 두드린다 — 화면 검사 자료와 같은 카드다.
 *
 * <h3>★ 기대값은 자료를 세어 적었다(NA migration/steps.md 16단계)</h3>
 * 1화 9장(T5 · T7 가림) · 3화 18장(9장 가림) · 100화 22장(T378 가림). 회수 회차가 N 보다 크면 가린다 —
 * T10 은 3화에 회수돼 3화 독자에게는 <b>보인다</b>(경계). "Kuina" 는 T15 의 회수 기록에만 있어 검색으로
 * 나오면 가린 결말이 새는 것이다.
 *
 * <h3>★ 열두 칸은 화면 검사 자료와 글자 하나까지 같아야 한다</h3>
 * 표본 SQL 과 검사 자료는 같은 스크립트 계열(NA {@code display_cards()})에서 나왔다. 400화 독자에게는
 * 가릴 것이 없으므로, 400화 응답과 검사 자료가 다르면 SQL → 엔티티 → 응답 어딘가에서 칸이 틀어진 것이다.
 */
@TrailerIntegrationTest
@DisplayName("카드 API — 표본 25장으로 회차 거르기 · 가리기 · 검색 · 나눠 주기")
class ForeshadowingApiIT extends TrailerItSupport {

    private static final String CARDS = "/api/trailer/v1/public/cards";
    private static final String META = CARDS + "/meta";

    /** 카드 한 장의 칸 열둘(front_back_protocol.md 2-4). 응답 이름 → 검사 자료 이름. */
    private static final Map<String, String> TWELVE_FIELDS = new LinkedHashMap<>();

    static {
        TWELVE_FIELDS.put("id", "id");
        TWELVE_FIELDS.put("chapter", "chapter");
        TWELVE_FIELDS.put("kind", "kind");
        TWELVE_FIELDS.put("title", "title");
        TWELVE_FIELDS.put("fact", "fact");
        TWELVE_FIELDS.put("people", "people");
        TWELVE_FIELDS.put("excerpt", "excerpt");
        TWELVE_FIELDS.put("scene", "scene");
        TWELVE_FIELDS.put("sceneExcerpt", "scene_excerpt");
        TWELVE_FIELDS.put("status", "status");
        TWELVE_FIELDS.put("resolvedChapter", "resolved_chapter");
        TWELVE_FIELDS.put("resolution", "resolution");
    }

    // ── 도움 ──────────────────────────────────────────────────────────────

    private JsonNode page(String query) throws Exception {
        return data(getAnonymously(CARDS + "?" + query));
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(item.path("id").asText()));
        return ids;
    }

    private static Map<String, JsonNode> byId(JsonNode page) {
        Map<String, JsonNode> map = new LinkedHashMap<>();
        page.path("items").forEach(item -> map.put(item.path("id").asText(), item));
        return map;
    }

    private static int threadNo(String id) {
        return Integer.parseInt(id.substring(1));
    }

    private static void assertHidden(JsonNode card) {
        assertThat(card.path("status").asText()).as("%s 상태", card.path("id").asText()).isEqualTo("open");
        assertThat(card.path("resolvedChapter").isNull()).as("%s 회수 회차", card.path("id").asText()).isTrue();
        assertThat(card.path("resolution").isNull()).as("%s 회수 기록", card.path("id").asText()).isTrue();
    }

    private static void assertResolved(JsonNode card, int resolvedChapter) {
        assertThat(card.path("status").asText()).isEqualTo("resolved");
        assertThat(card.path("resolvedChapter").asInt()).isEqualTo(resolvedChapter);
        assertThat(card.path("resolution").asText()).isNotBlank();
    }

    // ── 1. 회차로 거르고 가린다 ───────────────────────────────────────────

    @Nested
    @DisplayName("회차 N — N화 이하만 주고 N화 뒤의 회수를 가린다")
    class Chapter {

        @Test
        @DisplayName("★★ 1화는 9장 — T5(66화 회수) · T7(96화 회수)이 미회수로 온다")
        void chapterOne() throws Exception {
            JsonNode page = page("chapter=1&size=100");

            assertThat(page.path("total").asLong()).isEqualTo(9);
            assertThat(page.path("chapterTotal").asLong()).isEqualTo(9);
            assertThat(page.path("hasNext").asBoolean()).isFalse();
            assertThat(ids(page)).containsExactly("T1", "T2", "T3", "T4", "T5", "T6", "T7", "T8", "T9");

            Map<String, JsonNode> cards = byId(page);
            assertHidden(cards.get("T5"));
            assertHidden(cards.get("T7"));
            assertThat(cards.get("T1").path("status").asText()).isEqualTo("open");
        }

        @Test
        @DisplayName("★★ 3화는 18장, 9장을 가린다 — 3화에 회수된 T10 은 경계라서 보인다")
        void chapterThree() throws Exception {
            JsonNode page = page("chapter=3&size=100");

            assertThat(page.path("total").asLong()).isEqualTo(18);
            Map<String, JsonNode> cards = byId(page);
            for (String hidden : List.of("T5", "T7", "T11", "T13", "T14", "T15", "T16", "T17", "T18")) {
                assertHidden(cards.get(hidden));
            }
            assertResolved(cards.get("T10"), 3);
            long hiddenCount = cards.values().stream()
                    .filter(c -> "open".equals(c.path("status").asText()))
                    .count();
            // 400화 기준 미회수는 T1 · T2 · T3 · T4 · T6 · T8 · T9 · T12 여덟이다. 나머지 open 아홉이 가린 것이다.
            assertThat(hiddenCount - 8).isEqualTo(9);
        }

        @Test
        @DisplayName("★ 100화는 22장 — 108화에 회수된 T378 만 가린다")
        void chapterHundred() throws Exception {
            JsonNode page = page("chapter=100&size=100");

            assertThat(page.path("total").asLong()).isEqualTo(22);
            Map<String, JsonNode> cards = byId(page);
            assertHidden(cards.get("T378"));
            assertResolved(cards.get("T34"), 94);
            assertResolved(cards.get("T13"), 96);
            assertThat(cards).doesNotContainKeys("T467", "T1575", "T1648");
        }

        @Test
        @DisplayName("★★ 400화의 카드 25장은 열두 칸이 화면 검사 자료와 글자 하나까지 같다")
        void chapter400MatchesTheFixture() throws Exception {
            Map<String, JsonNode> actual = byId(page("chapter=400&size=100"));
            JsonNode fixture = sampleFixtureCards();
            assertThat(fixture.size()).isEqualTo(25);
            assertThat(actual).hasSize(25);

            for (JsonNode expected : fixture) {
                String id = expected.path("id").asText();
                JsonNode card = actual.get(id);
                assertThat(card).as("%s 가 응답에 있다", id).isNotNull();
                TWELVE_FIELDS.forEach((ours, theirs) -> {
                    JsonNode want = expected.path(theirs);
                    JsonNode got = card.path(ours);
                    if (theirs.equals("scene_excerpt")) {
                        // 검사 자료는 장면이 없으면 null 이고 표는 빈 글이다(NOT NULL DEFAULT '').
                        assertThat(got.asText("")).as("%s.%s", id, ours).isEqualTo(want.asText(""));
                    } else {
                        assertThat(got).as("%s.%s", id, ours).isEqualTo(want);
                    }
                });
                // 응답에 나가지 않는 칸 — 표의 번호와 유형, 검색용 글, 해시.
                assertThat(card.has("type")).isFalse();
                assertThat(card.has("searchText")).isFalse();
                assertThat(card.has("stateDigest")).isFalse();
            }
        }

        @Test
        @DisplayName("★ 뒤 회차의 카드는 번호로 찾아도 없다 — T374 는 96화에 심었다")
        void laterCardsAreAbsent() throws Exception {
            assertThat(page("chapter=1&search=T374").path("total").asLong()).isZero();
            assertThat(page("chapter=95&search=T374").path("total").asLong()).isZero();
            assertThat(ids(page("chapter=96&search=T374"))).containsExactly("T374");
        }
    }

    // ── 2. 순서와 나눠 주기 ───────────────────────────────────────────────

    @Nested
    @DisplayName("순서와 쪽")
    class Paging {

        @Test
        @DisplayName("★ 순서는 T 번호 순 — T2 가 T10 앞에 온다(글자 순이면 T10 이 앞이다)")
        void orderedByNumberNotByText() throws Exception {
            List<String> ids = ids(page("chapter=400&size=100"));

            assertThat(ids).hasSize(25);
            assertThat(ids.indexOf("T2")).isLessThan(ids.indexOf("T10"));
            assertThat(ids).isSortedAccordingTo(Comparator.comparingInt(ForeshadowingApiIT::threadNo));
        }

        @Test
        @DisplayName("★ 10장씩 — 첫 쪽은 hasNext, 셋째 쪽은 5장으로 끝, 넷째 쪽은 빈 목록")
        void pages() throws Exception {
            JsonNode first = page("chapter=400&size=10&page=0");
            assertThat(first.path("chapter").asInt()).isEqualTo(400);
            assertThat(first.path("page").asInt()).isZero();
            assertThat(first.path("size").asInt()).isEqualTo(10);
            assertThat(first.path("total").asLong()).isEqualTo(25);
            assertThat(first.path("chapterTotal").asLong()).isEqualTo(25);
            assertThat(first.path("hasNext").asBoolean()).isTrue();
            assertThat(ids(first)).containsExactly("T1", "T2", "T3", "T4", "T5", "T6", "T7", "T8", "T9", "T10");

            JsonNode third = page("chapter=400&size=10&page=2");
            assertThat(third.path("page").asInt()).isEqualTo(2);
            assertThat(third.path("hasNext").asBoolean()).isFalse();
            assertThat(ids(third)).containsExactly("T374", "T378", "T467", "T1575", "T1648");

            JsonNode fourth = page("chapter=400&size=10&page=3");
            assertThat(fourth.path("items")).isEmpty();
            assertThat(fourth.path("hasNext").asBoolean()).isFalse();
            assertThat(fourth.path("total").asLong()).isEqualTo(25);
        }

        @Test
        @DisplayName("size 를 안 주면 50 — 응답이 실제로 쓴 값을 되돌려 준다")
        void defaultSize() throws Exception {
            JsonNode page = page("chapter=400");

            assertThat(page.path("size").asInt()).isEqualTo(50);
            assertThat(page.path("page").asInt()).isZero();
            assertThat(page.path("items")).hasSize(25);
        }
    }

    // ── 3. 검색 ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("검색 — 화면이 브라우저에서 찾던 규칙 그대로")
    class Search {

        @Test
        @DisplayName("T 번호는 그 한 장 — 소문자 t2 도 T2 다")
        void byThreadId() throws Exception {
            assertThat(ids(page("chapter=400&search=T2"))).containsExactly("T2");
            assertThat(ids(page("chapter=400&search=t2"))).containsExactly("T2");
            assertThat(page("chapter=400&search=T2").path("total").asLong()).isEqualTo(1);
        }

        @Test
        @DisplayName("★ 단어는 모두 들어 있어야 한다 — 대소문자·빈칸 수는 상관없다")
        void allWordsMustMatch() throws Exception {
            assertThat(ids(page("chapter=400&search=luffy shanks"))).containsExactly("T2", "T3", "T374");
            assertThat(ids(page("chapter=400&search=  Shanks   LUFFY "))).containsExactly("T2", "T3", "T374");
            assertThat(ids(page("chapter=400&search=koby"))).containsExactly("T11", "T12", "T16", "T17");
        }

        @Test
        @DisplayName("\"1화\" 로 1화에 심은 카드를 찾는다 — 회차도 검색용 글에 들어 있다")
        void byChapterWord() throws Exception {
            assertThat(ids(page("chapter=400&search=1화")))
                    .containsExactly("T1", "T2", "T3", "T4", "T5", "T6", "T7", "T8", "T9");
        }

        @Test
        @DisplayName("유형은 똑같은 카드만 — 검색어와 함께 건다")
        void kindFilter() throws Exception {
            assertThat(ids(page("chapter=3&kind=약속"))).containsExactly("T2", "T3", "T11", "T14");
            assertThat(ids(page("chapter=400&kind=약속&search=koby"))).containsExactly("T11");
            assertThat(page("chapter=400&kind=없는 유형").path("total").asLong()).isZero();
        }

        @Test
        @DisplayName("★★ % 와 _ 는 글자 그대로 — 와일드카드로 새면 한 글자가 25장을 다 찾는다")
        void wildcardsAreLiteral() throws Exception {
            assertThat(page("chapter=400&search=%").path("total").asLong()).isZero();
            assertThat(page("chapter=400&search=_").path("total").asLong()).isZero();
            assertThat(page("chapter=400&search=t%").path("total").asLong()).isZero();
        }

        @Test
        @DisplayName("★★ 회수 기록은 검색하지 못한다 — \"Kuina\" 는 T15 의 회수 기록에만 있다")
        void resolutionIsNotSearchable() throws Exception {
            // 400화 독자에게는 회수 기록이 보이는데도 검색으로는 찾지 못한다 — 검색용 글에 넣지 않았다.
            assertThat(page("chapter=400&search=Kuina").path("total").asLong()).isZero();
            assertThat(page("chapter=3&search=kuina").path("total").asLong()).isZero();
        }

        @Test
        @DisplayName("chapterTotal 은 검색 전의 수 — \"12개 · 400화 장부\" 와 \"복선 25개\" 가 따로 온다")
        void chapterTotalIgnoresTheSearch() throws Exception {
            JsonNode page = page("chapter=400&search=koby");

            assertThat(page.path("total").asLong()).isEqualTo(4);
            assertThat(page.path("chapterTotal").asLong()).isEqualTo(25);
        }
    }

    // ── 4. 잘못된 인자는 400 ──────────────────────────────────────────────

    @Nested
    @DisplayName("잘못된 인자 — 500 이 아니라 400")
    class BadRequests {

        @Test
        @DisplayName("★★ 회차 0 · 401 · 글자 · 없음은 400 TRAILER_INVALID_CHAPTER")
        void invalidChapter() throws Exception {
            for (String query : List.of("chapter=0", "chapter=401", "chapter=abc", "chapter=", "size=10")) {
                MvcResult result = getAnonymously(CARDS + "?" + query);
                assertThat(status(result)).as(query).isEqualTo(400);
                assertThat(errorCode(result)).as(query).isEqualTo("TRAILER_INVALID_CHAPTER");
            }
        }

        @Test
        @DisplayName("쪽 · 크기 · 검색어 상한을 넘으면 400 INVALID_INPUT — 회차 코드가 아니다")
        void invalidPageSizeSearch() throws Exception {
            String tooLong = "a".repeat(SearchTerms.MAX_LENGTH + 1);
            String tooMany = String.join(" ", java.util.Collections.nCopies(SearchTerms.MAX_WORDS + 1, "w"));
            for (String query : List.of("chapter=1&page=-1", "chapter=1&page=x", "chapter=1&size=0",
                    "chapter=1&size=101", "chapter=1&size=abc",
                    "chapter=1&search=" + tooLong, "chapter=1&search=" + tooMany)) {
                MvcResult result = getAnonymously(CARDS + "?" + query);
                assertThat(status(result)).as(query).isEqualTo(400);
                assertThat(errorCode(result)).as(query).isEqualTo("INVALID_INPUT");
            }
        }

        @Test
        @DisplayName("상한 안은 통과한다 — 200자 · 단어 10개 · size 100")
        void limitsAreInclusive() throws Exception {
            String exact = "a".repeat(SearchTerms.MAX_LENGTH);
            String ten = String.join(" ", java.util.Collections.nCopies(SearchTerms.MAX_WORDS, "w"));

            assertThat(status(getAnonymously(CARDS + "?chapter=1&search=" + exact))).isEqualTo(200);
            assertThat(status(getAnonymously(CARDS + "?chapter=1&search=" + ten))).isEqualTo(200);
            assertThat(status(getAnonymously(CARDS + "?chapter=1&size=100"))).isEqualTo(200);
        }
    }

    // ── 5. 장부 정보 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("★ 장부 정보 — 가장 뒤 회차 400, 해시 둘은 검사 자료와 같고, 유형과 인물은 카드에 먼저 나온 순서")
    void meta() throws Exception {
        JsonNode meta = data(getAnonymously(META));
        JsonNode fixture = json.readTree(java.nio.file.Files.readString(repoRoot().resolve(SAMPLE_FIXTURE)));

        assertThat(meta.path("maxChapter").asInt()).isEqualTo(400);
        assertThat(meta.path("stateDigest").asText()).isEqualTo(fixture.path("state_digest").asText());
        assertThat(meta.path("cardsDigest").asText()).isEqualTo(fixture.path("cards_digest").asText());
        assertThat(meta.path("stateDigest").asText()).hasSize(64);

        List<String> kinds = new ArrayList<>();
        meta.path("kinds").forEach(k -> kinds.add(k.asText()));
        assertThat(kinds).containsExactly("수수께끼", "약속", "설명 없는 능력", "체호프의 총", "언급만 됨");

        List<String> people = new ArrayList<>();
        meta.path("suggestedPeople").forEach(p -> people.add(p.asText()));
        assertThat(people).containsExactly("Gol D. Roger", "Shanks", "Monkey D. Luffy", "Lord of the Coast", "Benn Beckman");
        // 판정 1회의 값. 화면이 단추 옆에 보인다(TrailerCreditPolicy 기본 5).
        assertThat(meta.path("judgeCredits").asInt()).isEqualTo(5);
    }

    // ── 6. 상세 ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("상세 — 한 장을 번호로")
    class Detail {

        @Test
        @DisplayName("★ T5 는 1화 독자에게 미회수, 66화 독자부터 회수됨")
        void maskedLikeTheList() throws Exception {
            JsonNode atOne = data(getAnonymously(CARDS + "/T5?chapter=1"));
            assertThat(atOne.path("id").asText()).isEqualTo("T5");
            assertThat(atOne.path("chapter").asInt()).isEqualTo(1);
            assertThat(atOne.path("kind").asText()).isEqualTo("체호프의 총");
            assertHidden(atOne);

            assertHidden(data(getAnonymously(CARDS + "/T5?chapter=65")));
            assertResolved(data(getAnonymously(CARDS + "/T5?chapter=66")), 66);
            assertResolved(data(getAnonymously(CARDS + "/T5?chapter=400")), 66);
        }

        @Test
        @DisplayName("★★ 모르는 번호와 N화 뒤에 심은 카드는 같은 404 TRAILER_CARD_NOT_FOUND")
        void notFound() throws Exception {
            for (String path : List.of("/T374?chapter=1", "/T374?chapter=95", "/T9999?chapter=400", "/t5?chapter=400")) {
                MvcResult result = getAnonymously(CARDS + path);
                assertThat(status(result)).as(path).isEqualTo(404);
                assertThat(errorCode(result)).as(path).isEqualTo("TRAILER_CARD_NOT_FOUND");
            }
            assertThat(status(getAnonymously(CARDS + "/T374?chapter=96"))).isEqualTo(200);
        }

        @Test
        @DisplayName("회차가 틀리면 상세도 400 TRAILER_INVALID_CHAPTER — 카드를 찾기 전에 본다")
        void invalidChapter() throws Exception {
            for (String path : List.of("/T5?chapter=abc", "/T5?chapter=0", "/T5?chapter=401", "/T5")) {
                MvcResult result = getAnonymously(CARDS + path);
                assertThat(status(result)).as(path).isEqualTo(400);
                assertThat(errorCode(result)).as(path).isEqualTo("TRAILER_INVALID_CHAPTER");
            }
        }
    }

    // ── 7. 표가 비었으면 503 ──────────────────────────────────────────────

    @Test
    @DisplayName("★★ 카드 표가 비어 있으면 셋 모두 503 TRAILER_LEDGER_NOT_LOADED — 운영 DB 에 SQL 을 넣기 전")
    void emptyTableIsServiceUnavailable() throws Exception {
        truncate();
        assertThat(count()).isZero();

        for (String path : List.of(META, CARDS + "?chapter=1", CARDS + "/T1?chapter=1")) {
            MvcResult result = getAnonymously(path);
            assertThat(status(result)).as(path).isEqualTo(503);
            assertThat(errorCode(result)).as(path).isEqualTo("TRAILER_LEDGER_NOT_LOADED");
        }
    }

    // ── 8. 로그인 없이 ────────────────────────────────────────────────────

    @Test
    @DisplayName("★ 로그인 없이 GET 은 200 — GET 이 아니면 문이 열려 있지 않다(401)")
    void openForGetOnly() throws Exception {
        assertThat(status(getAnonymously(META))).isEqualTo(200);
        assertThat(status(getAnonymously(CARDS + "?chapter=1"))).isEqualTo(200);
        assertThat(status(getAnonymously(CARDS + "/T1?chapter=1"))).isEqualTo(200);

        int posted = mockMvc.perform(post(CARDS + "/meta")).andReturn().getResponse().getStatus();
        assertThat(posted).as("public 아래라도 GET 만 열려 있다").isIn(401, 403);
    }
}
