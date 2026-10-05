package com.lore.piecemaker.adreport;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class AdCohortRepository {
    private final JdbcTemplate jdbc;

    public AdCohortRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 최초 유입은 캠페인·조회 기간으로 거르기 전에 고른다. 재방문을 신규 획득으로 세지 않는다. */
    List<AccountLanding> firstLandings(String campaign, Instant from, Instant to, Instant asOf, Instant availableAfter) {
        return jdbc.query("""
                with known as (
                    select l.*, row_number() over (
                        partition by owner_user_id order by landed_at, id) as first_rank
                    from piece_maker_ad_landing l
                    where owner_user_id is not null and claimed_at <= ? and landed_at <= ?
                      and utm_source = 'facebook' and utm_medium = 'paid_social' and landed_at > ?
                )
                select l.owner_user_id, l.landed_at, l.utm_content, l.placement,
                    u.role, u.status, u.deleted_at, u.created_at as account_created_at,
                    exists (select 1 from piece_maker_measurement_gap g
                        where g.user_id = l.owner_user_id) as history_gap,
                    exists (select 1 from piece_maker_ad_landing a
                        where a.owner_user_id = l.owner_user_id
                          and a.ambiguous and a.ambiguous_at <= ?) as ambiguous,
                    exists (select 1 from hypotheses h
                        where h.user_id = l.owner_user_id and h.created_at < l.landed_at) as prior_submission,
                    v.viewed_at
                from known l
                join users u on u.id = l.owner_user_id
                left join piece_maker_first_result_view v on v.user_id = l.owner_user_id and v.viewed_at > ?
                where l.first_rank = 1 and l.utm_campaign = ? and l.landed_at >= ? and l.landed_at < ?
                order by l.landed_at, l.id
                """, (rs, row) -> new AccountLanding(rs.getLong("owner_user_id"),
                        rs.getTimestamp("landed_at").toInstant(), rs.getString("utm_content"),
                        rs.getString("placement"), rs.getString("role"), rs.getString("status"),
                        rs.getTimestamp("deleted_at") != null, rs.getBoolean("ambiguous"),
                        rs.getBoolean("prior_submission"),
                        rs.getTimestamp("viewed_at") == null ? null : rs.getTimestamp("viewed_at").toInstant(),
                        rs.getTimestamp("account_created_at").toInstant(), rs.getBoolean("history_gap")),
                Timestamp.from(asOf), Timestamp.from(asOf), Timestamp.from(availableAfter), Timestamp.from(asOf),
                Timestamp.from(availableAfter), campaign,
                Timestamp.from(from), Timestamp.from(to));
    }

    AdCohortReport.ReceiptCounts receiptCounts(String campaign, Instant from, Instant to, Instant asOf) {
        return jdbc.queryForObject("""
                select count(*) filter (where owner_user_id is null or claimed_at > ?) as unclaimed,
                    count(*) filter (where ambiguous and ambiguous_at <= ?) as ambiguous
                from piece_maker_ad_landing
                where utm_source = 'facebook' and utm_medium = 'paid_social' and utm_campaign = ?
                  and landed_at >= ? and landed_at < ? and landed_at <= ?
                """, (rs, row) -> new AdCohortReport.ReceiptCounts(rs.getLong("unclaimed"), rs.getLong("ambiguous")),
                Timestamp.from(asOf), Timestamp.from(asOf), campaign, Timestamp.from(from),
                Timestamp.from(to), Timestamp.from(asOf));
    }

    record AccountLanding(long userId, Instant landedAt, String adId, String placement,
                          String role, String status, boolean deleted, boolean ambiguous,
                          boolean priorSubmission, Instant viewedAt, Instant accountCreatedAt, boolean historyGap) { }
}
