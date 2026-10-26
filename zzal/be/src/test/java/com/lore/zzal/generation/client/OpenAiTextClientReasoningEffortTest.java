package com.lore.zzal.generation.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/** 정체성 호출의 reasoning_effort — 보낼 때·안 보낼 때·잘못된 값. 과금 호출 없음(로컬 가짜 서버). */
class OpenAiTextClientReasoningEffortTest {

    private final AtomicReference<JsonNode> sent = new AtomicReference<>();
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private URI endpoint;

    private void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", e -> {
            sent.set(json.readTree(e.getRequestBody()));
            byte[] b = "{\"choices\":[{\"message\":{\"content\":\"synthetic identity\"}}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20}}".getBytes();
            e.sendResponseHeaders(200, b.length);
            e.getResponseBody().write(b);
            e.close();
        });
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @AfterEach void close() { if (server != null) server.stop(0); }

    private OpenAiTextClient client(String effort) {
        return new OpenAiTextClient(null, "SYNTHETIC_NOT_A_KEY", null, effort,
                HttpClient.newHttpClient(), endpoint, Duration.ofSeconds(2), ProviderUsageRecorder.NO_OP);
    }

    @Test void effortIsSentForGpt5() throws Exception {
        start();
        var result = client("low").generate("synthetic prompt", List.of(), ModelSpec.of("gpt-5"));
        assertThat(sent.get().path("reasoning_effort").asText()).isEqualTo("low");
        assertThat(sent.get().path("model").asText()).isEqualTo("gpt-5");
        // 단가·모델은 그대로 — 비용 계산이 바뀌지 않는다.
        assertThat(result.costUsd()).isEqualByComparingTo("0.000325");
    }

    @Test void blankOrNullEffortKeepsLegacyRequest() throws Exception {
        start();
        client(null).generate("synthetic prompt", List.of(), ModelSpec.of("gpt-5"));
        assertThat(sent.get().has("reasoning_effort")).isFalse();
        client("  ").generate("synthetic prompt", List.of(), ModelSpec.of("gpt-5"));
        assertThat(sent.get().has("reasoning_effort")).isFalse();
        // 예전 생성자들도 그대로 안 보낸다.
        new OpenAiTextClient(null, "SYNTHETIC_NOT_A_KEY", null, HttpClient.newHttpClient(), endpoint, Duration.ofSeconds(2))
                .generate("synthetic prompt", List.of(), ModelSpec.of("gpt-5"));
        assertThat(sent.get().has("reasoning_effort")).isFalse();
    }

    @Test void effortIsNotSentForNonGpt5Models() throws Exception {
        start();
        client("low").generate("synthetic prompt", List.of(), ModelSpec.of("gpt-4.1-mini"));
        assertThat(sent.get().has("reasoning_effort")).isFalse();
    }

    @Test void allowedValuesAreAcceptedAndTrimmed() {
        for (String v : List.of("minimal", "low", "medium", "high")) {
            assertThat(OpenAiTextClient.normalizeReasoningEffort(v)).isEqualTo(v);
        }
        assertThat(OpenAiTextClient.normalizeReasoningEffort(" low ")).isEqualTo("low");
        assertThat(OpenAiTextClient.normalizeReasoningEffort("")).isNull();
        assertThat(OpenAiTextClient.normalizeReasoningEffort(null)).isNull();
    }

    @Test void invalidValueIsRejectedAtConstructionWithSettingName() {
        for (String bad : List.of("none", "LOW", "lo", "xhigh")) {
            assertThatThrownBy(() -> new OpenAiTextClient(null, "SYNTHETIC_NOT_A_KEY", null, bad, ProviderUsageRecorder.NO_OP))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("app.zzal.openai.identity-reasoning-effort");
        }
    }

    @Test void generationConfigDefaultsToLowAndValidatesEvenInFakeMode() {
        var config = new com.lore.zzal.generation.GenerationConfig();
        ReflectionTestUtils.setField(config, "identityReasoningEffort", "low");
        var real = config.textClient(true, 0, "SYNTHETIC_NOT_A_KEY", null);
        assertThat(ReflectionTestUtils.getField(real, "reasoningEffort")).isEqualTo("low");

        ReflectionTestUtils.setField(config, "identityReasoningEffort", "");
        var unset = config.textClient(true, 0, "SYNTHETIC_NOT_A_KEY", null);
        assertThat(ReflectionTestUtils.getField(unset, "reasoningEffort")).isNull();

        ReflectionTestUtils.setField(config, "identityReasoningEffort", "turbo");
        assertThatThrownBy(() -> config.textClient(false, 0, "", null))
                .hasMessageContaining("app.zzal.openai.identity-reasoning-effort");
    }

    @Test void springBindingDefaultsToLowAndBlankDisables() {
        var runner = new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withBean(PersistentProviderUsageRecorder.class, () -> {
                    var r = org.mockito.Mockito.mock(PersistentProviderUsageRecorder.class);
                    org.mockito.Mockito.when(r.forFeature(org.mockito.ArgumentMatchers.anyString())).thenReturn(ProviderUsageRecorder.NO_OP);
                    return r;
                })
                .withBean(ProviderUsageSpool.class, () -> org.mockito.Mockito.mock(ProviderUsageSpool.class))
                .withUserConfiguration(Binding.class);
        runner.run(ctx -> {
            var c = ctx.getBean(com.lore.zzal.generation.GenerationConfig.class).textClient(true, 0, "SYNTHETIC_NOT_A_KEY", null);
            assertThat(ReflectionTestUtils.getField(c, "reasoningEffort")).isEqualTo("low");
        });
        runner.withPropertyValues("app.zzal.openai.identity-reasoning-effort=").run(ctx -> {
            var c = ctx.getBean(com.lore.zzal.generation.GenerationConfig.class).textClient(true, 0, "SYNTHETIC_NOT_A_KEY", null);
            assertThat(ReflectionTestUtils.getField(c, "reasoningEffort")).isNull();
        });
        runner.withPropertyValues("app.zzal.openai.identity-reasoning-effort=minimal").run(ctx -> {
            var c = ctx.getBean(com.lore.zzal.generation.GenerationConfig.class).textClient(true, 0, "SYNTHETIC_NOT_A_KEY", null);
            assertThat(ReflectionTestUtils.getField(c, "reasoningEffort")).isEqualTo("minimal");
        });
    }

    /** 기존 CustomPlannerTextClientTest.IdentityBinding 과 같은 방식 — @Value 만 바인딩한다. */
    static class Binding {
        @org.springframework.context.annotation.Bean
        com.lore.zzal.generation.GenerationConfig owner() { return new com.lore.zzal.generation.GenerationConfig(); }
    }
}
