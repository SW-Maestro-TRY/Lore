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
 * 만들기는 기존 /nh/create 를 그대로 쓴다 — 화면이 세계의 lorebook 과 사건 text(settings)를 실어 보내므로
 * 값·크레딧·안전 검사가 일반 만들기와 같다.
 */
@Component
public class PlayCatalog {

    private static final String FILE = "play/worlds.json";

    private final List<Map<String, Object>> worlds;

    public PlayCatalog(ObjectMapper json) {
        try (InputStream in = new ClassPathResource(FILE).getInputStream()) {
            Map<String, List<Map<String, Object>>> doc = json.readValue(in, new TypeReference<>() {});
            this.worlds = List.copyOf(doc.get("worlds"));
        } catch (IOException e) {
            throw new UncheckedIOException(FILE + " 을 읽지 못했습니다", e);
        }
    }

    public List<Map<String, Object>> worlds() {
        return worlds;
    }
}
