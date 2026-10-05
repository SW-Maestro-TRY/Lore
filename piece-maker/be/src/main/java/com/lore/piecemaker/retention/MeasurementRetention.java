package com.lore.piecemaker.retention;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/** 최대 365일 정책: 364일에 만료시켜 정기 삭제에 하루의 여유를 둔다. */
@Service
public class MeasurementRetention {
    public static final Duration AVAILABLE_FOR = Duration.ofDays(364);
    private final JdbcTemplate jdbc;

    public MeasurementRetention(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public static Instant availableSince(Instant now) { return now.minus(AVAILABLE_FOR); }

    /** 수집 스위치와 독립적이다. PM 기록만 삭제하며 원본 가설·공통 식별 연결은 건드리지 않는다. */
    @Transactional
    public Counts purgeExpired() {
        Instant now = jdbc.queryForObject("select clock_timestamp()", (rs, row) -> rs.getTimestamp(1).toInstant());
        Timestamp cutoff = Timestamp.from(availableSince(now));
        int landings = jdbc.update("delete from piece_maker_ad_landing where landed_at <= ?", cutoff);
        int views = jdbc.update("delete from piece_maker_first_result_view where viewed_at <= ?", cutoff);
        int gaps = jdbc.update("delete from piece_maker_measurement_gap where recorded_at <= ?", cutoff);
        int events = jdbc.update("""
                delete from zzal_event
                where left(name, length('piece_maker_')) = 'piece_maker_' and received_at <= ?
                """, cutoff);
        return new Counts(landings, views, gaps, events);
    }

    public record Counts(int landings, int views, int gaps, int events) { }
}
