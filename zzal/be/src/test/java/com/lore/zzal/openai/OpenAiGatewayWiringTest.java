package com.lore.zzal.openai;

import com.lore.zzal.attendance.BundleJobWorker;
import com.lore.zzal.attendance.CustomBundlePublicationService;
import com.lore.zzal.attendance.CustomPlanningService;
import com.lore.zzal.attendance.CustomProviderConfiguration;
import com.lore.zzal.chat.line.ChatLineClient;
import com.lore.zzal.chat.line.ChatLineConfig;
import com.lore.zzal.chat.line.ChatLineEvents;
import com.lore.zzal.chat.prompt.SystemPromptCache;
import com.lore.zzal.chat.stage.StageSpeechBatchGenerator;
import com.lore.zzal.diary.DiaryModel;
import com.lore.zzal.diary.DiaryModelConfiguration;
import com.lore.zzal.generation.GenerationConfig;
import com.lore.zzal.generation.client.ImageClient;
import com.lore.zzal.generation.client.ModelSpec;
import com.lore.zzal.generation.client.PersistentProviderUsageRecorder;
import com.lore.zzal.generation.client.PostProcessor;
import com.lore.zzal.generation.client.ProviderUsageRecorder;
import com.lore.zzal.generation.client.ProviderUsageSpool;
import com.lore.zzal.generation.client.TextClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P1-1 · P1-6 (설계 2-4): OpenAI 클라이언트를 만드는 곳(그림·문단 {@code GenerationConfig}, 대사
 * {@code ChatLineConfig}·{@code StageSpeechBatchGenerator}·{@code DiaryModelConfiguration}·{@code CustomProviderConfiguration})
 * 이 모두 스프링의 창구 빈 하나를 거치고, 각자 맞는 줄로 간다.
 *
 * ★ 창구를 거치지 않는 생성 지점이 하나라도 있으면 그 기능만 상한·429 차단·기록 밖으로 새 나간다.
 */
@DisplayName("창구 배선 — 생성 지점이 전부 창구 빈을 거친다")
class OpenAiGatewayWiringTest {

    /** 호출 수를 줄별로 세는 창구. */
    static final class CountingGateway extends OpenAiGateway {
        final Map<Lane, AtomicInteger> calls = new EnumMap<>(Lane.class);

        CountingGateway() {
            super(LaneConfig.defaults());
            for (Lane l : Lane.values()) calls.put(l, new AtomicInteger());
        }

        @Override
        public Call call(Lane lane, String model, ProviderUsageRecorder recorder) {
            calls.get(lane).incrementAndGet();
            return super.call(lane, model, recorder);
        }

        int count(Lane lane) {
            return calls.get(lane).get();
        }
    }

    private static final String BODY = "{\"id\":\"r\",\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1},"
            + "\"choices\":[{\"message\":{\"content\":\"{\\\"line\\\":\\\"응\\\",\\\"speeches\\\":[]}\"}}],\"data\":[{\"b64_json\":\"AA==\"}]}";

    private static String base(GatewayTestServer server) {
        return "http://127.0.0.1:" + server.server.getAddress().getPort();
    }

    private static PersistentProviderUsageRecorder noOpRecorder() {
        var r = mock(PersistentProviderUsageRecorder.class);
        when(r.forFeature(anyString())).thenReturn(ProviderUsageRecorder.NO_OP);
        return r;
    }

