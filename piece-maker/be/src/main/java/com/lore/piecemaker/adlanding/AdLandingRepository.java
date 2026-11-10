package com.lore.piecemaker.adlanding;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AdLandingRepository {
    private final JdbcTemplate jdbc;
    public AdLandingRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    record Landing(UUID id, Long owner, Instant landedAt, AdLandingRequests.Attribution attribution, boolean ambiguous) { }
    private static final RowMapper<Landing> ROW = (rs, n) -> new Landing(
            rs.getObject("id", UUID.class), rs.getObject("owner_user_id", Long.class),
            rs.getTimestamp("landed_at").toInstant(), new AdLandingRequests.Attribution(
            rs.getString("utm_source"), rs.getString("utm_medium"), rs.getString("utm_campaign"),
            rs.getString("utm_content"), rs.getString("placement"), rs.getTimestamp("client_landed_at").toInstant().toEpochMilli()),
            rs.getBoolean("ambiguous"));

    void insert(String anonId, Long userId, AdLandingRequests.Capture input) {
        var a = input.attribution();
        jdbc.update("""
                insert into piece_maker_ad_landing
                (id, anon_id, request_key, owner_user_id, client_landed_at, utm_source, utm_medium,
                 utm_campaign, utm_content, placement, claimed_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, case when ?::bigint is null then null else clock_timestamp() end)
                on conflict (anon_id, request_key) do nothing
                """, UUID.randomUUID(), anonId, input.requestKey(), userId,
                Timestamp.from(Instant.ofEpochMilli(a.firstAdLandedAt())), a.utmSource(), a.utmMedium(),
                a.utmCampaign(), a.utmContent(), a.placement(), userId);
    }

    Landing lockRequest(String anonId, UUID key) {
        return jdbc.queryForObject("select * from piece_maker_ad_landing where anon_id = ? and request_key = ? for update", ROW, anonId, key);
    }

    Optional<Landing> lockLanding(String anonId, UUID id) {
        return jdbc.query("select * from piece_maker_ad_landing where anon_id = ? and id = ? for update", ROW, anonId, id)
                .stream().findFirst();
    }

    void claim(UUID id, long userId) {
        jdbc.update("update piece_maker_ad_landing set owner_user_id = ?, claimed_at = clock_timestamp() where id = ?", userId, id);
    }

    void markAmbiguous(UUID id) {
        jdbc.update("update piece_maker_ad_landing set ambiguous = true, ambiguous_at = coalesce(ambiguous_at, clock_timestamp()) where id = ?", id);
    }
}
