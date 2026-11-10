package com.lore.zzal.returning;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class ReturningGuideRepository {
    private final JdbcTemplate db;
    public ReturningGuideRepository(JdbcTemplate db) { this.db = db; }
    public record Stored(long petId, int step, Instant completedAt) { }
    public Optional<Stored> find(Long owner, String version, boolean lock) {
        return db.query("SELECT pet_id,step,completed_at FROM zzal_returning_guide WHERE user_id=? AND version=?"
                + (lock ? " FOR UPDATE" : ""), (rs, n) -> new Stored(rs.getLong("pet_id"), rs.getInt("step"),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant()), owner, version).stream().findFirst();
    }
    public void start(Long owner, String version, Long pet, Instant now) {
        db.update("INSERT INTO zzal_returning_guide(user_id,version,pet_id,created_at,updated_at) VALUES(?,?,?,?,?) ON CONFLICT(user_id,version) DO NOTHING",
                owner, version, pet, Timestamp.from(now), Timestamp.from(now));
    }
    public void advance(Long owner, String version, Long pet, int step, boolean complete, Instant now) {
        db.update("UPDATE zzal_returning_guide SET pet_id=?,step=GREATEST(step,?),completed_at=CASE WHEN ? THEN COALESCE(completed_at,?) ELSE completed_at END,updated_at=? WHERE user_id=? AND version=?",
                pet, step, complete, Timestamp.from(now), Timestamp.from(now), owner, version);
    }
}
