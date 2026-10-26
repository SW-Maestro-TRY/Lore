package com.lore.webtoon.community;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 연재 모듈이 함께 배포되면 그 장부를 사용한다. 모듈이 없는 환경은 단편이다. */
@Component
public class JdbcSeriesIdentity implements SeriesIdentity {
    private final JdbcTemplate db;
    public JdbcSeriesIdentity(JdbcTemplate db) { this.db = db; }
    public Identity of(String jobId) {
        if (!Boolean.TRUE.equals(db.queryForObject("select to_regclass('webtoon_series_chapter') is not null", Boolean.class)))
            return new Identity(jobId, 1);
        return db.query("select root_job,episode_number from webtoon_series_chapter where job_id=?",
                (r,n) -> new Identity(r.getString(1),r.getInt(2)), jobId).stream()
                .findFirst().orElse(new Identity(jobId,1));
    }
}
