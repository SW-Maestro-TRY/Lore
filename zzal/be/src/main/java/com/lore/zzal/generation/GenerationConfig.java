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
            return new PythonPostProcessor(storage, scripts, pythonBin, timeout, v -> hatchStates(env, v));
        }
        return new FakePostProcessor(500);
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
