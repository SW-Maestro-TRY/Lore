package com.lore.webtoon.story;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 연재 2화부터의 작품 제목 — 1화 제목이다(2026-10-26).
 *
 * 화마다 AI 가 새 제목을 지어서 같은 작품이 화마다 다른 이름으로 보였다. 작품 제목은 1화 것 하나로 두고,
 * 화가 지은 제목은 소제목으로 쓴다. 진행 화면·알림이 이걸로 작품 제목을 얻는다(카드·완성 화면은 RunService).
 */
@Component
public class SeriesTitles {
    private final JdbcTemplate db;
    private final StoryStore stories;

    public SeriesTitles(JdbcTemplate db, StoryStore stories) {
        this.db = db;
        this.stories = stories;
    }

    /** 이 작업이 연재 2화 이후면 1화 제목, 아니면 null. */
    public String of(String jobPublicId) {
        if (jobPublicId == null) return null;
        try {
            List<String> runs = db.queryForList(
                    "select j.run_id from webtoon_series_chapter c join webtoon_job j on j.public_id = c.root_job "
                            + "where c.job_id = ? and j.run_id is not null", String.class, jobPublicId);
            if (runs.isEmpty()) return null;
            return stories.chosenOf(runs.getFirst()).map(WebtoonStory::displayTitle)
                    .filter(t -> !t.isBlank()).orElse(null);
        } catch (Exception e) {                  // noqa: 제목 하나 때문에 화면·알림을 막지 않는다
            return null;
        }
    }
}
