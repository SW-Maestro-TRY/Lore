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
@DisplayName("운영자용 가설 API — 판정 안 된 가설 가져가기(2-8)")
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
}
