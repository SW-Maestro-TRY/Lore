package com.lore.piecemaker.retention;

import com.lore.common.retention.AccountPurge;
import com.lore.common.retention.AccountPurgeStep;
import com.lore.common.retention.PurgeableUserRepository;
import com.lore.piecemaker.support.PieceMakerIntegrationTest;
import com.lore.piecemaker.support.PieceMakerItSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 공통 계정 파기 경로와 PostgreSQL FK·트랜잭션을 함께 검증한다. */
@PieceMakerIntegrationTest
@TestPropertySource(properties = {
        "app.analytics.enabled=false",
        "app.s3.content-bucket=",
        "lore.retention.account-purge-enabled=false",
        "lore.retention.sweep-enabled=false",
        "app.zzal.archive.enabled=false"
})
class PieceMakerPurgeIT extends PieceMakerItSupport {
    @Autowired private PieceMakerPurge pieceMakerPurge;
    @Autowired private AccountPurgeStep accountStep;
    @Autowired private PurgeableUserRepository purgeableUsers;
    private final List<Long> feedbackIds = new ArrayList<>();
    private final List<String> anonIds = new ArrayList<>();

    @AfterEach
    void removeRemainingFixtureRows() {
        feedbackIds.forEach(id -> jdbc.update("delete from piece_maker_feedback where id = ?", id));
        anonIds.forEach(anon -> {
            jdbc.update("delete from zzal_event where anon_id = ?", anon);
            jdbc.update("delete from zzal_anon_identity where anon_id = ?", anon);
            jdbc.update("delete from piece_maker_ad_landing where anon_id = ?", anon);
        });
    }

