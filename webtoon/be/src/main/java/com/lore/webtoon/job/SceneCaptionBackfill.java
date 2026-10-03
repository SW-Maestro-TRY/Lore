package com.lore.webtoon.job;

import com.lore.webtoon.story.StoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 장면 설명이 비어 있는 옛 작품을 서버가 뜰 때 채운다(#607).
 *
 * <h2>왜 필요한가</h2>
 *
 * 「확인하고 만들기」·「바로 만들기」로 만든 작품은 나눈 장면이 {@code scenes.json} 에만 있고
 * {@code webtoon_story.scenes_json} 에는 안 적혀서, 편집실에 「이 장의 장면 설명이 없어요」가 떴다.
 * 고친 뒤 새로 만드는 작품은 알아서 적히지만, 이미 만들어진 작품은 이것이 한 번 채운다.
 *
 * 작품 폴더에 {@code scenes.json} 이 남아 있는 것만 채운다. 폴더가 치워졌으면 건너뛴다. 이미 채워진
 * 작품은 대상이 아니므로 다시 돌아도 같은 결과다. 실패해도 서버 기동을 막지 않는다.
 */
@Component
public class SceneCaptionBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SceneCaptionBackfill.class);

    private final StoryStore stories;
    private final JobRunner runner;
    private final boolean on;

    public SceneCaptionBackfill(StoryStore stories, JobRunner runner,
                                @Value("${lore.webtoon.backfill-scene-captions:true}") boolean on) {
        this.stories = stories;
        this.runner = runner;
        this.on = on;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!on) {
            return;
        }
        try {
            int filled = 0;
            List<String> targets = stories.runIdsWithoutScenes();
            for (String runId : targets) {
                List<String> captions = runner.sceneCaptions(runId);
                if (!captions.isEmpty()) {
                    stories.setScenes(runId, captions);
                    filled++;
                }
            }
            if (filled > 0) {
                log.info("장면 설명이 비어 있던 작품 {}편을 작품 폴더의 장면으로 채웠습니다", filled);
            }
        } catch (Exception e) {             // noqa: 기동을 막지 않는다
            log.warn("장면 설명 채우기에 실패했습니다", e);
        }
    }
}
