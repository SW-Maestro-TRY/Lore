package com.lore.webtoon.character;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「랜덤으로 만들어보기」가 존재를 고르는 방식. 실제 재료 파일(random_pool.json)로 굴린다.
 * 스프링을 안 띄운다.
 */
class RandomPoolTest {

    private static final Path POOL = Path.of(System.getProperty("user.dir"),
            "webtoon", "ai", "new_harness", "prompt", "random_pool.json");

    @Test
    @DisplayName("갈래 가중치대로 굴린다 — 사람이 절반쯤 나온다")
    void 갈래_가중치() throws Exception {
        JsonNode pool = new ObjectMapper().readTree(Files.readString(POOL));
        Random rnd = new Random(7);
        Map<String, Integer> count = new HashMap<>();
        int n = 10_000;
        for (int i = 0; i < n; i++) {
            JsonNode b = CharacterService.pickBeing(pool.path("beings"), pool.path("kind_weights"), rnd);
            count.merge(b.path("kind").asText(), 1, Integer::sum);
        }
        double human = count.getOrDefault("human", 0) / (double) n;
        int weightSum = 0;
        for (JsonNode w : pool.path("kind_weights")) {
            weightSum += w.asInt();
        }
        double expected = pool.path("kind_weights").path("human").asInt() / (double) weightSum;
        assertThat(human).isBetween(expected - 0.03, expected + 0.03);
        assertThat(count.keySet()).contains("human", "animal", "thing");
    }

    @Test
    @DisplayName("가중치가 없으면 예전처럼 존재마다 고르게 뽑는다")
    void 가중치_없음() throws Exception {
        JsonNode pool = new ObjectMapper().readTree(Files.readString(POOL));
        JsonNode b = CharacterService.pickBeing(pool.path("beings"), pool.path("없는_칸"), new Random(1));
        assertThat(b.path("being").asText()).isNotEmpty();
    }
}
