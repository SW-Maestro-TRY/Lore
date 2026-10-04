package com.lore.webtoon.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * run 폴더의 {@code directions.json}(이야기 후보) 을 읽는다.
 *
 * 하네스가 이야기 단계에서 쓰는 파일이고, 배포 서버에서도 지우지 않고 남긴다
 * ({@link RunFiles} — 글·JSON 은 남긴다). 이야기 단계가 끝났을 때
 * ({@link JobRunner}) 와 서버가 다시 떠서 메모리의 후보가 비었을 때
 * ({@link JobStore#directionsOf}) 둘 다 여기서 읽는다.
 */
final class DirectionsFile {

    private static final Logger log = LoggerFactory.getLogger(DirectionsFile.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DirectionsFile() {
    }

    /** 후보 목록. 파일이 없거나 못 읽으면 빈 목록 — 부르는 쪽이 그 경우를 따로 다룬다. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> read(Path runsDir, String runId) {
        if (runsDir == null || runId == null || runId.isBlank()) {
            return List.of();
        }
        Path file = runsDir.resolve(runId).resolve("directions.json");
        try {
            JsonNode root = MAPPER.readTree(file.toFile());
            return root.isArray()
                    ? MAPPER.convertValue(root, List.class)
                    : List.of();
        } catch (IOException e) {
            log.warn("이야기 후보를 못 읽었습니다 (run={})", runId, e);
            return List.of();
        }
    }
}
