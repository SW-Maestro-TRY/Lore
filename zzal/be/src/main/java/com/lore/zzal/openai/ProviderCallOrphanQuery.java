package com.lore.zzal.openai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 고아 호출 — 시작 행({@code zzal_provider_call_started})만 있고 끝 행({@code zzal_provider_usage})이 없는 호출(설계 1-3).
 * JVM 이 호출 도중 죽으면 끝 기록이 안 남는다. 실제 과금 여부는 청구 대조(1-5)가 가린다.
 *
 * ★ 끝 행의 event_id 가 callId 다. 아직 돌고 있을 수 있는 최근 호출은 {@code startedBefore} 로 뺀다.
 */
@Component
public class ProviderCallOrphanQuery {

    private static final String WHERE = """
            FROM zzal_event s
            WHERE s.name = 'zzal_provider_call_started' AND s.event_origin = 'server'
              AND s.occurred_at >= ? AND s.occurred_at < ?
              AND NOT EXISTS (SELECT 1 FROM zzal_event u
                              WHERE u.name = 'zzal_provider_usage' AND u.event_origin = 'server'
                                AND u.event_id = (s.props::jsonb ->> 'call_id'))
            """;

    private final JdbcTemplate jdbc;

    public ProviderCallOrphanQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @param since 이 시각 이후 시작한 호출만 · @param startedBefore 이 시각 전에 시작한 호출만(진행 중 제외) */
    public List<String> orphanCallIds(Instant since, Instant startedBefore) {
        return jdbc.queryForList("SELECT s.props::jsonb ->> 'call_id' " + WHERE + " ORDER BY s.occurred_at",
                String.class, Timestamp.from(since), Timestamp.from(startedBefore));
    }

    public long countOrphans(Instant since, Instant startedBefore) {
        Long n = jdbc.queryForObject("SELECT count(*) " + WHERE, Long.class, Timestamp.from(since), Timestamp.from(startedBefore));
        return n == null ? 0 : n;
    }
}
