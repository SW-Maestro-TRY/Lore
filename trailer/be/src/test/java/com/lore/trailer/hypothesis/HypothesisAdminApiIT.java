package com.lore.trailer.hypothesis;

import com.fasterxml.jackson.databind.JsonNode;
import com.lore.trailer.support.TrailerIntegrationTest;
import com.lore.trailer.support.TrailerItSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 운영자용 가설 API 의 통합 검사 — 가져가기(2-8). 운영자는 users.role 을 ADMIN 으로 바꾼 시험용 사용자다. */
@TrailerIntegrationTest
@DisplayName("운영자용 가설 API — 가져가기(2-8) · 판정 넣기(2-9)")
class HypothesisAdminApiIT extends TrailerItSupport {

    private static final String CARDS = "/api/trailer/v1/public/cards";
    private static final String HYPOTHESES = "/api/trailer/v1/hypotheses";
    private static final String ADMIN = "/api/trailer/v1/admin/hypotheses";

    private Map<String, Object> submission(int chapter, List<String> cards, String title) throws Exception {
        JsonNode meta = data(getAnonymously(CARDS + "/meta"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chapter", chapter);
        body.put("title", title);
        body.put("claim", "주장 — " + title);
        body.put("cards", cards);
        body.put("notes", Map.of());
        body.put("stateDigest", meta.path("stateDigest").asText());
        body.put("cardsDigest", meta.path("cardsDigest").asText());
        return body;
    }

    private long submit(Long user, int chapter, List<String> cards, String title) throws Exception {
        MvcResult result = postJson(user, HYPOTHESES, submission(chapter, cards, title));
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return data(result).path("id").asLong();
    }

    private Long newAdminId() {
        Long id = newUserId();
        makeAdmin(id);
        return id;
    }

    private static List<String> ids(JsonNode cards) {
        List<String> ids = new ArrayList<>();
        cards.forEach(card -> ids.add(card.path("id").asText()));
        return ids;
    }

    @Test
    @DisplayName("★ 운영자가 아니면 403 ADMIN_ONLY, 로그인 없이는 401 — 자기 가설이 있어도 마찬가지")
    void onlyAdmins() throws Exception {
        Long reader = newUserId();
        submit(reader, 400, List.of("T2"), "독자의 가설");

        MvcResult forbidden = getAs(reader, ADMIN);
        assertThat(status(forbidden)).isEqualTo(403);
        assertThat(errorCode(forbidden)).isEqualTo("ADMIN_ONLY");
        assertThat(status(getAnonymously(ADMIN))).isEqualTo(401);
    }

    @Test
    @DisplayName("★ PENDING 만 맡긴 순서로 오고, 줄마다 judge.py 의 입력(회차 · 주장 · 카드 · 해석 · 해시 둘)이 든다")
    void pendingOldestFirstWithJudgeInputs() throws Exception {
        Long admin = newAdminId();
        Long a = newUserId();
        Long b = newUserId();
        long oldest = submit(a, 1, List.of("T5", "T2"), "첫째");
        long judged = submit(b, 400, List.of("T374"), "판정 끝난 것");
        Map<String, Object> withNote = submission(400, List.of("T2", "T374"), "셋째");
        withNote.put("notes", Map.of("T374", "약속의 모자"));
        long latest = data(postJson(a, HYPOTHESES, withNote)).path("id").asLong();
        jdbc.update("update hypotheses set judgement_status = 'COMPLETE', judgement = '{\"grade\":\"likely\"}'::jsonb, "
                + "judged_at = now() where id = ?", judged);

        MvcResult result = getAs(admin, ADMIN);

        assertThat(status(result)).isEqualTo(200);
        JsonNode items = data(result).path("items");
        assertThat(items.size()).as("PENDING 만").isEqualTo(2);
        JsonNode first = items.get(0);
        JsonNode second = items.get(1);
        assertThat(first.path("id").asLong()).isEqualTo(oldest);
        assertThat(second.path("id").asLong()).isEqualTo(latest);
        // judge.py 가 받는 모양 — 회차, 주장, 카드(순서 그대로, 그 회차로 가린 값), 해석, 해시 둘.
        assertThat(first.path("chapter").asInt()).isEqualTo(1);
        assertThat(first.path("claim").asText()).isEqualTo("주장 — 첫째");
        assertThat(ids(first.path("cards"))).containsExactly("T5", "T2");
        assertThat(first.path("cards").get(0).path("status").asText()).as("1화 독자의 T5 는 미회수").isEqualTo("open");
        assertThat(first.path("notes").path("T5").asText()).isEmpty();
        JsonNode meta = data(getAnonymously(CARDS + "/meta"));
        assertThat(first.path("stateDigest").asText()).isEqualTo(meta.path("stateDigest").asText());
        assertThat(first.path("cardsDigest").asText()).isEqualTo(meta.path("cardsDigest").asText());
        assertThat(first.path("createdAt").asText()).isNotEmpty();
        assertThat(second.path("notes").path("T374").asText()).isEqualTo("약속의 모자");
        assertThat(ids(second.path("cards"))).containsExactly("T2", "T374");
        // 운영자에게는 누가 맡겼는지 가지 않는다 — 판정에 필요하지 않다.
        assertThat(first.has("userId")).isFalse();
        assertThat(first.has("judgementStatus")).isFalse();
    }

    @Test
    @DisplayName("판정 안 된 가설이 없으면 빈 배열")
    void emptyWhenNothingPending() throws Exception {
        JsonNode items = data(getAs(newAdminId(), ADMIN)).path("items");
        assertThat(items.isArray()).isTrue();
        assertThat(items.size()).isZero();
    }

    /* ---- 판정 넣기(2-9) ---------------------------------------------------------- */

    private static Map<String, Object> judgement(String grade, List<String> support, List<String> against) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("grade", grade);
        value.put("reason", "샹크스의 약속은 장부에 두 번 나온다.");
        value.put("support", support);
        value.put("against", against);
        value.put("cited_cards", List.of(Map.of("id", "T2", "title", "약속")));
        return value;
    }

    private static Map<String, Object> judgeBody(long id, String status, Map<String, Object> judgement,
                                                 Map<String, Object> presentation, String failure) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("judgementStatus", status);
        if (judgement != null) {
            body.put("judgement", judgement);
        }
        if (presentation != null) {
            body.put("presentation", presentation);
        }
        if (failure != null) {
            body.put("failureMessage", failure);
        }
        return body;
    }

