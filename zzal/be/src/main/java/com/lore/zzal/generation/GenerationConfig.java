package com.lore.zzal.generation;

import com.lore.common.s3.S3Storage;
import com.lore.zzal.generation.client.FakeImageClient;
import com.lore.zzal.generation.client.FlakyImageClient;
import com.lore.zzal.generation.client.FakeMotionPostProcessor;
import com.lore.zzal.generation.client.FakePostProcessor;
import com.lore.zzal.generation.client.FakeTextClient;
import com.lore.zzal.generation.client.ImageClient;
import com.lore.zzal.generation.client.OpenAiImageClient;
import com.lore.zzal.generation.client.OpenAiTextClient;
import com.lore.zzal.generation.client.MotionPostProcessor;
import com.lore.zzal.generation.client.PostProcessor;
import com.lore.zzal.generation.client.PythonMotionPostProcessor;
import com.lore.zzal.generation.client.PythonPostProcessor;
import com.lore.zzal.generation.client.TextClient;
import com.lore.zzal.generation.steps.GridStep;
import com.lore.zzal.generation.steps.MotionGridStep;
import com.lore.zzal.generation.steps.MotionPostStep;
import org.springframework.beans.factory.annotation.Value;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.List;

/**
 * 진짜로 부를지, 흉내만 낼지 고른다.
 *
 * ★ 기본이 가짜인 이유 — 호출 한 번에 실제로 돈이 나간다($0.19). 개발 중 왕복마다
 *   그 돈이 나가면 안 되고, 실수로 켜져 있는 것보다 실수로 꺼져 있는 편이 훨씬 낫다.
 *   실제 호출은 **명시적으로 켤 때만** 돈다.
 *
 *   app.zzal.generation.real=false   가짜. 과금 0 (기본)
 *   app.zzal.generation.real=true    실제 OpenAI 호출
 *
 * ★ 후처리는 따로 켠다(real-postprocess) — **돈이 안 들기 때문**이다.
 *   우리 서버 안 계산이라, 실제 파이썬을 돌리면서도 API 호출은 가짜로 둘 수 있다.
 *   그래야 "자르기가 제대로 되는가" 를 과금 없이 확인할 수 있다.
 */
@Configuration
public class GenerationConfig {

    @Value("${app.zzal.generation.flaky.fail-step:}")
    private String flakyStep;

    @Value("${app.zzal.generation.flaky.fail-times:1}")
    private int flakyTimes;

    @Value("${app.zzal.generation.flaky.mode:moderation}")
    private String flakyMode;

    @Bean
    public ImageClient imageClient(@Value("${app.zzal.generation.real:false}") boolean real,
                                   @Value("${app.zzal.generation.fake-delay-ms:4000}") int delay,
                                   @Value("${app.zzal.generation.fake-grid-key:}") String fakeGridKey,
                                   @Value("${app.zzal.openai.api-key:}") String apiKey,
                                   S3Storage storage) {
        if (real) {
            requireKey(apiKey);
            return new OpenAiImageClient(storage, apiKey, 180);
        }
        ImageClient base = new FakeImageClient(delay, fakeGridKey);
        return wrapFlaky(base);
    }

    /**
     * 검증용 — 설정이 있으면 일부러 실패시키는 껍데기를 씌운다.
     * 기본은 비어 있어 아무 일도 하지 않는다.
     */
    private ImageClient wrapFlaky(ImageClient base) {
        if (flakyStep == null || flakyStep.isBlank()) {
            return base;
        }
        return new FlakyImageClient(base, flakyStep, flakyTimes, flakyMode);
    }

    @Bean
    public TextClient textClient(@Value("${app.zzal.generation.real:false}") boolean real,
                                 @Value("${app.zzal.generation.fake-delay-ms:4000}") int delay,
                                 @Value("${app.zzal.openai.api-key:}") String apiKey,
                                 S3Storage storage) {
        if (real) {
            requireKey(apiKey);
            return new OpenAiTextClient(storage, apiKey);
        }
        return new FakeTextClient(delay / 3);
    }

