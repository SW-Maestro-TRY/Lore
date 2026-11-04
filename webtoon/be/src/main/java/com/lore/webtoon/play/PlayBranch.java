package com.lore.webtoon.play;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.webtoon.job.HarnessProcess;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 세계관 체험 만화가 사건의 원래 전개와 어디서 갈라졌는가(2026-11-04). 하네스가 이야기를 쓴 뒤 따로 읽어
 * 작품 폴더에 {@code branch.json} 으로 남긴다(run.py stage_branch). 결과 화면과 사건의 「캐릭터별 이야기」
 * 카드가 「원래는 … / 이 주인공은 …」으로 보여 준다. 원래 전개가 없던 사건 · 예전 작품은 파일이 없어 {@code null}.
 */
@Component
public class PlayBranch {

    private static final Logger log = LoggerFactory.getLogger(PlayBranch.class);
    private static final List<String> TEXT = List.of("canon", "this", "why", "end", "label");

    private final HarnessProcess harness;
    private final ObjectMapper mapper = new ObjectMapper();

    public PlayBranch(HarnessProcess harness) {
        this.harness = harness;
    }

    /** {@code at}(갈라진 원래 전개 줄, 0 이면 안 갈림) · 문장 다섯 · {@code canon_lines}. 없거나 못 읽으면 null. */
    public Map<String, Object> of(String runId) {
        if (runId == null || runId.isBlank()) {
            return null;
        }
        Path root = harness.runsDir().normalize();
        Path file = root.resolve(runId).resolve("branch.json").normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            Map<String, Object> raw = mapper.readValue(file.toFile(), new TypeReference<>() {});
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("at", raw.get("at") instanceof Number n ? n.intValue() : 0);
            for (String k : TEXT) {
                out.put(k, raw.get(k) == null ? "" : String.valueOf(raw.get(k)));
            }
            out.put("canon_lines", PlayWorldService.canon(raw.get("canon_lines")));
            return out;
        } catch (IOException e) {
            log.warn("갈림 정리를 읽지 못했습니다: {}", file, e);
            return null;
        }
    }
}