    @Test
    @DisplayName("도메인 파기는 본인 PM 자료만 지우고 같은 계정의 타 서비스·공통 연결을 보존한다")
    void domainPurgeDeletesOnlyOwnedHypothesesAndCards() {
        String anon = newAnon();
        var owner = fixture(newUserId(), anon);
        var other = fixture(newUserId(), anon);

        assertThat(pieceMakerPurge.purge(owner.user())).isEqualTo(2);
        assertThat(pieceMakerPurge.purge(owner.user())).isZero();
        assertThat(rows("hypotheses", "user_id", owner.user())).isZero();
        assertThat(rows("hypothesis_foreshadowing", "hypothesis_id", owner.hypothesis())).isZero();
        assertThat(rows("hypotheses", "user_id", other.user())).isEqualTo(1);
        assertThat(rows("hypothesis_foreshadowing", "hypothesis_id", other.hypothesis())).isEqualTo(1);
        assertThat(rows("users", "id", owner.user())).isEqualTo(1);
        assertThat(rows("piece_maker_first_result_view", "user_id", owner.user())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select hypothesis_id from piece_maker_first_result_view where user_id = ?",
                Long.class, owner.user())).isNull();
        assertThat(rows("piece_maker_ad_landing", "owner_user_id", owner.user())).isEqualTo(1);
        assertThat(rows("piece_maker_feedback", "user_id", owner.user())).isEqualTo(1);
        assertThat(pmEvents(owner.user())).isZero();
        assertLegacyAnalyticsPresent(owner.user());
    }

    @Test
    @DisplayName("수집이 꺼져 있어도 PM 자료를 파기하고 같은 계정·다른 계정의 기존 공통 분석 동작은 유지한다")
    void accountPurgeCascadesMeasurementAndDeletesOnlyPieceMakerAnalytics() {
        String anon = newAnon();
        var owner = fixture(newUserId(), anon);
        var other = fixture(newUserId(), anon);
        long anonymousEvent = event(null, anon);
        UUID anonymousLanding = landing(null, anon);

        accountStep.purge(owner.user());
        assertThat(accountStep.purge(owner.user())).isZero();

        assertGone(owner);
        assertPresent(other);
        assertThat(rows("zzal_event", "id", anonymousEvent)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from piece_maker_ad_landing where id = ?",
                Long.class, anonymousLanding)).isEqualTo(1);
        // 기존 피드백의 별도 보존 정책은 이번 수정에서 바꾸지 않는다.
        assertThat(rows("piece_maker_feedback", "user_id", owner.user())).isEqualTo(1);
    }

    @Test
    @DisplayName("마지막 계정 DELETE가 실패하면 먼저 지운 가설·카드·분석 기록도 전부 복원한다")
    void finalAccountDeleteFailureRollsBackEveryDatabaseDeletion() {
        String anon = newAnon();
        var owner = fixture(newUserId(), anon);
        // 격리된 시험 DB에만 마지막 users DELETE를 막는 자식을 추가한다.
        jdbc.execute("create table piece_maker_purge_it_block (user_id bigint references users(id))");
        try {
            jdbc.update("insert into piece_maker_purge_it_block (user_id) values (?)", owner.user());
            assertThatThrownBy(() -> accountStep.purge(owner.user())).isInstanceOf(DataAccessException.class);
            assertPresent(owner);
            assertThat(jdbc.queryForObject("select hypothesis_id from piece_maker_first_result_view where user_id = ?",
                    Long.class, owner.user())).isEqualTo(owner.hypothesis());
        } finally {
            jdbc.execute("drop table piece_maker_purge_it_block");
        }
        accountStep.purge(owner.user());
        assertGone(owner);
    }

    @Test
    @DisplayName("기존 30일 유예를 넘긴 탈퇴 계정만 파기하며 경계·최근 탈퇴·활성 계정은 유지한다")
    void scheduledSelectionKeepsThirtyDayBoundaryAndActiveUsers() {
        String anon = newAnon();
        var expired = fixture(newUserId(), anon);
        var boundary = fixture(newUserId(), anon);
        var recent = fixture(newUserId(), anon);
        var active = fixture(newUserId(), anon);
        Instant now = Instant.parse("2026-10-05T12:00:00Z");
        withdraw(expired.user(), now.minus(Duration.ofDays(30)).minusSeconds(1));
        withdraw(boundary.user(), now.minus(Duration.ofDays(30)));
        withdraw(recent.user(), now.minus(Duration.ofDays(29)));

        // 스케줄러를 운영 설정으로 켜지 않고, 시험에서 기존 선택 로직 한 회차만 호출한다.
        AccountPurge.Result result = new AccountPurge(purgeableUsers, accountStep, true).runOnce(now);
        assertThat(result.failed()).isZero();
        assertThat(result.accounts()).isEqualTo(1);
        assertGone(expired);
        assertPresent(boundary);
        assertPresent(recent);
        assertPresent(active);
    }

    private Fixture fixture(long user, String anon) {
        long hypothesis = jdbc.queryForObject("""
                insert into hypotheses (user_id, chapter, title, claim, state_digest, cards_digest,
                    judgement_status, judgement, judged_at)
                values (?, 400, '파기 시험', '내 가설', ?, ?, 'COMPLETE',
                    '{"grade":"likely","reason":"시험 근거","support":[],"against":[]}'::jsonb, now()) returning id
                """, Long.class, user, "a".repeat(64), "b".repeat(64));
        jdbc.update("""
                insert into hypothesis_foreshadowing
                    (hypothesis_id, card_position, thread_id, start_chapter, thread_kind, title, fact, excerpt, status)
                values (?, 0, 'T2', 1, '약속', '시험 카드', '사실', '발췌', 'open')
                """, hypothesis);
        jdbc.update("insert into piece_maker_first_result_view (user_id, hypothesis_id, viewed_at) values (?, ?, now())",
                user, hypothesis);
        landing(user, anon);
        event(user, anon);
        event(user, anon, "zzal_hatch_open");
        event(user, anon, "auth_login");
        event(user, anon, "pieceXmaker_visit");
        jdbc.update("insert into zzal_anon_identity (anon_id, user_id, linked_at) values (?, ?, now())", anon, user);
        feedbackIds.add(jdbc.queryForObject("""
                insert into piece_maker_feedback (kind, body, user_id) values ('ERROR_REPORT', '시험 피드백', ?) returning id
                """, Long.class, user));
        fund(user, 10);
        return new Fixture(user, hypothesis);
    }

    private long event(Long user, String anon) {
        return event(user, anon, "piece_maker_visit");
    }

    private long event(Long user, String anon, String name) {
        return jdbc.queryForObject("""
                insert into zzal_event (name, anon_id, user_id, occurred_at, received_at)
                values (?, ?, ?, now(), now()) returning id
                """, Long.class, name, anon, user);
    }

    private UUID landing(Long user, String anon) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into piece_maker_ad_landing (id, anon_id, request_key, owner_user_id, client_landed_at,
                    utm_source, utm_medium, utm_campaign, utm_content, placement, claimed_at)
                values (?, ?, ?, ?, now(), 'facebook', 'paid_social', 'purge_test', '1', 'feed',
                    case when ?::bigint is null then null else now() end)
                """, id, anon, UUID.randomUUID(), user, user);
        return id;
    }

    private String newAnon() {
        String anon = UUID.randomUUID().toString().replace("-", "");
        anonIds.add(anon);
        return anon;
    }

    private void withdraw(long user, Instant at) {
        jdbc.update("update users set status = 'DELETED', deleted_at = ? where id = ?", Timestamp.from(at), user);
    }

    private void assertGone(Fixture fixture) {
        assertThat(rows("users", "id", fixture.user())).isZero();
        assertThat(rows("hypotheses", "user_id", fixture.user())).isZero();
        assertThat(rows("hypothesis_foreshadowing", "hypothesis_id", fixture.hypothesis())).isZero();
        assertThat(rows("piece_maker_first_result_view", "user_id", fixture.user())).isZero();
        assertThat(rows("piece_maker_ad_landing", "owner_user_id", fixture.user())).isZero();
        assertThat(pmEvents(fixture.user())).isZero();
        assertLegacyAnalyticsPresent(fixture.user());
        assertThat(creditRows(fixture.user(), null)).isZero();
    }

    private void assertPresent(Fixture fixture) {
        assertThat(rows("users", "id", fixture.user())).isEqualTo(1);
        assertThat(rows("hypotheses", "user_id", fixture.user())).isEqualTo(1);
        assertThat(rows("hypothesis_foreshadowing", "hypothesis_id", fixture.hypothesis())).isEqualTo(1);
        assertThat(rows("piece_maker_first_result_view", "user_id", fixture.user())).isEqualTo(1);
        assertThat(rows("piece_maker_ad_landing", "owner_user_id", fixture.user())).isEqualTo(1);
        assertThat(pmEvents(fixture.user())).isEqualTo(1);
        assertLegacyAnalyticsPresent(fixture.user());
        assertThat(creditRows(fixture.user(), null)).isEqualTo(1);
    }

    /** 표·열 이름에는 이 시험 안에서 정한 고정값만 사용한다. */
    private long rows(String table, String column, long id) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + column + " = ?", Long.class, id);
    }

    private long pmEvents(long user) {
        return jdbc.queryForObject("""
                select count(*) from zzal_event
                where user_id = ? and left(name, length('piece_maker_')) = 'piece_maker_'
                """, Long.class, user);
    }

    private void assertLegacyAnalyticsPresent(long user) {
        assertThat(jdbc.queryForList("""
                select name from zzal_event where user_id = ? and name <> 'piece_maker_visit' order by name
                """, String.class, user)).containsExactly("auth_login", "pieceXmaker_visit", "zzal_hatch_open");
        assertThat(rows("zzal_anon_identity", "user_id", user)).isEqualTo(1);
    }

    private record Fixture(long user, long hypothesis) { }
}