    /**
     * 키가 없으면 부팅을 막는다.
     *
     * ★ 조용히 넘어가면 "실제 호출을 켰다고 생각했는데 실은 안 켜진" 상태가 되고,
     *   그 사실이 실제 호출 때까지 안 드러난다. 설정이 원인일 때는 설정 이름을 그대로 말한다.
     */
    private void requireKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "실제 생성을 켰는데 API 키가 없습니다. ZZAL_OPENAI_API_KEY 를 설정하세요.");
        }
    }

    @Bean
    public MotionPostProcessor motionPostProcessor(
            @Value("${app.zzal.generation.real-postprocess:false}") boolean real,
            S3Storage storage, PipelineScripts scripts,
            @Value("${app.zzal.python.bin:python3}") String pythonBin,
            @Value("${app.zzal.motion-pipeline-version:v1}") String version,
            @Value("${app.zzal.python.timeout-seconds:60}") int timeout) {
        if (real) {
            return new PythonMotionPostProcessor(storage, scripts, pythonBin, version, timeout);
        }
        return new FakeMotionPostProcessor(500);
    }

    /** 격자 1장(1층 8종). v1·v2 공통. */
    @Bean
    public GridStep gridStep(ImageClient imageClient, PromptLoader prompts) {
        return new GridStep(imageClient, prompts, GridStep.NAME);
    }

    /** 격자 2장째(2층 8종). v2 만. 프롬프트 prompt/v2/grid2.txt. */
    @Bean
    public GridStep grid2Step(ImageClient imageClient, PromptLoader prompts) {
        return new GridStep(imageClient, prompts, com.lore.zzal.generation.steps.PostProcessStep.GRID2);
    }

    @Bean
    public MotionGridStep motionGridStep(ImageClient imageClient, PromptLoader prompts) {
        return new MotionGridStep(imageClient, prompts);
    }

    @Bean
    public MotionPostStep motionPostStep(MotionPostProcessor motionPostProcessor) {
        return new MotionPostStep(motionPostProcessor);
    }

    @Bean
    public PostProcessor postProcessor(
            @Value("${app.zzal.generation.real-postprocess:false}") boolean real,
            S3Storage storage, PipelineScripts scripts,
            @Value("${app.zzal.python.bin:python3}") String pythonBin,
            @Value("${app.zzal.pipeline-version:v1}") String configuredVersion,
            @Value("${app.zzal.python.timeout-seconds:60}") int timeout,
            Environment env) {
        // ★ 부팅 때 설정된 버전의 목록이 있는지 확인한다(빠졌으면 설정 이름을 말하며 막힘). 실제 사용 버전은
        //   호출마다 job 에서 온다 — 폴백으로 v1 이 됐는데 빈은 v2 로 굳어 있던 어긋남을 막는다(#218 리뷰).
        hatchStates(env, configuredVersion);
        hatchStates(env, "v1");
        if (real) {
            requirePythonPackages(pythonBin);
            requireFakeGridWhenImagesAreFake(env);
            return new PythonPostProcessor(storage, scripts, pythonBin, timeout, v -> hatchStates(env, v));
        }
        return new FakePostProcessor(500);
    }

    /**
     * 그림은 가짜인데 후처리는 진짜인 조합을 <b>기동할 때</b> 막는다.
     *
     * ★ 가짜 이미지 클라이언트는 격자 키를 안 내놓는다. 그 상태로 실제 후처리를 돌리면
     *   S3 에서 빈 키를 받으려다 {@code Key cannot be empty} 로 죽는데, 그게 <b>부화 마지막 단계</b>라
     *   설정을 잘못 준 사실이 몇 분 뒤에야 드러난다(2026-09-09 실제로 겪음).
     *
     * ★ 과금 없이 후처리를 검증하려면 진짜 격자 파일 하나가 필요하다 — {@code app.zzal.generation.fake-grid-key}.
     */
    private static void requireFakeGridWhenImagesAreFake(Environment env) {
        boolean fakeImages = !env.getProperty("app.zzal.generation.real", Boolean.class, false);
        String fakeGrid = env.getProperty("app.zzal.generation.fake-grid-key", "");
        if (fakeImages && fakeGrid.isBlank()) {
            throw new IllegalStateException("""
                    그림은 가짜인데(real=false) 후처리만 진짜(real-postprocess=true)입니다 — 이 조합은 돌 수 없습니다.
                      가짜 이미지 클라이언트는 격자를 안 내놓는데 후처리는 그 격자를 S3 에서 찾습니다.
                    둘 중 하나를 고르세요.
                      · 후처리도 가짜로  → ZZAL_REAL_POSTPROCESS=false
                      · 과금 없이 후처리를 검증 → ZZAL_FAKE_GRID_KEY 에 진짜 격자 파일의 S3 key""");
        }
    }

    /**
     * 후처리 파이썬이 쓸 수 있는 상태인지 <b>기동할 때</b> 확인한다.
     *
     * <h3>★ 왜 여기서 미리 보나 — 실패 지점이 파이프라인 맨 끝이라서</h3>
     * 후처리는 시트·문단·격자 2장을 <b>다 굽고 난 뒤</b>에 돈다. 패키지가 없으면 그때 죽는데,
     * 그림값은 이미 다 나간 뒤다. 2026-09-09 에 {@code numpy} 가 없어 <b>$0.25 를 쓰고</b>
     * 마지막 줄에서 실패했다.
     *
     * <h3>★ 기동을 막는다 — 경고로 끝내지 않는다</h3>
     * 경고는 로그에 묻힌다. 실제 생성을 켠 서버가 후처리를 못 하는 상태로 떠 있으면
     * 그 서버는 <b>돈만 쓰고 결과를 못 내는 서버</b>다. 뜨지 않는 편이 낫다.
     */
    private static void requirePythonPackages(String pythonBin) {
        List<String> need = List.of("numpy", "scipy", "PIL");
        String probe = need.stream().map("import %s"::formatted).collect(Collectors.joining("; "));
        try {
            Process p = new ProcessBuilder(pythonBin, "-c", probe)
                    .redirectErrorStream(true).start();
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("후처리 파이썬이 30초 안에 답하지 않습니다 — app.zzal.python.bin=" + pythonBin);
            }
            if (p.exitValue() != 0) {
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                throw new IllegalStateException("""
                        후처리 파이썬에 필요한 패키지가 없습니다 — 실제 생성을 켤 수 없습니다.
                          app.zzal.python.bin = %s
                          필요 = %s (버전은 zzal/pipeline/*/requirements.txt)
                          파이썬이 남긴 말: %s
                        이대로 두면 시트·격자를 다 굽고 마지막 단계에서 죽어 그림값만 나갑니다."""
                        .formatted(pythonBin, need, out));
            }
        } catch (IOException e) {
            throw new IllegalStateException("후처리 파이썬을 실행할 수 없습니다 — app.zzal.python.bin=" + pythonBin, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("후처리 파이썬 확인이 중단됐습니다", e);
        }
    }

    /**
     * 부화 후처리가 만들어야 하는 파일 이름 — {@code app.zzal.hatch.states.{버전}}.
     *
     * v1 은 8종(idle…train), v2 는 카탈로그 key 16종. 버전마다 다르므로 키를 버전으로 고른다.
     */
    static List<String> hatchStates(Environment env, String version) {
        String property = "app.zzal.hatch.states." + version;
        String configured = env.getProperty(property, "");
        List<String> states = Arrays.stream(configured.split("\\s*,\\s*"))
                .filter(s -> !s.isBlank())
                .toList();
        if (states.isEmpty()) {
            throw new IllegalStateException(
                    "부화 후처리 출력 목록이 없습니다. application.yml 의 %s 를 설정하세요".formatted(property));
        }
        return states;
    }
}
