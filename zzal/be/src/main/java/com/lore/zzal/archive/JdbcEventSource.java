package com.lore.zzal.archive;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * {@code zzal_event} 를 id 순으로 읽어 오는 곳 — SELECT 하나뿐이다.
 *
 * <h3>★ 페이지네이션을 offset 으로 하지 않는다</h3>
 * {@code offset} 은 건너뛸 줄을 실제로 읽고 버리므로 뒤로 갈수록 느려지고, 그 사이에 앞쪽에
 * 줄이 들어오면 <b>한 줄이 두 번 나오거나 한 줄이 빠진다.</b> 기준점을 id 로 잡으면 그런 일이 없다.
 */
@Component
public class JdbcEventSource implements EventSource {

    private static final String SQL = """
            select id, name, anon_id, user_id, props, path, referrer, source, device, variant,
                   occurred_at, received_at
              from zzal_event
             where id > ?
               and received_at <= ?
             order by id
             limit ?
            """;

    private final JdbcTemplate jdbc;

    public JdbcEventSource(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<EventRow> readAfter(long afterId, Instant cutoff, int limit) {
        return jdbc.query(SQL, JdbcEventSource::map, afterId, Timestamp.from(cutoff), limit);
    }

    private static EventRow map(ResultSet rs, int rowNum) throws SQLException {
        // ★ wasNull() 은 <b>바로 앞에 읽은 칸</b>을 가리킨다. 다른 칸을 하나라도 읽고 나서 물으면
        //   엉뚱한 칸의 답이 온다 — 비로그인 기록의 user_id 가 0 으로 보관될 자리다.
        long rawUserId = rs.getLong("user_id");
        Long userId = rs.wasNull() ? null : rawUserId;
        return new EventRow(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("anon_id"),
                userId,
                rs.getString("props"),
                rs.getString("path"),
                rs.getString("referrer"),
                rs.getString("source"),
                rs.getString("device"),
                rs.getString("variant"),
                instant(rs, "occurred_at"),
                instant(rs, "received_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
