package com.lore.piecemaker.metapixel;

import com.fasterxml.jackson.databind.JsonNode;
import com.lore.piecemaker.support.PieceMakerIntegrationTest;
import com.lore.piecemaker.support.PieceMakerItSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@PieceMakerIntegrationTest
@TestPropertySource(properties = {"app.analytics.enabled=true", "lore.piece-maker.meta-pixel.enabled=true",
        "lore.piece-maker.meta-pixel.id=123456789", "lore.piece-maker.meta-pixel.site-origin=https://lorecomic.com",
        "lore.piece-maker.ad-report.test-exclusions-reviewed=true",
        "lore.piece-maker.ad-report.excluded-user-ids=900000000002"})
class MetaPixelApiIT extends PieceMakerItSupport {
    @Test
    void firstNormalViewWithoutAnyAdLandingIssuesOneSignal() throws Exception {
        long user = newUserId();
        long hypothesis = result(user);
        var first = view(user, hypothesis);
        assertThat(first.path("firstView").asBoolean()).isTrue();
        var event = first.path("metaEvent");
        assertThat(event.path("eventName").asText()).isEqualTo("PieceMakerFirstResultViewed");
        assertThat(event.path("pixelId").asText()).isEqualTo("123456789");
        assertThat(event.path("siteOrigin").asText()).isEqualTo("https://lorecomic.com");
        assertThat(UUID.fromString(event.path("eventId").asText()).version()).isEqualTo(4);
        assertThat(event.size()).isEqualTo(4);
        assertThat(view(user, hypothesis).path("metaEvent").isNull()).isTrue();
        assertThat(view(user, result(user)).path("metaEvent").isNull()).isTrue();
    }

    @Test
    void concurrentTabsIssueOnlyOneSignal() throws Exception {
        long user = newUserId();
        long hypothesis = result(user);
        var start = new CountDownLatch(1);
        List<Future<JsonNode>> responses = new ArrayList<>();
        try (var workers = Executors.newFixedThreadPool(6)) {
            for (int i = 0; i < 6; i++) responses.add(workers.submit(() -> {
                start.await(); return view(user, hypothesis);
            }));
            start.countDown();
            int signals = 0;
            for (var response : responses) if (!response.get().path("metaEvent").isNull()) signals++;
            assertThat(signals).isEqualTo(1);
        }
    }

    @Test
    void adminsInactiveAndExplicitTestAccountsStillRecordInternallyButDoNotIssueSignals() throws Exception {
        long admin = newUserId();
        jdbc.update("update users set role = 'ADMIN' where id = ?", admin);
        long inactive = newUserId();
        jdbc.update("update users set status = 'DELETED', deleted_at = now() where id = ?", inactive);
        long tester = 900000000002L;
        jdbc.update("""
                insert into users (id,email,status,role,pet_slots,created_at,updated_at)
                values (?,'pm-pixel-test@example.invalid','ACTIVE','USER',1,now(),now())
                """, tester);
        for (long user : List.of(admin, inactive, tester)) {
            var response = view(user, result(user));
            assertThat(response.path("firstView").asBoolean()).isTrue();
            assertThat(response.path("metaEvent").isNull()).isTrue();
        }
    }

    @Test
    void expiredHistoryDoesNotTurnAnOldAccountIntoANewMetaCompletion() throws Exception {
        long user = newUserId();
        long hypothesis = result(user);
        view(user, hypothesis);
        jdbc.update("update users set created_at = now() - interval '400 days' where id = ?", user);
        jdbc.update("update piece_maker_first_result_view set viewed_at = now() - interval '365 days' where user_id = ?", user);
        var response = view(user, hypothesis);
        assertThat(response.path("firstView").asBoolean()).isTrue();
        assertThat(response.path("metaEvent").isNull()).isTrue();
    }

    @Test
    void selectiveDeletionGapPreventsReissuingAFirstCompletion() throws Exception {
        long user = newUserId();
        long hypothesis = result(user);
        view(user, hypothesis);
        jdbc.update("delete from piece_maker_first_result_view where user_id = ?", user);
        var response = view(user, hypothesis);
        assertThat(response.path("firstView").asBoolean()).isTrue();
        assertThat(response.path("metaEvent").isNull()).isTrue();
    }

    @Test
    void failedResultDoesNotIssueASignalOrSaveAView() throws Exception {
        long user = newUserId();
        long hypothesis = result(user);
        jdbc.update("update hypotheses set judgement = '{}'::jsonb where id = ?", hypothesis);
        var response = mockMvc.perform(post(url(hypothesis)).with(asUser(user))).andReturn();
        assertThat(status(response)).isEqualTo(400);
        assertThat(jdbc.queryForObject("select count(*) from piece_maker_first_result_view where user_id = ?", Long.class, user)).isZero();
    }

    private JsonNode view(long user, long hypothesis) throws Exception {
        return data(mockMvc.perform(post(url(hypothesis)).with(asUser(user))).andReturn());
    }

    private String url(long id) { return "/api/piece-maker/v1/hypotheses/" + id + "/result-view"; }

    private long result(long user) {
        return jdbc.queryForObject("""
                insert into hypotheses (user_id,chapter,title,claim,state_digest,cards_digest,judgement_status,judgement,judged_at)
                values (?,400,'시험 제목','시험 주장',?,?,'COMPLETE',
                    '{"grade":"likely","reason":"근거","support":[],"against":[]}'::jsonb,now()) returning id
                """, Long.class, user, "a".repeat(64), "b".repeat(64));
    }
}
