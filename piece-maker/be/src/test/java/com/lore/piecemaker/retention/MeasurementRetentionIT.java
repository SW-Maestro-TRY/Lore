package com.lore.piecemaker.retention;

import com.lore.piecemaker.support.PieceMakerIntegrationTest;
import com.lore.piecemaker.support.PieceMakerItSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@PieceMakerIntegrationTest
@TestPropertySource(properties = {"app.analytics.enabled=false", "lore.piece-maker.measurement.retention-enabled=false"})
class MeasurementRetentionIT extends PieceMakerItSupport {
    @Autowired MeasurementRetention retention;
    @Autowired ApplicationContext context;
    private final String anon = UUID.randomUUID().toString().replace("-", "");

    @AfterEach
    void cleanFixtures() {
        jdbc.update("delete from zzal_event where anon_id = ?", anon);
        jdbc.update("delete from zzal_anon_identity where anon_id = ?", anon);
        jdbc.update("delete from piece_maker_ad_landing where anon_id = ?", anon);
    }

    @Test
    void periodDeletionIncludesAnonymousRowsWhilePreservingOtherServiceAndSharedIdentity() {
        long owner = newUserId();
        long recentOwner = newUserId();
        Instant expired = Instant.now().minus(MeasurementRetention.AVAILABLE_FOR).minusSeconds(1);
        Instant recent = Instant.now().minus(MeasurementRetention.AVAILABLE_FOR).plusSeconds(3600);
        landing(null, expired); landing(owner, expired); landing(recentOwner, recent);
        view(owner, expired); view(recentOwner, recent);
        jdbc.update("insert into piece_maker_measurement_gap(user_id, recorded_at) values (?, ?), (?, ?)",
                owner, Timestamp.from(expired), recentOwner, Timestamp.from(recent));
        event(null, "piece_maker_visit", expired);
        event(owner, "piece_maker_visit", expired);
        event(owner, "piece_maker_visit", recent);
        for (String other : List.of("auth_login", "zzal_hatch_open", "pieceXmaker_visit")) event(owner, other, expired);
        jdbc.update("insert into zzal_anon_identity(anon_id, user_id, linked_at) values (?, ?, ?)", anon, owner, Timestamp.from(expired));

        var result = retention.purgeExpired();
        assertThat(result).isEqualTo(new MeasurementRetention.Counts(2, 1, 1, 2));
        assertThat(jdbc.queryForList("select name from zzal_event where anon_id = ? order by name", String.class, anon))
                .containsExactlyInAnyOrder("auth_login", "pieceXmaker_visit", "piece_maker_visit", "zzal_hatch_open");
        assertThat(jdbc.queryForObject("select count(*) from zzal_anon_identity where anon_id = ?", Long.class, anon)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from piece_maker_measurement_gap where user_id = ?", Long.class, owner)).isZero();
        assertThat(retention.purgeExpired()).isEqualTo(new MeasurementRetention.Counts(0, 0, 0, 0));
    }

    @Test
    void deletingOneActiveAccountDoesNotCreateAnOrphanOrBlockCascade() {
        long owner = newUserId();
        view(owner, Instant.now()); landing(owner, Instant.now());
        jdbc.update("delete from users where id = ?", owner);
        assertThat(jdbc.queryForObject("select count(*) from piece_maker_measurement_gap where user_id = ?", Long.class, owner)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from piece_maker_first_result_view where user_id = ?", Long.class, owner)).isZero();
    }

    @Test
    void failureRollsBackAllEarlierDeletes() {
        long owner = newUserId();
        Instant expired = Instant.now().minus(MeasurementRetention.AVAILABLE_FOR).minusSeconds(1);
        UUID id = landing(owner, expired); view(owner, expired);
        jdbc.execute("create table pm_retention_test_block(user_id bigint references piece_maker_first_result_view(user_id))");
        try {
            jdbc.update("insert into pm_retention_test_block values (?)", owner);
            assertThatThrownBy(retention::purgeExpired).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(jdbc.queryForObject("select count(*) from piece_maker_ad_landing where id = ?", Long.class, id)).isEqualTo(1);
        } finally {
            jdbc.execute("drop table pm_retention_test_block");
        }
    }

    @Test
    void destructiveScheduleRequiresItsOwnOptInEvenIfCollectionIsEnabledElsewhere() {
        assertThat(context.getBeansOfType(MeasurementRetentionJob.class)).isEmpty();
    }

    private UUID landing(Long owner, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into piece_maker_ad_landing(id,anon_id,request_key,owner_user_id,landed_at,client_landed_at,
                    utm_source,utm_medium,utm_campaign,utm_content,placement,claimed_at)
                values (?,?,?,?,?,?,'facebook','paid_social','retention_test','1','feed',?)
                """, id, anon, UUID.randomUUID(), owner, Timestamp.from(at), Timestamp.from(at),
                owner == null ? null : Timestamp.from(at));
        return id;
    }

    private void view(long owner, Instant at) {
        jdbc.update("insert into piece_maker_first_result_view(user_id,viewed_at) values (?,?)", owner, Timestamp.from(at));
    }

    private void event(Long owner, String name, Instant at) {
        // 사용자 시계가 새 값이어도 서버 수신 시각으로 만료되어야 한다.
        jdbc.update("insert into zzal_event(name,anon_id,user_id,occurred_at,received_at) values (?,?,?,now(),?)",
                name, anon, owner, Timestamp.from(at));
    }
}
