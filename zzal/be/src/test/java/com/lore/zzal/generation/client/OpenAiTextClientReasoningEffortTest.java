package com.lore.zzal.generation.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.zzal.generation.PromptLoader;
import com.lore.zzal.generation.StepContext;
import com.lore.zzal.generation.steps.IdentityStep;
import com.lore.zzal.generation.steps.SheetStep;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 정체성 단계 reasoning_effort — 시도 차수별(기본 ",low,minimal") 요청 본문과 설정 검증.
 * 과금 호출 없음: 로컬 가짜 HTTP 서버가 OpenAI 자리를 대신하고, 요청 본문만 받아 적는다.
 */
class OpenAiTextClientReasoningEffortTest {

    private static final String DEFAULT_SCHEDULE = ",low,minimal";
    private final List<JsonNode> sent = new ArrayList<>();
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private URI endpoint;

    private void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", e -> {
            synchronized (sent) { sent.add(json.readTree(e.getRequestBody())); }
            byte[] b = "{\"choices\":[{\"message\":{\"content\":\"synthetic identity\"}}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20}}".getBytes();
            e.sendResponseHeaders(200, b.length);
            e.getResponseBody().write(b);
            e.close();
        });
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @AfterEach void close() { if (server != null) server.stop(0); }

    private OpenAiTextClient client() {
        return new OpenAiTextClient(null, "SYNTHETIC_NOT_A_KEY", null,
                HttpClient.newHttpClient(), endpoint, Duration.ofSeconds(2), ProviderUsageRecorder.NO_OP);
    }

    // ───────────── 클라이언트: 요청 본문 ─────────────

    @Test void clientSendsGivenEffortForGpt5AndKeepsCost() throws Exception {
        start();
        var r = client().generate("p", List.of(), ModelSpec.of("gpt-5"), "low");
        assertThat(sent.get(0).path("reasoning_effort").asText()).isEqualTo("low");
        assertThat(sent.get(0).path("model").asText()).isEqualTo("gpt-5");
        assertThat(r.costUsd()).isEqualByComparingTo("0.000325"); // 단가 그대로
    }

    @Test void clientOmitsEffortWhenNullOrThreeArgCall() throws Exception {
        start();
        client().generate("p", List.of(), ModelSpec.of("gpt-5"), null);
        client().generate("p", List.of(), ModelSpec.of("gpt-5"));
        assertThat(sent).allSatisfy(b -> assertThat(b.has("reasoning_effort")).isFalse());
    }

    @Test void clientOmitsEffortForNonGpt5Models() throws Exception {
        start();
        client().generate("p", List.of(), ModelSpec.of("gpt-4.1-mini"), "low");
        assertThat(sent.get(0).has("reasoning_effort")).isFalse();
    }

    // ───────────── 단계: 시도 차수별 ─────────────

    @Test void attemptOneSendsNothingTwoLowThreeMinimalBeyondKeepsLast() throws Exception {
        start();
        IdentityStep step = stepWithFakeSheet(DEFAULT_SCHEDULE);
        assertThat(run(step, 1).has("reasoning_effort")).as("시도 1 = 지금과 같음(모델 기본)").isFalse();
        assertThat(run(step, 2).path("reasoning_effort").asText()).isEqualTo("low");
        assertThat(run(step, 3).path("reasoning_effort").asText()).isEqualTo("minimal");
        assertThat(run(step, 4).path("reasoning_effort").asText()).as("목록보다 크면 마지막 값").isEqualTo("minimal");
        assertThat(run(step, 9).path("reasoning_effort").asText()).isEqualTo("minimal");
        assertThat(run(step, 0).has("reasoning_effort")).as("관리자 재굽기 attempt 0 → 1").isFalse();
    }

    @Test void emptyScheduleNeverSends() throws Exception {
        start();
        IdentityStep step = stepWithFakeSheet("");
        for (int a = 1; a <= 4; a++) assertThat(run(step, a).has("reasoning_effort")).isFalse();
    }

    @Test void effortForMatchesSchedule() {
        IdentityStep step = new IdentityStep(mock(TextClient.class), mock(PromptLoader.class), true, 90, DEFAULT_SCHEDULE);
        assertThat(step.effortFor(0)).isNull();
        assertThat(step.effortFor(1)).isNull();
        assertThat(step.effortFor(2)).isEqualTo("low");
        assertThat(step.effortFor(3)).isEqualTo("minimal");
        assertThat(step.effortFor(4)).isEqualTo("minimal");
        IdentityStep legacy = new IdentityStep(mock(TextClient.class), mock(PromptLoader.class), true, 90);
        assertThat(legacy.effortFor(3)).as("4인자 생성자 = 이전 동작").isNull();
        IdentityStep lastBlank = new IdentityStep(mock(TextClient.class), mock(PromptLoader.class), true, 90, "low,");
        assertThat(lastBlank.effortFor(5)).as("마지막이 빈 칸이면 그 뒤도 안 보냄").isNull();
    }

    // ───────────── 설정 검증 ─────────────

    @Test void scheduleParsingAcceptsAllowedAndBlankItems() {
        assertThat(IdentityStep.parseEffortByAttempt(",low,minimal")).containsExactly(null, "low", "minimal");
        assertThat(IdentityStep.parseEffortByAttempt(" , medium , high ")).containsExactly(null, "medium", "high");
        assertThat(IdentityStep.parseEffortByAttempt("")).isEmpty();
        assertThat(IdentityStep.parseEffortByAttempt(null)).isEmpty();
    }

    @Test void invalidScheduleItemFailsAtConstructionWithSettingName() {
        for (String bad : List.of(",low,none", "LOW", "low,xhigh", "fast")) {
            assertThatThrownBy(() -> new IdentityStep(mock(TextClient.class), mock(PromptLoader.class), true, 90, bad))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("app.zzal.openai.identity-reasoning-effort-by-attempt");
        }
    }

    @Test void clientRejectsInvalidEffortBeforeSending() throws Exception {
        start();
        assertThatThrownBy(() -> client().generate("p", List.of(), ModelSpec.of("gpt-5"), "none"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(sent).isEmpty();
    }

    @Test void springDefaultIsAttemptScheduleAndBlankDisables() {
        var runner = new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withBean(TextClient.class, () -> mock(TextClient.class))
                .withBean(PromptLoader.class, () -> mock(PromptLoader.class))
                .withBean(IdentityStep.class);
        runner.run(ctx -> {
            var s = ctx.getBean(IdentityStep.class);
            assertThat(s.effortFor(1)).isNull();
            assertThat(s.effortFor(2)).isEqualTo("low");
            assertThat(s.effortFor(3)).isEqualTo("minimal");
        });
        runner.withPropertyValues("app.zzal.openai.identity-reasoning-effort-by-attempt=").run(ctx ->
                assertThat(ctx.getBean(IdentityStep.class).effortFor(3)).isNull());
        runner.withPropertyValues("app.zzal.openai.identity-reasoning-effort-by-attempt=,none").run(ctx ->
                assertThat(ctx).hasFailed());
    }

    // ───────────── 도우미 ─────────────

    /** S3 를 흉내 내 시트 그림 한 장(작은 바이트)을 내려 주는 클라이언트로 단계를 만든다. */
    private IdentityStep stepWithFakeSheet(String schedule) {
        com.lore.common.s3.S3Storage storage = mock(com.lore.common.s3.S3Storage.class);
        try {
            org.mockito.Mockito.doAnswer(inv -> {
                java.nio.file.Files.write(inv.getArgument(1), new byte[]{1, 2, 3});
                return null;
            }).when(storage).download(anyString(), any(java.nio.file.Path.class));
        } catch (Exception e) { throw new AssertionError(e); }
        var c = new OpenAiTextClient(storage, "SYNTHETIC_NOT_A_KEY", null,
                HttpClient.newHttpClient(), endpoint, Duration.ofSeconds(2), ProviderUsageRecorder.NO_OP);
        PromptLoader prompts = mock(PromptLoader.class);
        when(prompts.prompt(anyString(), anyString())).thenReturn("synthetic identity prompt");
        when(prompts.model(anyString(), anyString())).thenReturn(ModelSpec.of("gpt-5"));
        return new IdentityStep(c, prompts, false, 90, schedule);
    }

    private JsonNode run(IdentityStep step, int attempt) throws Exception {
        StepContext ctx = new StepContext(1L, "synthetic", null, "v1").attempt(attempt);
        ctx.putImage(SheetStep.NAME, "images/zzal/pets/1/sheet.png");
        int before = sent.size();
        step.run(ctx);
        assertThat(sent).hasSize(before + 1);
        return sent.get(sent.size() - 1);
    }
}
