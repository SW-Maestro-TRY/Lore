package com.lore.piecemaker.resultview;

import com.lore.piecemaker.support.PieceMakerIntegrationTest;
import com.lore.piecemaker.support.PieceMakerItSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@PieceMakerIntegrationTest
@TestPropertySource(properties = "app.analytics.enabled=true")
class ResultViewApiIT extends PieceMakerItSupport {
    private static final String NORMAL = "{\"grade\":\"likely\",\"reason\":\"약속이 반복됩니다\",\"support\":[\"T2\"],\"against\":[]}";

    @Test
    void expiredViewIsReplacedWithoutReturningExpiredTimestamp() throws Exception {
        long user = newUserId();
        long hypothesis = result(user, "COMPLETE", NORMAL);
        data(record(user, hypothesis));
        jdbc.update("update piece_maker_first_result_view set viewed_at = now() - interval '365 days' where user_id = ?", user);
        Instant before = dbNow();
        var refreshed = data(record(user, hypothesis));
        assertThat(refreshed.path("firstView").asBoolean()).isTrue();
        assertThat(Instant.parse(refreshed.path("viewedAt").asText())).isBetween(before, dbNow());
        assertThat(data(record(user, hypothesis)).path("firstView").asBoolean()).isFalse();
        assertThat(ledgerCount(user)).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-2-11-1 내 COMPLETE 결과를 실제 DB에 한 줄 저장하고 서버 시각을 반환한다")
    void storesFirstNormalView() throws Exception {
        long user = newUserId();
        long hypothesis = result(user, "COMPLETE", NORMAL);
        Instant before = dbNow();
        var response = data(record(user, hypothesis));
        assertThat(response.path("firstView").asBoolean()).isTrue();
        assertThat(response.path("metaEvent").isNull()).isTrue(); // 기본 설정에서는 외부 신호를 발급하지 않는다.
        Instant at = Instant.parse(response.path("viewedAt").asText());
        assertThat(at).isBetween(before, dbNow());
        assertThat(ledgerCount(user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select hypothesis_id from piece_maker_first_result_view where user_id = ?", Long.class, user))
                .isEqualTo(hypothesis);
    }

    @Test
    @DisplayName("AC-2-11-2 새 요청·다른 가설을 열어도 계정별 첫 시각과 한 줄을 유지한다")
    void repeatedAndDifferentHypothesisKeepFirstView() throws Exception {
        long user = newUserId();
        long first = result(user, "COMPLETE", NORMAL);
        long second = result(user, "COMPLETE", NORMAL);
        var initial = data(record(user, first));
        for (long hypothesis : List.of(first, second, first)) {
            var repeated = data(record(user, hypothesis));
            assertThat(repeated.path("firstView").asBoolean()).isFalse();
            assertThat(repeated.path("viewedAt")).isEqualTo(initial.path("viewedAt"));
        }
        assertThat(ledgerCount(user)).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-2-11-2 서로 다른 가설의 동시 요청 8개도 한 번만 최초 기록한다")
    void concurrentRequestsInsertOnlyOnce() throws Exception {
        long user = newUserId();
        long first = result(user, "COMPLETE", NORMAL);
        long second = result(user, "COMPLETE", NORMAL);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        try (var workers = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 8; i++) {
                long hypothesis = i % 2 == 0 ? first : second;
                futures.add(workers.submit(() -> {
                    start.await();
                    return record(user, hypothesis);
                }));
            }
            start.countDown();
            List<Boolean> firstFlags = new ArrayList<>();
            List<String> timestamps = new ArrayList<>();
            for (var future : futures) {
                var response = data(future.get(20, TimeUnit.SECONDS));
                firstFlags.add(response.path("firstView").asBoolean());
                timestamps.add(response.path("viewedAt").asText());
            }
            assertThat(firstFlags).filteredOn(Boolean::booleanValue).hasSize(1);
            assertThat(timestamps.stream().distinct()).hasSize(1);
            assertThat(ledgerCount(user)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("AC-2-11-3 남의 가설·없는 번호·잘못된 번호 모두 같은 404이며 저장하지 않는다")
    void rejectsUnknownAndOtherOwner() throws Exception {
        long owner = newUserId();
        long stranger = newUserId();
        long hypothesis = result(owner, "COMPLETE", NORMAL);
        for (String id : List.of(String.valueOf(hypothesis), "9999999", "abc", "0")) {
            var response = mockMvc.perform(post(path(id)).with(asUser(stranger))).andReturn();
            assertThat(status(response)).isEqualTo(404);
            assertThat(errorCode(response)).isEqualTo("PIECE_MAKER_HYPOTHESIS_NOT_FOUND");
        }
        assertThat(ledgerCount(owner) + ledgerCount(stranger)).isZero();
    }

    @Test
    @DisplayName("AC-2-11-4 대기·실패·깨진 판정은 400이며 정상 열람으로 저장하지 않는다")
    void rejectsNonNormalResults() throws Exception {
        long user = newUserId();
        List<Long> invalid = List.of(result(user, "PENDING", null), result(user, "FAILED", null), result(user, "COMPLETE", "{}"));
        for (long hypothesis : invalid) {
            var response = record(user, hypothesis);
            assertThat(status(response)).isEqualTo(400);
            assertThat(errorCode(response)).isEqualTo("INVALID_INPUT");
        }
        assertThat(ledgerCount(user)).isZero();
    }

    @Test
    @DisplayName("AC-2-11-5 로그인 없이 열람 신호를 보내면 401이며 저장하지 않는다")
    void anonymousIsUnauthorized() throws Exception {
        long user = newUserId();
        long hypothesis = result(user, "COMPLETE", NORMAL);
        assertThat(status(mockMvc.perform(post(path(String.valueOf(hypothesis)))).andReturn())).isEqualTo(401);
        assertThat(ledgerCount(user)).isZero();
    }

    @Test
    @DisplayName("AC-2-11-6 실제 INSERT가 실패하면 HTTP 성공 없이 롤백한다")
    void databaseFailureIsNotSuccess() throws Exception {
        long user = newUserId();
        long hypothesis = result(user, "COMPLETE", NORMAL);
        // 비어 있는 시험 표에서만 일시 제약을 둔다. 종료 시 반드시 제거한다.
        jdbc.execute("alter table piece_maker_first_result_view add constraint result_view_it_reject check (false) not valid");
        try {
            assertThat(status(record(user, hypothesis))).isEqualTo(500);
            assertThat(ledgerCount(user)).isZero();
        } finally {
            jdbc.execute("alter table piece_maker_first_result_view drop constraint result_view_it_reject");
        }
        assertThat(data(record(user, hypothesis)).path("firstView").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("AC-2-11-7 가설 삭제 후에도 중복 방지가 유지되고 계정 삭제 때 함께 지워진다")
    void hypothesisDeletionKeepsRecordAndAccountDeletionRemovesIt() throws Exception {
        long user = newUserId();
        long first = result(user, "COMPLETE", NORMAL);
        var initial = data(record(user, first));
        jdbc.update("delete from hypotheses where id = ?", first);
        assertThat(ledgerCount(user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select hypothesis_id from piece_maker_first_result_view where user_id = ?", Long.class, user)).isNull();
        long second = result(user, "COMPLETE", NORMAL);
        var again = data(record(user, second));
        assertThat(again.path("firstView").asBoolean()).isFalse();
        assertThat(again.path("viewedAt")).isEqualTo(initial.path("viewedAt"));
        jdbc.update("delete from hypotheses where user_id = ?", user);
        jdbc.update("delete from users where id = ?", user);
        assertThat(ledgerCount(user)).isZero();
    }

    private long result(long user, String status, String judgement) {
        return jdbc.queryForObject("""
                insert into hypotheses (user_id, chapter, title, claim, state_digest, cards_digest,
                    judgement_status, judgement, failure_message, judged_at)
                values (?, 400, '시험 가설', '시험 주장', ?, ?, ?, ?::jsonb, ?, ?)
                returning id
                """, Long.class, user, "a".repeat(64), "b".repeat(64), status, judgement,
                "FAILED".equals(status) ? "시험 실패" : null,
                "PENDING".equals(status) ? null : java.sql.Timestamp.from(Instant.now()));
    }

    private long ledgerCount(long user) {
        return jdbc.queryForObject("select count(*) from piece_maker_first_result_view where user_id = ?", Long.class, user);
    }

    private MvcResult record(long user, long hypothesis) throws Exception {
        return mockMvc.perform(post(path(String.valueOf(hypothesis))).with(asUser(user))).andReturn();
    }

    private static String path(String id) {
        return "/api/piece-maker/v1/hypotheses/" + id + "/result-view";
    }
    private Instant dbNow() {
        return jdbc.queryForObject("select clock_timestamp()", (rs, row) -> rs.getTimestamp(1).toInstant());
    }

}