    @Test
    @DisplayName("그림·문단(GenerationConfig) → 그림 줄")
    void generationConfig() throws Exception {
        try (var server = new GatewayTestServer(); var gateway = new CountingGateway()) {
            server.body = BODY;
            var config = new GenerationConfig();
            ReflectionTestUtils.setField(config, "openAiBaseUrl", base(server));
            ReflectionTestUtils.setField(config, "openAiGateway", gateway);
            var storage = mock(com.lore.common.s3.S3Storage.class);
            ImageClient image = config.imageClient(true, 0, "", "SYNTHETIC_NOT_A_KEY", 5, storage, new MockEnvironment(), mock(PostProcessor.class));
            image.generate("p", List.of(), "images/t.png", ModelSpec.of("gpt-image-2"));
            TextClient text = config.textClient(true, 0, "SYNTHETIC_NOT_A_KEY", storage);
            text.generate("p", List.of(), ModelSpec.of("gpt-5"));

            assertThat(gateway.count(Lane.IMAGE)).isEqualTo(2);
            assertThat(server.requests.get()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("채팅(ChatLineConfig) → 급한 대사 줄")
    void chatLineConfig() throws Exception {
        try (var server = new GatewayTestServer(); var gateway = new CountingGateway();
             var ctx = new AnnotationConfigApplicationContext()) {
            server.body = BODY;
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(ctx, "app.zzal.chat.llm=true",
                    "app.zzal.openai.api-key=SYNTHETIC_NOT_A_KEY", "app.lore.env=local", "app.zzal.openai.base-url=" + base(server));
            ctx.registerBean(SystemPromptCache.class, SystemPromptCache::new);
            ctx.registerBean(ChatLineEvents.class, () -> mock(ChatLineEvents.class));
            ctx.registerBean(PersistentProviderUsageRecorder.class, OpenAiGatewayWiringTest::noOpRecorder);
            ctx.registerBean(ProviderUsageSpool.class, () -> mock(ProviderUsageSpool.class));
            ctx.registerBean(OpenAiGateway.class, () -> gateway);
            ctx.register(ChatLineConfig.class);
            ctx.refresh();
            var chain = ctx.getBean(com.lore.zzal.chat.line.LineChain.class);
            var client = (ChatLineClient) ReflectionTestUtils.getField(ReflectionTestUtils.getField(chain, "llm"), "client");
            client.complete("s", "u", "gpt-5-mini", Duration.ofSeconds(5));

            assertThat(gateway.count(Lane.DIALOGUE_URGENT)).isEqualTo(1);
            assertThat(server.requests.get()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("튜토리얼 묶음(StageSpeechBatchGenerator) → 급한 대사 줄")
    void stageSpeech() throws Exception {
        try (var server = new GatewayTestServer(); var gateway = new CountingGateway();
             var ctx = new AnnotationConfigApplicationContext()) {
            server.body = BODY;
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(ctx, "app.zzal.chat.llm=true",
                    "app.zzal.openai.api-key=SYNTHETIC_NOT_A_KEY", "app.zzal.openai.base-url=" + base(server));
            ctx.registerBean(PersistentProviderUsageRecorder.class, OpenAiGatewayWiringTest::noOpRecorder);
            ctx.registerBean(ProviderUsageSpool.class, () -> mock(ProviderUsageSpool.class));
            ctx.registerBean(OpenAiGateway.class, () -> gateway);
            ctx.register(StageSpeechBatchGenerator.class);
            ctx.refresh();
            var client = (ChatLineClient) ReflectionTestUtils.getField(ctx.getBean(StageSpeechBatchGenerator.class), "client");
            client.complete("s", "u", "gpt-5-mini", Duration.ofSeconds(5));

            assertThat(gateway.count(Lane.DIALOGUE_URGENT)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("일기(DiaryModelConfiguration) → 느긋한 대사 줄")
    void diary() throws Exception {
        try (var server = new GatewayTestServer(); var gateway = new CountingGateway();
             var ctx = new AnnotationConfigApplicationContext()) {
            server.body = BODY;
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(ctx, "zzal.diary.enabled=true",
                    "app.zzal.openai.api-key=SYNTHETIC_NOT_A_KEY", "app.zzal.openai.base-url=" + base(server));
            ctx.registerBean(PersistentProviderUsageRecorder.class, OpenAiGatewayWiringTest::noOpRecorder);
            ctx.registerBean(ProviderUsageSpool.class, () -> mock(ProviderUsageSpool.class));
            ctx.registerBean(OpenAiGateway.class, () -> gateway);
            ctx.register(DiaryModelConfiguration.class);
            ctx.refresh();
            var client = (ChatLineClient) ReflectionTestUtils.getField(ctx.getBean(DiaryModel.class), "client");
            client.complete("s", "u", "gpt-5-mini", Duration.ofSeconds(5));

            assertThat(gateway.count(Lane.DIALOGUE_BACKGROUND)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("맞춤 기획(CustomProviderConfiguration) → 느긋한 대사 줄")
    void customPlanner() throws Exception {
        try (var server = new GatewayTestServer(); var gateway = new CountingGateway()) {
            server.body = BODY;
            var env = new MockEnvironment().withProperty("app.zzal.generation.real", "true")
                    .withProperty("app.zzal.generation.real-postprocess", "true").withProperty("app.zzal.attendance.cli-enabled", "true")
                    .withProperty("app.zzal.openai.api-key", "SYNTHETIC_NOT_A_KEY").withProperty("app.zzal.custom.planner-model", "gpt-5.6-luna")
                    .withProperty("app.zzal.custom.provider", "api").withProperty("app.zzal.custom.image-model", "gpt-image-2")
                    .withProperty("app.zzal.custom.image-size", "1248x1248").withProperty("app.zzal.custom.postprocess-version", "v1")
                    .withProperty("app.zzal.openai.base-url", base(server));
            var config = new CustomProviderConfiguration();
            ReflectionTestUtils.setField(config, "openAiGateway", gateway);
            var service = config.realCustomBundleService(env, mock(CustomPlanningService.class), mock(BundleJobWorker.class),
                    mock(TextClient.class), mock(ImageClient.class), mock(PostProcessor.class), mock(com.lore.common.s3.S3Storage.class),
                    mock(CustomBundlePublicationService.class));
            var planner = ReflectionTestUtils.getField(service, "text");
            var client = (ChatLineClient) ReflectionTestUtils.getField(planner, "client");
            client.complete("s", "u", "gpt-5.6-luna", Duration.ofSeconds(5));

            assertThat(gateway.count(Lane.DIALOGUE_BACKGROUND)).isEqualTo(1);
        }
    }
}
