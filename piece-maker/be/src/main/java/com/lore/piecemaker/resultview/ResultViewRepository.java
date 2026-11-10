package com.lore.piecemaker.resultview;

import com.lore.piecemaker.retention.MeasurementRetention;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/** PostgreSQL 고유 키로 브라우저·기기·서버 인스턴스를 넘어 계정 중복을 막는다. */
@Repository
public class ResultViewRepository {
    private final JdbcTemplate jdbc;

    public ResultViewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record OwnedResult(String status, String judgement) { }

    /** 검사와 기록 사이에 운영자가 결과를 바꾸거나 가설을 지우지 못하게 한다. */
    Optional<OwnedResult> lockOwnedResult(long hypothesisId, long userId) {
        return jdbc.query("""
                select judgement_status, judgement::text from hypotheses
                where id = ? and user_id = ? for share
                """, (rs, row) -> new OwnedResult(rs.getString(1), rs.getString(2)), hypothesisId, userId)
                .stream().findFirst();
    }

    Optional<Instant> insertFirst(long userId, long hypothesisId) {
        // 기존 행을 잠가 조회 사이에 정기 파기가 끼어들지 못하게 한다. 만료된 시각은 반환하지 않는다.
        var existing = jdbc.query("select viewed_at from piece_maker_first_result_view where user_id = ? for update",
                (rs, row) -> rs.getTimestamp(1).toInstant(), userId).stream().findFirst();
        if (existing.isPresent()) {
            if (existing.get().isAfter(MeasurementRetention.availableSince(Instant.now()))) return Optional.empty();
            jdbc.update("delete from piece_maker_first_result_view where user_id = ?", userId);
        }
        return jdbc.query("""
                insert into piece_maker_first_result_view (user_id, hypothesis_id, viewed_at)
                values (?, ?, clock_timestamp())
                on conflict (user_id) do nothing
                returning viewed_at
                """, (rs, row) -> rs.getTimestamp(1).toInstant(), userId, hypothesisId)
                .stream().findFirst();
    }

    Instant firstViewedAt(long userId) {
        // 별도 SELECT여야 동시 INSERT가 끝난 뒤 커밋된 행을 READ COMMITTED에서 볼 수 있다.
        return jdbc.queryForObject("select viewed_at from piece_maker_first_result_view where user_id = ?",
                (rs, row) -> rs.getTimestamp(1).toInstant(), userId);
    }
}
