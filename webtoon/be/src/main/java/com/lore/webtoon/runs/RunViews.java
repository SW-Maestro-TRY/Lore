package com.lore.webtoon.runs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 작품마다 조회수 — 둘러보기의 「조회순」 정렬에 쓴다(2026-10-26).
 *
 * 따로 세는 칸을 두지 않고 이미 남는 행동 기록(webtoon_event)의 완성 화면 열기
 * (page_view · view=result · props.run)를 센다. 같은 사람이 여러 번 열어도 한 번 — 로그인했으면
 * 계정, 아니면 브라우저(uid)로 가른다. 기록은 1년 보관이라 조회수도 최근 1년 것이다.
 *
 * 둘러보기를 열 때마다 기록 표를 훑지 않도록 5분 동안 들고 있는다.
 */
@Component
public class RunViews {
    private static final Logger log = LoggerFactory.getLogger(RunViews.class);
    private static final long KEEP_MS = 5 * 60 * 1000L;

    private final JdbcTemplate db;
    private volatile Map<String, Long> cached = Map.of();
    private volatile long cachedAt = 0;

    public RunViews(JdbcTemplate db) {
        this.db = db;
    }

    /** 작품 번호 → 본 사람 수. 기록을 못 읽으면 빈 표 — 정렬만 흐트러지고 목록은 뜬다. */
    public Map<String, Long> all() {
        long now = System.currentTimeMillis();
        if (now - cachedAt < KEEP_MS) {
            return cached;
        }
        Map<String, Long> out = new HashMap<>();
        try {
            /* props 는 글(TEXT) 칸이라 JSON 으로 바꾸지 않고 run 값만 뽑는다 — 한 줄이 깨져 있어도 전체가 안 막힌다. */
            db.query("""
                    select run, count(distinct who) as n from (
                      select substring(props from '"run":"([^"]+)"') as run,
                             coalesce('u' || user_id::text, 'b' || uid) as who
                      from webtoon_event
                      where name = 'page_view' and view = 'result'
                    ) v where run is not null and who is not null group by run
                    """, rs -> { out.put(rs.getString("run"), rs.getLong("n")); });
        } catch (Exception e) {
            log.warn("조회수를 못 셌다: {}", e.toString());
            return cached;
        }
        cached = out;
        cachedAt = now;
        return out;
    }
}
