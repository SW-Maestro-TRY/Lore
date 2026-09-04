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
     * 값은 실험의 api_limits.json·run_e2e_api.zsh 와 같게 맞췄다(2026-09-02).
     * 실측 기준 medium 1장 $0.0985 — 시트 $0.063 + 격자 $0.086 이 여기서 나온다.
     *
     * ⚠️ 아직 코드에 있다. 버전별로 달라지기 시작하면 models.yml 로 옮긴다.
     */
    public ModelSpec model(String version, String step) {
        return switch (step) {
            // 시트는 가로로 넓다 — 턴어라운드·표정·팔레트가 한 장에 들어간다.
            case "sheet" -> new ModelSpec("gpt-image-2", "1536x1024", "medium");
            // ★ 격자는 반드시 정사각이다(프롬프트가 "ONE square 1:1 image" 를 요구한다).
            //   그리고 gpt-image-2 는 가로·세로가 16의 배수여야 한다 — 1248 = 78x16.
            //   실험이 쓰던 값과 같게 맞춘다. 크기가 달라지면 후처리 절단이 어긋난다.
            case "grid" -> new ModelSpec("gpt-image-2", "1248x1248", "medium");
            case "identity" -> ModelSpec.of("gpt-5");
            default -> ModelSpec.of("none");
        };
    }
}
