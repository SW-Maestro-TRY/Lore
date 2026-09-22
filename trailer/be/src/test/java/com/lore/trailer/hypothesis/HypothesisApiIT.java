package com.lore.trailer.hypothesis;

import com.fasterxml.jackson.databind.JsonNode;
import com.lore.trailer.support.TrailerIntegrationTest;
import com.lore.trailer.support.TrailerItSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 가설 API 의 통합 검사. 표본 25장 위에서 맡기기(2-5)와 하나 보기(2-6)를 본다 — 저장 · 복사 · 가리기 · 검사 문구 · 되묻기.
 * 로그인은 {@code asUser} 로 넣는다(JWT 필터가 넣는 것과 같은 모양).
 */
@TrailerIntegrationTest
@DisplayName("가설 API — 맡기기(2-5) · 하나 보기(2-6)")
class HypothesisApiIT extends TrailerItSupport {

    private static final String CARDS = "/api/trailer/v1/public/cards";
    private static final String HYPOTHESES = "/api/trailer/v1/hypotheses";
    private static final String CLAIM = "샹크스와 루피는 다시 만난다.";

    private JsonNode meta() throws Exception {
        return data(getAnonymously(CARDS + "/meta"));
    }

    /** 표본의 해시 둘을 실은 맡기기 몸통. 화면이 보내는 모양(camelCase)이다. */
    private Map<String, Object> submission(int chapter, List<String> cards) throws Exception {
        JsonNode meta = meta();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chapter", chapter);
        body.put("title", "샹크스의 약속");
        body.put("claim", CLAIM);
        body.put("cards", cards);
        body.put("notes", Map.of());
        body.put("stateDigest", meta.path("stateDigest").asText());
        body.put("cardsDigest", meta.path("cardsDigest").asText());
        return body;
    }

    private static List<String> ids(JsonNode cards) {
        List<String> ids = new ArrayList<>();
        cards.forEach(card -> ids.add(card.path("id").asText()));
        return ids;
    }

    @Test
    @DisplayName("로그인 없이 맡기면 401 — 카드 조회와 달리 가설은 로그인이 있어야 한다")
    void anonymousIs401() throws Exception {
        assertThat(status(postJson(null, HYPOTHESES, submission(400, List.of("T2"))))).isEqualTo(401);
        assertThat(count("hypotheses")).isZero();
    }