    @Test
    @DisplayName("★ COMPLETE 를 넣으면 판정과 편집본이 그대로 저장되고, 독자가 되물으면 그대로 보이고, 대기 목록에서 빠진다")
    void completeIsStoredAndVisibleToReader() throws Exception {
        Long admin = newAdminId();
        Long reader = newUserId();
        long id = submit(reader, 400, List.of("T2", "T374"), "판정받을 가설");
        Map<String, Object> presentation = Map.of("status", "complete", "headline", "약속은 이어진다", "sections", List.of(), "details", List.of());

        MvcResult result = postJson(admin, ADMIN + "/judge",
                judgeBody(id, "COMPLETE", judgement("likely", List.of("T2", "T374"), List.of()), presentation, null));

        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode h = data(result);
        assertThat(h.path("id").asLong()).isEqualTo(id);
        assertThat(h.path("judgementStatus").asText()).isEqualTo("COMPLETE");
        assertThat(h.path("judgement").path("grade").asText()).isEqualTo("likely");
        assertThat(h.path("judgement").path("cited_cards").get(0).path("title").asText()).isEqualTo("약속");
        assertThat(h.path("presentation").path("headline").asText()).isEqualTo("약속은 이어진다");
        assertThat(h.path("judgedAt").asText()).isNotEmpty();
        assertThat(h.path("failureMessage").isNull()).isTrue();
        // 독자의 되묻기(2-6)에 그대로 보인다.
        JsonNode seen = data(getAs(reader, HYPOTHESES + "/" + id));
        assertThat(seen.path("judgementStatus").asText()).isEqualTo("COMPLETE");
        assertThat(seen.path("judgement")).isEqualTo(h.path("judgement"));
        // 대기 목록에서 빠진다.
        assertThat(data(getAs(admin, ADMIN)).path("items").size()).isZero();
        assertThat(jdbc.queryForObject("select judgement_status from hypotheses where id = ?", String.class, id)).isEqualTo("COMPLETE");
    }

