package com.lore.zzal.generation;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 파이프라인 리소스 폴더의 <b>{@code 이름 = 값}</b> 표를 읽는다.
 *
 * <h3>★ 왜 있는가</h3>
 * 판정받은 조건 중에는 <b>코드가 아니라 그 버전 프롬프트·동작의 성질</b>인 것들이 있다 —
 * 어느 칸이 앉은 자세인가({@link HatchPostures}), 어느 동작이 어떤 후처리를 타는가
 * ({@link MotionPostProfiles}). 칸 번호·동작 이름을 코드에 박지 말라는 2026-09-12 지시에 따라
 * 이런 것은 전부 리소스 파일로 빼는데, <b>읽는 방식이 두 벌이 되면 형식이 조용히 갈라진다.</b>
 * 그래서 형식과 읽기를 여기 한 곳에 둔다.
 *
 * <h3>형식</h3>
 * <pre>
 *   # 으로 시작하는 줄과 빈 줄은 무시
 *   이름 = 값            (값 안의 = 는 그대로 살려 둔다 — 첫 = 에서만 자른다)
 * </pre>
 *
 * <h3>★ 없으면 빈 표 — 있는데 형식이 깨졌으면 예외</h3>
 * 파일이 없는 것은 "그 버전에는 이 개념이 없다" 는 정상 상태다(v1·v2 부화 후처리는
 * 자세 매핑을 모른다). 그러나 <b>있는데 읽을 수 없으면</b> 무엇이 잘못됐는지 말하며 멈춘다.
 */
final class ResourceTable {

    private ResourceTable() {
    }

    /** 그 리소스의 {@code 이름 → 값} 표. 파일이 없으면 빈 표(불변). */
    static Map<String, String> load(String path) {
        ClassPathResource r = new ClassPathResource(path);
        if (!r.exists()) {
            return Map.of();
        }
        Map<String, String> table = new LinkedHashMap<>();
        try {
            String text = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            for (String raw : text.split("\n")) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 0) {
                    throw new IllegalStateException(
                            "%s 의 형식 오류: '%s' — `이름 = 값` 으로 쓸 것".formatted(path, line));
                }
                table.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("리소스 표를 읽을 수 없습니다: " + path, e);
        }
        return Map.copyOf(table);
    }
}
