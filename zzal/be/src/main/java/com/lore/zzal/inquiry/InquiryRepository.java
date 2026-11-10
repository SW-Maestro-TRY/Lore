package com.lore.zzal.inquiry;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class InquiryRepository {
    private final JdbcTemplate db;
    public InquiryRepository(JdbcTemplate db) { this.db = db; }

    public record Stored(long id, Long petId, String body, String locale, Instant createdAt, Long parentId) {
        public Stored(long id, Long petId, String body, String locale, Instant createdAt) { this(id, petId, body, locale, createdAt, null); }
    }

    /** 한 행 전부(답장 칸 포함). 화면·관리자 응답은 이것을 골라 담는다. */
    public record Row(long id, long userId, Long petId, Long parentId, String body, String locale, Instant createdAt,
                      String replyText, Instant repliedAt, Long repliedBy, Instant replySeenAt) { }

    private static Instant at(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column); return t == null ? null : t.toInstant();
    }
    private static final String COLUMNS = "id,user_id,pet_id,parent_id,body,locale,created_at,reply_text,replied_at,replied_by,reply_seen_at";
    private static final RowMapper<Row> ROW = (rs, n) -> new Row(rs.getLong("id"), rs.getLong("user_id"),
            rs.getObject("pet_id", Long.class), rs.getObject("parent_id", Long.class), rs.getString("body"),
            rs.getString("locale"), at(rs, "created_at"), rs.getString("reply_text"), at(rs, "replied_at"),
            rs.getObject("replied_by", Long.class), at(rs, "reply_seen_at"));

    public void insertIfAbsent(Long owner, InquiryRequests.Submit request, Instant now) {
        // DO NOTHING keeps concurrent duplicates usable in the same PostgreSQL transaction.
        db.update("""
                INSERT INTO zzal_inquiry(user_id,pet_id,request_id,body,locale,created_at,parent_id)
                VALUES(?,?,?,?,?,?,?) ON CONFLICT(user_id,request_id) DO NOTHING
                """, owner, request.petId(), request.requestId(), request.body(), request.locale().name(), Timestamp.from(now),
                request.parentId());
    }

    public Optional<Stored> find(Long owner, UUID requestId) {
        return db.query("SELECT id,pet_id,body,locale,created_at,parent_id FROM zzal_inquiry WHERE user_id=? AND request_id=?",
                (rs, index) -> new Stored(rs.getLong("id"), rs.getObject("pet_id", Long.class),
                        rs.getString("body"), rs.getString("locale"), rs.getTimestamp("created_at").toInstant(),
                        rs.getObject("parent_id", Long.class)),
                owner, requestId).stream().findFirst();
    }

    public boolean ownedBy(long id, Long owner) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM zzal_inquiry WHERE id=? AND user_id=?)",
                Boolean.class, id, owner));
    }

    /** 본인 문의 전부, 오래된 것부터(대화 기록형 화면이 위에서 아래로 그린다). 최근 200건까지. */
    public List<Row> listMine(Long owner) {
        return db.query("SELECT " + COLUMNS + " FROM (SELECT * FROM zzal_inquiry WHERE user_id=? ORDER BY created_at DESC, id DESC LIMIT 200) t"
                + " ORDER BY created_at, id", ROW, owner);
    }

    /** 본인 문의의 답장을 봤다고 적는다. 남의 것이거나 답장이 없거나 이미 봤으면 아무것도 안 바뀐다. */
    public int markSeen(long id, Long owner, Instant now) {
        return db.update("UPDATE zzal_inquiry SET reply_seen_at=? WHERE id=? AND user_id=? AND reply_text IS NOT NULL AND reply_seen_at IS NULL",
                Timestamp.from(now), id, owner);
    }

    public Optional<Row> findById(long id) {
        return db.query("SELECT " + COLUMNS + " FROM zzal_inquiry WHERE id=?", ROW, id).stream().findFirst();
    }

    /** 답장은 한 번만 — 이미 있으면 0. */
    public int reply(long id, String text, Long admin, Instant now) {
        return db.update("UPDATE zzal_inquiry SET reply_text=?, replied_at=?, replied_by=? WHERE id=? AND reply_text IS NULL",
                text, Timestamp.from(now), admin, id);
    }

    /** 관리자 목록 한 줄 — 펫 이름은 지금 이름(펫이 지워졌으면 null). */
    public record AdminRow(long id, long userId, Long petId, String petName, String locale, String body,
                           boolean replied, Instant createdAt, Instant repliedAt, Long parentId) { }

    private static final String ADMIN_SELECT = """
            SELECT i.id,i.user_id,i.pet_id,p.name AS pet_name,i.locale,i.body,i.reply_text IS NOT NULL AS replied,
                   i.created_at,i.replied_at,i.parent_id
            FROM zzal_inquiry i LEFT JOIN zzal_pet p ON p.id=i.pet_id
            """;
    private static final RowMapper<AdminRow> ADMIN_ROW = (rs, n) -> new AdminRow(rs.getLong("id"), rs.getLong("user_id"),
            rs.getObject("pet_id", Long.class), rs.getString("pet_name"), rs.getString("locale"), rs.getString("body"),
            rs.getBoolean("replied"), at(rs, "created_at"), at(rs, "replied_at"), rs.getObject("parent_id", Long.class));

    /** 최근 days 일, 미답 먼저 → 그 안에서 최신순. */
    public List<AdminRow> adminList(int days, Instant now) {
        return db.query(ADMIN_SELECT + " WHERE i.created_at >= ? ORDER BY (i.reply_text IS NULL) DESC, i.created_at DESC, i.id DESC LIMIT 500",
                ADMIN_ROW, Timestamp.from(now.minus(java.time.Duration.ofDays(days))));
    }

    public Optional<AdminRow> adminFind(long id) {
        return db.query(ADMIN_SELECT + " WHERE i.id=?", ADMIN_ROW, id).stream().findFirst();
    }

    /** 이 문의가 속한 사슬 전체(맨 앞 문의부터 그 뒤로 이어 쓴 것까지), 오래된 것부터. */
    public List<Row> chain(long id) {
        return db.query("""
                WITH RECURSIVE up AS (
                    SELECT id,parent_id,0 AS depth FROM zzal_inquiry WHERE id=?
                    UNION ALL SELECT i.id,i.parent_id,up.depth+1 FROM zzal_inquiry i JOIN up ON i.id=up.parent_id WHERE up.depth<100
                ), root AS (SELECT id FROM up WHERE parent_id IS NULL ORDER BY depth DESC LIMIT 1),
                down AS (
                    SELECT i.id,0 AS depth FROM zzal_inquiry i JOIN root ON i.id=root.id
                    UNION ALL SELECT i.id,down.depth+1 FROM zzal_inquiry i JOIN down ON i.parent_id=down.id WHERE down.depth<100
                )
                SELECT z.* FROM zzal_inquiry z WHERE z.id IN (SELECT id FROM down) ORDER BY z.created_at, z.id
                """, ROW, id);
    }

    public record Feedback(long id, long userId, long petId, String petName, int rating, List<String> tags, String text, Instant createdAt) { }
    private static final RowMapper<Feedback> FEEDBACK = (rs, n) -> {
        String tags = rs.getString("tags");
        return new Feedback(rs.getLong("id"), rs.getLong("user_id"), rs.getLong("pet_id"), rs.getString("pet_name"),
                rs.getInt("rating"), tags == null || tags.isBlank() ? List.of() : List.of(tags.split(",")),
                rs.getString("text"), at(rs, "created_at"));
    };

    public List<Feedback> feedbackOfUser(long userId) {
        return db.query("""
                SELECT f.id,f.user_id,f.pet_id,p.name AS pet_name,f.rating,f.tags,f.text,f.created_at
                FROM zzal_feedback f LEFT JOIN zzal_pet p ON p.id=f.pet_id WHERE f.user_id=? ORDER BY f.created_at DESC, f.id DESC LIMIT 50
                """, FEEDBACK, userId);
    }

    public List<Feedback> feedbackSince(int days, Instant now) {
        return db.query("""
                SELECT f.id,f.user_id,f.pet_id,p.name AS pet_name,f.rating,f.tags,f.text,f.created_at
                FROM zzal_feedback f LEFT JOIN zzal_pet p ON p.id=f.pet_id WHERE f.created_at >= ? ORDER BY f.created_at DESC, f.id DESC LIMIT 500
                """, FEEDBACK, Timestamp.from(now.minus(java.time.Duration.ofDays(days))));
    }
}
