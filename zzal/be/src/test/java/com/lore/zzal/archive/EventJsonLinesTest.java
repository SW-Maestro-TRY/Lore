package com.lore.zzal.archive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 보관 파일의 생김새 — 한 줄에 하나, gzip, 칸이 하나도 안 빠진다. */
@DisplayName("보관 파일 — JSON Lines + gzip")
class EventJsonLinesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static EventRow row(long id, Long userId) {
        return new EventRow(id, "zzal_care", "anon-abc", userId, "{\"action\":\"feed\"}",
                "/zzal", "https://lorecomic.com/z", "kakao", "mobile", "default",
                Instant.parse("2026-09-14T01:02:03Z"), Instant.parse("2026-09-14T01:02:04Z"));
    }

    @Test
    @DisplayName("★ 원본의 칸을 하나도 안 버린다 — 보관은 나중에 무엇을 볼지 모르는 채로 뜬다")
    void everyColumnSurvives() throws Exception {
        JsonNode node = JSON.readTree(EventJsonLines.line(row(42, 7L)));

        assertThat(node.get("id").asLong()).isEqualTo(42);
        assertThat(node.get("name").asText()).isEqualTo("zzal_care");
        assertThat(node.get("anon_id").asText()).isEqualTo("anon-abc");
        assertThat(node.get("user_id").asLong()).isEqualTo(7);
        assertThat(node.get("props").asText()).isEqualTo("{\"action\":\"feed\"}");
        assertThat(node.get("path").asText()).isEqualTo("/zzal");
        assertThat(node.get("referrer").asText()).isEqualTo("https://lorecomic.com/z");
        assertThat(node.get("source").asText()).isEqualTo("kakao");
        assertThat(node.get("device").asText()).isEqualTo("mobile");
        assertThat(node.get("variant").asText()).isEqualTo("default");
        assertThat(node.get("occurred_at").asText()).isEqualTo("2026-09-14T01:02:03Z");
        assertThat(node.get("received_at").asText()).isEqualTo("2026-09-14T01:02:04Z");
    }

    @Test
    @DisplayName("★ 비로그인은 user_id 가 null 로 남는다 — 0 이면 0번 사용자가 한 것처럼 읽힌다")
    void anonymousStaysNull() throws Exception {
        JsonNode node = JSON.readTree(EventJsonLines.line(row(1, null)));

        assertThat(node.has("user_id")).isTrue();
        assertThat(node.get("user_id").isNull()).isTrue();
    }

    @Test
    @DisplayName("★ props 는 문자열 그대로 — 줄마다 모양이 달라지면 읽는 쪽이 칸 타입을 못 정한다")
    void propsStayAString() throws Exception {
        JsonNode node = JSON.readTree(EventJsonLines.line(row(1, 1L)));

        assertThat(node.get("props").isTextual()).isTrue();
    }

    @Test
    @DisplayName("★ 줄 수만큼 줄이 나온다 — 통째로 감싼 배열이 아니라 줄 단위다")
    void gzipRoundTrip() throws Exception {
        Path tmp = Files.createTempFile("zzal-events-test-", ".jsonl.gz");
        try {
            long bytes = EventJsonLines.writeGzip(List.of(row(1, null), row(2, 9L), row(3, null)), tmp);

            assertThat(bytes).isEqualTo(Files.size(tmp)).isPositive();

            String text;
            try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(Files.readAllBytes(tmp)))) {
                text = new String(gz.readAllBytes(), StandardCharsets.UTF_8);
            }
            assertThat(text).endsWith("\n");
            List<String> lines = text.lines().toList();
            assertThat(lines).hasSize(3);
            for (String line : lines) {
                assertThat(JSON.readTree(line).get("id").asLong()).isPositive();
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
