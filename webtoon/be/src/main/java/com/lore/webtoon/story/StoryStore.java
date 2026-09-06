package com.lore.webtoon.story;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 지어진 이야기를 DB 에 옮겨 담고, 제품이 그것만 보게 한다.
 *
 * <b>하네스 폴더를 안 읽고도</b> 제목 · 줄거리 · 장면 설명을 낼 수 있게 하는
 * 것이 이 클래스의 전부다. 그래야 그 폴더가 작업대가 되고, 다 끝나면 지워도
 * 되는 것이 된다.
 */
@Service
public class StoryStore {

    private static final Logger log = LoggerFactory.getLogger(StoryStore.class);

    private final WebtoonStoryRepository stories;
    private final ObjectMapper mapper = new ObjectMapper();

    public StoryStore(WebtoonStoryRepository stories) {
        this.stories = stories;
    }

    /**
     * 후보 넷을 통째로 적는다. 이미 적힌 작품이면 아무 일도 안 한다 —
     * 같은 이야기를 두 번 적을 이유가 없고, 두 번 적으면 어느 것이 진짜인지
     * 알 수 없다.
     *
     * @param directions {@code directions.json} 을 읽은 그대로
     * @return 이번에 적은 줄 수
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int save(String runId, List<?> directions) {
        if (runId == null || runId.isBlank() || directions == null || directions.isEmpty()) {
            return 0;
        }
        if (stories.existsByRunId(runId)) {
            return 0;
        }
        Instant now = Instant.now();
        List<WebtoonStory> fresh = new ArrayList<>();
        for (Object one : directions) {
            JsonNode node = mapper.valueToTree(one);
            int n = node.path("n").asInt(0);
            if (n <= 0) {
                continue;
            }
            fresh.add(WebtoonStory.of(runId, n,
                    text(node, "title"), text(node, "genre"), text(node, "plot"),
                    json(node, "scenes"), json(node, "cast"), now));
        }
        stories.saveAll(fresh);
        log.info("이야기를 적었습니다 (run={}, 후보 {}개)", runId, fresh.size());
        return fresh.size();
    }

    /** 사람이 고른 것을 표시한다. 이미 다른 것이 표시돼 있으면 옮긴다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void choose(String runId, int n) {
        for (WebtoonStory one : stories.findByRunIdOrderByNAsc(runId)) {
            boolean want = one.getN() == n;
            if (one.isChosen() != want) {
                one.choose(want);
                stories.save(one);
            }
        }
    }

    /** 이 작품이 된 이야기. 아직 안 골랐거나 안 옮겨 온 작품이면 비어 있다. */
    @Transactional(readOnly = true)
    public Optional<WebtoonStory> chosenOf(String runId) {
        return stories.findByRunIdAndChosenTrue(runId);
    }

    /** 후보 전부. 고르는 화면이 쓴다. */
    @Transactional(readOnly = true)
    public List<WebtoonStory> candidatesOf(String runId) {
        return stories.findByRunIdOrderByNAsc(runId);
    }

    /**
     * 장면 목록. 완성본에서 그림을 누르면 뜨는 설명이 여기서 나온다.
     *
     * 못 읽으면 빈 목록 — 설명이 안 뜰 뿐이고 읽는 데는 지장이 없다.
     */
    @Transactional(readOnly = true)
    public List<String> scenesOf(String runId) {
        return chosenOf(runId).map(story -> {
            try {
                JsonNode got = mapper.readTree(
                        story.getScenesJson() == null ? "[]" : story.getScenesJson());
                List<String> out = new ArrayList<>();
                got.forEach(node -> out.add(node.asText()));
                return out;
            } catch (Exception e) {                 // noqa: 설명 하나 때문에 읽기를 막지 않는다
                log.warn("장면을 읽지 못했습니다 (run={})", runId, e);
                return List.<String>of();
            }
        }).orElse(List.of());
    }

    private String text(JsonNode node, String field) {
        JsonNode got = node.path(field);
        return got.isTextual() ? got.asText() : null;
    }

    private String json(JsonNode node, String field) {
        JsonNode got = node.path(field);
        return got.isMissingNode() || got.isNull() ? null : got.toString();
    }
}
