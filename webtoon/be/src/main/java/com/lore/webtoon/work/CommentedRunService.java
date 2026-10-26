package com.lore.webtoon.work;

import com.lore.webtoon.runs.RunService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 내가 댓글을 단 웹툰 — 마이페이지 「댓글 단 웹툰」(2026-10-26).
 *
 * 최근에 댓글을 단 작품부터. 지운 댓글·가려진 댓글만 남은 작품은 뺀다. 모양은 찜한 웹툰과 같은
 * 둘러보기 카드다 — 그림이 없거나 사라진 작품은 {@link RunService#cardOf} 가 null 이라 빠진다.
 */
@Service
public class CommentedRunService {

    /** 한 번에 보여 줄 만큼 — 더 많아도 오래된 것까지 다 그리지 않는다. */
    static final int LIMIT = 100;

    private final JdbcTemplate db;
    private final RunService runs;

    public CommentedRunService(JdbcTemplate db, RunService runs) {
        this.db = db;
        this.runs = runs;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> commentedCards(Long userId) {
        if (userId == null) {
            return List.of();
        }
        List<String> ids = db.queryForList("""
                select run_id from webtoon_comment
                where user_id=? and not deleted and not hidden
                group by run_id order by max(created_at) desc limit ?""", String.class, userId, LIMIT);
        List<Map<String, Object>> out = new ArrayList<>();
        for (String runId : ids) {
            Map<String, Object> card = runs.cardOf(runId);
            if (card != null) {
                out.add(card);
            }
        }
        return out;
    }
}
