package com.lore.webtoon.character;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「랜덤으로 만들어보기」 예시 목록(random_pool.json)이 칸을 채울 수 있는 모양인지 본다.
 * 스프링을 안 띄운다.
 */
class RandomPoolTest {

    private static final Path ROOT = Path.of(System.getProperty("user.dir"));
    private static final Path POOL = ROOT.resolve("webtoon/ai/new_harness/prompt/random_pool.json");
    private static final Path WORLDS = ROOT.resolve("webtoon/ai/story-harness/worlds.json");

    @Test
    @DisplayName("예시마다 이름·설명이 있고 세계관이 실제 프리셋 키다")
    void 예시_모양() throws Exception {
        JsonNode presets = new ObjectMapper().readTree(Files.readString(POOL)).path("presets");
        JsonNode worlds = new ObjectMapper().readTree(Files.readString(WORLDS)).path("presets");
        assertThat(presets.size()).isGreaterThanOrEqualTo(30);
        for (JsonNode p : presets) {
            assertThat(p.path("name").asText()).isNotBlank();
            assertThat(p.path("description").asText()).isNotBlank();
            assertThat(worlds.has(p.path("world").asText()))
                    .as("세계관 키 %s (%s)", p.path("world").asText(), p.path("name").asText())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("사람 예시가 40% 이상이다 — 몇 번 눌러도 사람이 안 나오던 문제")
    void 사람_비율() throws Exception {
        JsonNode presets = new ObjectMapper().readTree(Files.readString(POOL)).path("presets");
        long human = 0;
        for (JsonNode p : presets) {
            if ("human".equals(p.path("kind").asText())) {
                human++;
            }
        }
        assertThat(human / (double) presets.size()).isGreaterThanOrEqualTo(0.4);
    }
}