    @Test
    @DisplayName("FAILED 를 넣으면 실패 문구가 저장되고 판정 칸은 비어 있다. 다시 COMPLETE 로 덮어쓸 수 있다")
    void failedThenOverwrite() throws Exception {
        Long admin = newAdminId();
        long id = submit(newUserId(), 400, List.of("T2"), "실패할 가설");

        MvcResult failed = postJson(admin, ADMIN + "/judge", judgeBody(id, "FAILED", null, null, "모델이 답하지 않았습니다"));
        assertThat(status(failed)).isEqualTo(200);
        assertThat(data(failed).path("judgementStatus").asText()).isEqualTo("FAILED");
        assertThat(data(failed).path("failureMessage").asText()).isEqualTo("모델이 답하지 않았습니다");
        assertThat(data(failed).path("judgement").isNull()).isTrue();

        MvcResult redone = postJson(admin, ADMIN + "/judge",
                judgeBody(id, "COMPLETE", judgement("insufficient", List.of(), List.of()), null, null));
        assertThat(status(redone)).isEqualTo(200);
        assertThat(data(redone).path("judgementStatus").asText()).isEqualTo("COMPLETE");
        assertThat(data(redone).path("failureMessage").isNull()).isTrue();
        assertThat(data(redone).path("presentation").isNull()).isTrue();
    }

    @Test
    @DisplayName("모양이 틀리면 400 — 상태 값, 판정 없음, 등급, 근거 배열, 실패 문구 없음, id 없음. 없는 id 는 404. 비운영자는 403")
    void judgeValidation() throws Exception {
        Long admin = newAdminId();
        Long reader = newUserId();
        long id = submit(reader, 400, List.of("T2"), "검사용");

        record Case(String name, Map<String, Object> body, int status, String code) {
        }
        List<Case> cases = List.of(
                new Case("상태 값", judgeBody(id, "PENDING", null, null, null), 400, "INVALID_INPUT"),
                new Case("판정 없음", judgeBody(id, "COMPLETE", null, null, null), 400, "INVALID_INPUT"),
                new Case("등급", judgeBody(id, "COMPLETE", judgement("maybe", List.of(), List.of()), null, null), 400, "INVALID_INPUT"),
                new Case("근거 배열", judgeBody(id, "COMPLETE", Map.of("grade", "likely", "reason", "x", "support", "T2", "against", List.of()), null, null), 400, "INVALID_INPUT"),
                new Case("실패 문구 없음", judgeBody(id, "FAILED", null, null, "  "), 400, "INVALID_INPUT"),
                new Case("없는 id", judgeBody(999_999, "FAILED", null, null, "x"), 404, "TRAILER_HYPOTHESIS_NOT_FOUND"));
        for (Case c : cases) {
            MvcResult result = postJson(admin, ADMIN + "/judge", c.body());
            assertThat(status(result)).as(c.name()).isEqualTo(c.status());
            assertThat(errorCode(result)).as(c.name()).isEqualTo(c.code());
        }
        Map<String, Object> noId = new LinkedHashMap<>();
        noId.put("judgementStatus", "FAILED");
        noId.put("failureMessage", "x");
        assertThat(status(postJson(admin, ADMIN + "/judge", noId))).isEqualTo(400);
        // 독자 자신은 판정을 넣을 수 없다.
        MvcResult forbidden = postJson(reader, ADMIN + "/judge", judgeBody(id, "FAILED", null, null, "x"));
        assertThat(status(forbidden)).isEqualTo(403);
        assertThat(errorCode(forbidden)).isEqualTo("ADMIN_ONLY");
        assertThat(jdbc.queryForObject("select judgement_status from hypotheses where id = ?", String.class, id)).isEqualTo("PENDING");
    }
}
