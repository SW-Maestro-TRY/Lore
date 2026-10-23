package com.lore.zzal.inquiry;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class InquiryRepository {
    private final JdbcTemplate db;
    public InquiryRepository(JdbcTemplate db) { this.db = db; }

    public record Stored(long id, Long petId, String body, String locale, Instant createdAt) { }

    public void insertIfAbsent(Long owner, InquiryRequests.Submit request, Instant now) {
        // DO NOTHING keeps concurrent duplicates usable in the same PostgreSQL transaction.
        db.update("""
                INSERT INTO zzal_inquiry(user_id,pet_id,request_id,body,locale,created_at)
                VALUES(?,?,?,?,?,?) ON CONFLICT(user_id,request_id) DO NOTHING
                """, owner, request.petId(), request.requestId(), request.body(), request.locale().name(), Timestamp.from(now));
    }

    public Optional<Stored> find(Long owner, UUID requestId) {
        return db.query("SELECT id,pet_id,body,locale,created_at FROM zzal_inquiry WHERE user_id=? AND request_id=?",
                (rs, index) -> new Stored(rs.getLong("id"), rs.getObject("pet_id", Long.class),
                        rs.getString("body"), rs.getString("locale"), rs.getTimestamp("created_at").toInstant()),
                owner, requestId).stream().findFirst();
    }
}