    @Test
    @DisplayName("맡기면 PENDING 으로 저장되고, 담은 카드가 그 회차의 값으로 순서대로 복사된다")
    void submitStoresPendingWithCopiedCards() throws Exception {
        Long user = newUserId();
        Map<String, Object> body = submission(400, List.of("T374", "T2"));
        body.put("notes", Map.of("T2", "약속의 밀짚모자"));

        MvcResult result = postJson(user, HYPOTHESES, body);

        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode h = data(result);
        assertThat(h.path("id").asLong()).isPositive();
        assertThat(h.path("judgementStatus").asText()).isEqualTo("PENDING");
        assertThat(h.path("chapter").asInt()).isEqualTo(400);
        assertThat(h.path("title").asText()).isEqualTo("샹크스의 약속");
        assertThat(h.path("claim").asText()).isEqualTo(CLAIM);
        assertThat(h.path("judgement").isNull()).isTrue();
        assertThat(h.path("presentation").isNull()).isTrue();
        assertThat(h.path("failureMessage").isNull()).isTrue();
        assertThat(h.path("judgedAt").isNull()).isTrue();
        assertThat(h.path("createdAt").asText()).isNotEmpty();
        // 카드는 보낸 순서 그대로, 칸은 카드 상세 API(같은 회차)와 같다 — 복사본이 곧 그 회차의 카드다.
        assertThat(ids(h.path("cards"))).containsExactly("T374", "T2");
        for (JsonNode card : h.path("cards")) {
            JsonNode detail = data(getAnonymously(CARDS + "/" + card.path("id").asText() + "?chapter=400"));
            assertThat(card).as(card.path("id").asText()).isEqualTo(detail);
        }
        // 해석은 담은 카드마다 한 칸이다. 안 적은 카드는 빈 글.
        assertThat(h.path("notes").path("T2").asText()).isEqualTo("약속의 밀짚모자");
        assertThat(h.path("notes").path("T374").asText()).isEmpty();
        assertThat(h.path("notes").size()).isEqualTo(2);
        // 표에도 그대로 있다.
        assertThat(count("hypotheses")).isEqualTo(1);
        assertThat(count("hypothesis_foreshadowing")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select user_id from hypotheses", Long.class)).isEqualTo(user);
        assertThat(jdbc.queryForObject("select judgement_status from hypotheses", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForList("select thread_id from hypothesis_foreshadowing order by card_position", String.class))
                .containsExactly("T374", "T2");
    }

    @Test
    @DisplayName("★ 1화 독자가 담은 T5 는 미회수로 복사된다(400화 독자에게는 66화 회수) — 복사본은 그 회차로 가린 값")
    void copiesMaskedByChapter() throws Exception {
        Long user = newUserId();

        JsonNode early = data(postJson(user, HYPOTHESES, submission(1, List.of("T5")))).path("cards").get(0);
        assertThat(early.path("status").asText()).isEqualTo("open");
        assertThat(early.path("resolvedChapter").isNull()).isTrue();
        assertThat(early.path("resolution").isNull()).isTrue();

        JsonNode late = data(postJson(user, HYPOTHESES, submission(400, List.of("T5")))).path("cards").get(0);
        assertThat(late.path("status").asText()).isEqualTo("resolved");
        assertThat(late.path("resolvedChapter").asInt()).isEqualTo(66);
        assertThat(late.path("resolution").asText()).isNotEmpty();

        assertThat(jdbc.queryForList("select status from hypothesis_foreshadowing order by id", String.class))
                .containsExactly("open", "resolved");
    }

    @Test
    @DisplayName("카드 표가 비어 있으면 503 TRAILER_LEDGER_NOT_LOADED — 맡길 수 없다")
    void emptyLedgerIs503() throws Exception {
        Long user = newUserId();
        Map<String, Object> body = submission(400, List.of("T2"));
        truncate();

        MvcResult result = postJson(user, HYPOTHESES, body);

        assertThat(status(result)).isEqualTo(503);
        assertThat(errorCode(result)).isEqualTo("TRAILER_LEDGER_NOT_LOADED");
        assertThat(count("hypotheses")).isZero();
    }

    private long count(String table) {
        Long n = jdbc.queryForObject("select count(*) from " + table, Long.class);
        return n == null ? 0 : n;
    }

    @Nested
    @DisplayName("하나 보기(2-6) — 내 것만, 판정 칸은 상태대로")
    class GetOne {

        private long submitAs(Long user) throws Exception {
            return data(postJson(user, HYPOTHESES, submission(400, List.of("T2", "T374")))).path("id").asLong();
        }

        @Test
        @DisplayName("맡긴 직후 되물으면 맡길 때 받은 것과 같은 모양이고 PENDING 이다")
        void getReturnsSameShapeAsSubmit() throws Exception {
            Long user = newUserId();
            JsonNode submitted = data(postJson(user, HYPOTHESES, submission(400, List.of("T2", "T374"))));

            MvcResult result = getAs(user, HYPOTHESES + "/" + submitted.path("id").asLong());

            assertThat(status(result)).isEqualTo(200);
            assertThat(data(result)).isEqualTo(submitted);
            assertThat(data(result).path("judgementStatus").asText()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("★ 남의 가설과 없는 번호와 숫자 아닌 번호는 모두 같은 404 TRAILER_HYPOTHESIS_NOT_FOUND")
        void othersAndUnknownAre404() throws Exception {
            Long owner = newUserId();
            Long stranger = newUserId();
            long id = submitAs(owner);

            for (String path : List.of(HYPOTHESES + "/" + id, HYPOTHESES + "/999999", HYPOTHESES + "/abc", HYPOTHESES + "/0")) {
                MvcResult result = getAs(stranger, path);
                assertThat(status(result)).as(path).isEqualTo(404);
                assertThat(errorCode(result)).as(path).isEqualTo("TRAILER_HYPOTHESIS_NOT_FOUND");
            }
            assertThat(status(getAs(owner, HYPOTHESES + "/" + id))).isEqualTo(200);
        }

        @Test
        @DisplayName("로그인 없이 되물으면 401")
        void anonymousIs401() throws Exception {
            long id = submitAs(newUserId());
            assertThat(status(getAnonymously(HYPOTHESES + "/" + id))).isEqualTo(401);
        }

        @Test
        @DisplayName("★ COMPLETE 이면 판정(jsonb)이 넣은 그대로 객체로 돌아오고 judgedAt 이 찍힌다 — jsonb 왕복")
        void completeReturnsJudgementObjects() throws Exception {
            Long user = newUserId();
            long id = submitAs(user);
            String judgement = "{\"grade\": \"likely\", \"reason\": \"샹크스의 약속은 장부에 두 번 나온다.\", "
                    + "\"support\": [\"T2\", \"T374\"], \"against\": [], "
                    + "\"cited_cards\": [{\"id\": \"T2\", \"title\": \"약속\"}]}";
            String presentation = "{\"status\": \"complete\", \"headline\": \"약속은 이어진다\", \"sections\": [], \"details\": []}";
            jdbc.update("update hypotheses set judgement_status = 'COMPLETE', judgement = ?::jsonb, presentation = ?::jsonb, "
                    + "judged_at = now() where id = ?", judgement, presentation, id);

            JsonNode h = data(getAs(user, HYPOTHESES + "/" + id));

            assertThat(h.path("judgementStatus").asText()).isEqualTo("COMPLETE");
            assertThat(h.path("judgement").path("grade").asText()).isEqualTo("likely");
            assertThat(h.path("judgement").path("support").size()).isEqualTo(2);
            assertThat(h.path("judgement").path("cited_cards").get(0).path("id").asText()).isEqualTo("T2");
            assertThat(h.path("presentation").path("headline").asText()).isEqualTo("약속은 이어진다");
            assertThat(h.path("judgedAt").asText()).isNotEmpty();
            assertThat(h.path("failureMessage").isNull()).isTrue();
        }

        @Test
        @DisplayName("FAILED 이면 실패 문구가 오고 판정 칸은 null 이다")
        void failedReturnsMessage() throws Exception {
            Long user = newUserId();
            long id = submitAs(user);
            jdbc.update("update hypotheses set judgement_status = 'FAILED', failure_message = '모델이 답하지 않았습니다', "
                    + "judged_at = now() where id = ?", id);

            JsonNode h = data(getAs(user, HYPOTHESES + "/" + id));

            assertThat(h.path("judgementStatus").asText()).isEqualTo("FAILED");
            assertThat(h.path("failureMessage").asText()).isEqualTo("모델이 답하지 않았습니다");
            assertThat(h.path("judgement").isNull()).isTrue();
            assertThat(h.path("presentation").isNull()).isTrue();
            assertThat(h.path("judgedAt").asText()).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("400 — 무엇이 틀렸는지 문구로 말하고, 아무것도 저장하지 않는다")
    class BadRequests {

        private MvcResult submit(int chapter, List<String> cards, Consumer<Map<String, Object>> tweak) throws Exception {
            Map<String, Object> body = submission(chapter, cards);
            tweak.accept(body);
            MvcResult result = postJson(newUserId(), HYPOTHESES, body);
            assertThat(count("hypotheses")).as("저장되지 않아야 한다").isZero();
            return result;
        }

        private void assertInvalid(MvcResult result, String code, String messagePart) throws Exception {
            assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(400);
            assertThat(errorCode(result)).isEqualTo(code);
            assertThat(errorMessage(result)).contains(messagePart);
        }

        @Test
        @DisplayName("주장이 빈칸뿐이면 INVALID_INPUT — \"주장\"")
        void blankClaim() throws Exception {
            assertInvalid(submit(400, List.of("T2"), b -> b.put("claim", "   ")), "INVALID_INPUT", "주장");
        }

        @Test
        @DisplayName("카드가 없으면 — \"하나 이상\"")
        void noCards() throws Exception {
            assertInvalid(submit(400, List.of(), b -> { }), "INVALID_INPUT", "하나 이상");
        }

        @Test
        @DisplayName("모르는 카드 T99999 — 문구에 그 번호가 든다")
        void unknownCard() throws Exception {
            assertInvalid(submit(400, List.of("T2", "T99999"), b -> { }), "INVALID_INPUT", "T99999");
        }

        @Test
        @DisplayName("★ 1화 독자가 96화에 심은 T374 를 담으면 — 그 회차 기록에 없는 카드다")
        void cardPlantedAfterChapter() throws Exception {
            assertInvalid(submit(1, List.of("T374"), b -> { }), "INVALID_INPUT", "1화 기록에 없는 카드입니다: T374");
        }

        @Test
        @DisplayName("겹치는 카드 — \"겹칩니다\"")
        void duplicateCard() throws Exception {
            assertInvalid(submit(400, List.of("T2", "T2"), b -> { }), "INVALID_INPUT", "겹칩니다");
        }

        @Test
        @DisplayName("카드 번호 모양이 아니면(\"12\") — \"카드 번호\"")
        void badCardId() throws Exception {
            assertInvalid(submit(400, List.of("12"), b -> { }), "INVALID_INPUT", "카드 번호");
        }

        @Test
        @DisplayName("101장은 못 담는다 — \"100장\"")
        void tooManyCards() throws Exception {
            List<String> many = new ArrayList<>();
            for (int i = 1; i <= 101; i++) {
                many.add("T" + i);
            }
            assertInvalid(submit(400, many, b -> { }), "INVALID_INPUT", "100장");
        }

        @Test
        @DisplayName("담지 않은 카드의 해석 — \"담지 않은\"")
        void noteForUnpickedCard() throws Exception {
            assertInvalid(submit(400, List.of("T2"), b -> b.put("notes", Map.of("T374", "x"))), "INVALID_INPUT", "담지 않은");
        }

        @Test
        @DisplayName("해석 4,001자 — \"해석\"")
        void noteTooLong() throws Exception {
            assertInvalid(submit(400, List.of("T2"), b -> b.put("notes", Map.of("T2", "가".repeat(4_001)))), "INVALID_INPUT", "해석");
        }

        @Test
        @DisplayName("제목 181자 — \"제목\"")
        void titleTooLong() throws Exception {
            assertInvalid(submit(400, List.of("T2"), b -> b.put("title", "가".repeat(181))), "INVALID_INPUT", "제목");
        }

        @Test
        @DisplayName("주장 6,001자 — \"주장\"")
        void claimTooLong() throws Exception {
            assertInvalid(submit(400, List.of("T2"), b -> b.put("claim", "가".repeat(6_001))), "INVALID_INPUT", "주장");
        }

        @Test
        @DisplayName("회차가 없거나 0 이거나 가장 뒤 회차를 넘으면 TRAILER_INVALID_CHAPTER")
        void badChapter() throws Exception {
            assertInvalid(submit(400, List.of("T2"), b -> b.remove("chapter")), "TRAILER_INVALID_CHAPTER", "회차");
            assertInvalid(submit(400, List.of("T2"), b -> b.put("chapter", 0)), "TRAILER_INVALID_CHAPTER", "1 이상");
            assertInvalid(submit(400, List.of("T2"), b -> b.put("chapter", 401)), "TRAILER_INVALID_CHAPTER", "400");
        }

        @Test
        @DisplayName("★ 해시가 카드 표의 값과 다르면 TRAILER_DIGEST_MISMATCH — 옛 화면이 맡기는 가설을 여기서 막는다")
        void digestMismatch() throws Exception {
            assertInvalid(submit(400, List.of("T2"), b -> b.put("cardsDigest", "0".repeat(64))), "TRAILER_DIGEST_MISMATCH", "장부");
            assertInvalid(submit(400, List.of("T2"), b -> b.put("stateDigest", "0".repeat(64))), "TRAILER_DIGEST_MISMATCH", "장부");
        }

        @Test
        @DisplayName("해시가 없으면 INVALID_INPUT — 칸 이름을 말한다")
        void digestMissing() throws Exception {
            assertInvalid(submit(400, List.of("T2"), b -> b.remove("stateDigest")), "INVALID_INPUT", "stateDigest");
        }

        @Test
        @DisplayName("JSON 이 아니거나 칸의 자료형이 틀리면 INVALID_INPUT")
        void unreadableBody() throws Exception {
            Long user = newUserId();
            MvcResult broken = postJson(user, HYPOTHESES, "{");
            assertThat(status(broken)).isEqualTo(400);
            assertThat(errorCode(broken)).isEqualTo("INVALID_INPUT");
            MvcResult wrongType = postJson(user, HYPOTHESES, "{\"chapter\": \"사백\", \"claim\": \"x\", \"cards\": [\"T2\"]}");
            assertThat(status(wrongType)).isEqualTo(400);
            assertThat(errorCode(wrongType)).isEqualTo("INVALID_INPUT");
            assertThat(count("hypotheses")).isZero();
        }
    }
}
