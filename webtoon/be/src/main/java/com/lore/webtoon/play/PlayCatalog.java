package com.lore.webtoon.play;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 세계관 체험의 세계와 사건 목록. 운영자가 정해 두는 자료라 DB 없이 jar 안의 파일로 둔다.
 * 시작(/play/runs)은 서버가 세계 설정집과 사건을 골라 일반 만들기에 넘긴다.
 */
@Component
public class PlayCatalog {

    private static final String FILE = "play/worlds.json";

    private final List<Map<String, Object>> worlds;

    public PlayCatalog() {
        try (InputStream in = new ClassPathResource(FILE).getInputStream()) {
            Map<String, List<Map<String, Object>>> doc = new ObjectMapper().readValue(in, new TypeReference<>() {});
            this.worlds = List.copyOf(doc.get("worlds"));
        } catch (IOException e) {
            throw new UncheckedIOException(FILE + " 을 읽지 못했습니다", e);
        }
    }

    public List<Map<String, Object>> worlds() {
        return worlds;
    }

    public Map<String, Object> world(String id) {
        return worlds.stream().filter(w -> w.get("id").equals(id)).findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> event(Map<String, Object> world, String id) {
        if (world == null) {
            return null;
        }
        return ((List<Map<String, Object>>) world.get("events")).stream()
                .filter(e -> e.get("id").equals(id)).findFirst().orElse(null);
    }
}
