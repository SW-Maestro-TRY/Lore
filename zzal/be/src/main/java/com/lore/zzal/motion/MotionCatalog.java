package com.lore.zzal.motion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 어떤 동작을 어떤 순서로 열 것인가.
 *
 * ★ 목록을 코드가 아니라 설정에 둔 이유 — 무엇을 몇 개 열지는 상훈님이 실험 결과를 보고
 *   계속 정하신다(2026-09-03). 설정이면 코드를 안 고치고 순서를 바꾸거나 개수를 늘린다.
 *
 * <pre>
 * app.zzal.motions: 교감1_머리쓰다듬,식사1_한입크게,청소1_먼지떨이
 * </pre>
 *
 * ★ 이름은 실험의 동작 블록 파일명과 <b>정확히 같아야</b> 한다. 그 이름으로 블록을 찾는다.
 *   목록에는 있는데 블록 파일이 없으면 굽기 직전이 아니라 <b>부팅할 때</b> 걸리게 해 둔다 —
 *   사용자가 재우고 나서야 드러나면 그 사람은 6시간을 헛되이 기다린 것이 된다.
 *
 * ⚠️ 비어 있으면 아무 동작도 열리지 않는다. 그 자체는 정상 상태다(아직 안 정하셨다는 뜻).
 */
@Component
public class MotionCatalog {

    private final List<String> names;
    private final String version;
    private final Map<String, String> blocks = new ConcurrentHashMap<>();

    public MotionCatalog(@Value("${app.zzal.motions:}") String configured,
                         @Value("${app.zzal.motion-pipeline-version:v1}") String version) {
        this.version = version;
        this.names = configured == null || configured.isBlank()
                ? List.of()
                : List.of(configured.split("\\s*,\\s*"));
        // 목록에 적힌 블록이 실제로 있는지 여기서 다 확인한다.
        this.names.forEach(this::block);
    }

    /** 다 모으면 몇 개인가. */
    public int total() {
        return names.size();
    }

    /** 몇 번째로 배울 동작의 이름. 목록을 다 썼으면 null. */
    public String nameAt(int seq) {
        return seq >= 0 && seq < names.size() ? names.get(seq) : null;
    }

    /** 그 동작의 프롬프트 블록. */
    public String block(String name) {
        return blocks.computeIfAbsent(name, key -> {
            String path = "zzal/prompt/%s/motions/%s.txt".formatted(version, key);
            try {
                ClassPathResource r = new ClassPathResource(path);
                return new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("동작 블록이 없습니다: " + path, e);
            }
        });
    }

    public List<String> names() {
        return names;
    }
}
