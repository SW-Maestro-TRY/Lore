package com.lore.zzal.generation;

import com.lore.zzal.generation.client.ModelSpec;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 버전 폴더에서 프롬프트와 모델 설정을 읽는다.
 *
 * ★ 프롬프트를 코드가 아니라 파일에 둔 이유 — 계속 고쳐질 것이고, 무엇이 어떻게 바뀌었는지
 *   git diff 로 보여야 한다. 코드 안 문자열로 두면 diff 가 코드 변경에 묻힌다.
 *
 * 자리: zzal/be/src/main/resources/zzal/prompt/{version}/{name}.txt
 */
@Component
public class PromptLoader {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String prompt(String version, String name) {
        return cache.computeIfAbsent(version + "/" + name, key -> {
            try {
                ClassPathResource r = new ClassPathResource("zzal/prompt/" + key + ".txt");
                return new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                // 설정이 원인일 때는 어느 파일이 없는지 그대로 말해 준다.
                throw new UncheckedIOException(
                        "프롬프트 파일이 없습니다: zzal/prompt/" + key + ".txt", e);
            }
        });
    }

    /**
     * 어떤 모델을 쓸지.
     *
     * ⚠️ 지금은 코드에 기본값을 두었다. 실제 호출을 붙이는 시점(#132 4번 걸음)에
     *    버전별 models.yml 로 옮긴다 — 그 전에는 가짜 클라이언트라 값이 쓰이지 않는다.
     */
    public ModelSpec model(String version, String step) {
        return switch (step) {
            case "sheet", "grid" -> new ModelSpec("gpt-image-2", "1536x1024", "medium");
            case "identity" -> ModelSpec.of("gpt-5");
            default -> ModelSpec.of("none");
        };
    }
}
