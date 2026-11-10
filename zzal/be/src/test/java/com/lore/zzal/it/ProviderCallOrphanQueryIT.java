package com.lore.zzal.it;

import com.lore.zzal.openai.ProviderCallOrphanQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-5 (설계 1-3): "시작만 있고 끝이 없는 호출"이 고아 쿼리에 잡힌다. 진짜 Postgres 의 zzal_event 로 본다.
 * (계획서 이름은 ProviderCallOrphanQueryTest — DB 가 있어야 하므로 레포 관례대로 IT.)
 */
@ZzalIntegrationTest
@DisplayName("고아 호출 쿼리 — started 만 있는 callId 가 잡힌다")
class ProviderCallOrphanQueryIT extends ZzalItSupport {

    @Autowired ProviderCallOrphanQuery orphans;

    private void started(String callId, Instant at) {
        jdbc.update("""
                INSERT INTO zzal_event (name, anon_id, props, source, occurred_at, received_at, event_id, schema_version, identity_status, event_origin)
                VALUES ('zzal_provider_call_started', '00000000000000000000000000000000', ?, 'server', ?, ?, ?, 2, 'server', 'server')
                """, "{\"call_id\":\"" + callId + "\",\"lane\":\"image\"}", java.sql.Timestamp.from(at), java.sql.Timestamp.from(at),
                UUID.nameUUIDFromBytes(("started:" + callId).getBytes()).toString());
    }

    private void usage(String callId, Instant at) {
        jdbc.update("""
                INSERT INTO zzal_event (name, anon_id, props, source, occurred_at, received_at, event_id, schema_version, identity_status, event_origin)
                VALUES ('zzal_provider_usage', '00000000000000000000000000000000', ?, 'server', ?, ?, ?, 2, 'server', 'server')
                """, "{\"call_id\":\"" + callId + "\"}", java.sql.Timestamp.from(at), java.sql.Timestamp.from(at), callId);
    }

    @Test
    void startedWithoutUsageIsAnOrphan() {
        Instant now = Instant.now();
        String finished = UUID.randomUUID().toString();
        String orphan = UUID.randomUUID().toString();
        String running = UUID.randomUUID().toString();
        started(finished, now.minus(Duration.ofMinutes(30)));
        usage(finished, now.minus(Duration.ofMinutes(29)));
        started(orphan, now.minus(Duration.ofMinutes(20)));
        started(running, now.minusSeconds(5));     // 아직 돌고 있을 수 있는 호출

        assertThat(orphans.orphanCallIds(now.minus(Duration.ofHours(1)), now.minus(Duration.ofMinutes(5))))
                .containsExactly(orphan);
        assertThat(orphans.countOrphans(now.minus(Duration.ofHours(1)), now.minus(Duration.ofMinutes(5)))).isEqualTo(1);
    }
}
