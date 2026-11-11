package com.lore.zzal.chat.line;

import com.lore.zzal.chat.prompt.SystemPromptCache;
import com.lore.zzal.generation.client.ProviderUsageRecorder;
import com.lore.zzal.openai.Lane;
import com.lore.zzal.openai.LaneConfig;
import com.lore.zzal.openai.OpenAiGateway;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1 검토 표 4·10번(상훈님 결정 안 "다", 2026-11-11): 급한 대사 줄이 차면 <b>한 번만</b> 기다리고,
 * 자리를 못 얻으면 대사 사슬이 다시 부르지 않고 바로 대체 대사로 간다.
 * 실패 사유는 {@code lane_busy}(자리 없음)·{@code quota}(줄 차단)로 "OpenAI 오류"({@code error})와 갈라 남기고,
 * 보내지 않은 시도는 시도 횟수({@code attempts})로 세지 않는다. 흉내 서버라 과금 0.
 */
@DisplayName("대사 줄 자리 없음 — 한 번만 기다리고 대체 대사, 사유 lane_busy·시도 0")
class ChatLaneBusyFallbackTest {

    private static final String OK_BODY = "{\"id\":\"r\",\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1},"
            + "\"choices\":[{\"message\":{\"content\":\"{\\\"line\\\":\\\"안녕\\\"}\"}}]}";

    @Test
    @DisplayName("★★ 자리가 안 나면 OpenAI 로 한 번도 안 보내고(시도 0) 대체 대사, 사유 lane_busy, 기다림은 한 번뿐")
    void laneBusyGoesStraightToFallback() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", e -> {
            requests.incrementAndGet();
            e.getRequestBody().readAllBytes();
            try { Thread.sleep(3_000); } catch (InterruptedException ignored) { }
            byte[] b = OK_BODY.getBytes();
            e.sendResponseHeaders(200, b.length);
            e.getResponseBody().write(b);
            e.close();
        });
        server.start();
        Map<Lane, LaneConfig> lanes = new EnumMap<>(LaneConfig.defaults());
        lanes.put(Lane.DIALOGUE_URGENT, new LaneConfig(1, 400, Duration.ofSeconds(2)));
        try (var gateway = new OpenAiGateway(lanes)) {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var client = new OpenAiChatLineClient("SYNTHETIC_NOT_A_KEY", "minimal", 64, ProviderUsageRecorder.NO_OP,
                    base, gateway, Lane.DIALOGUE_URGENT);
            // 앞 손님이 자리 하나를 3초 쥔다.
            Future<?> holder = gateway.executor().submit(() -> client.complete("s", "u", "gpt-5.6-luna", Duration.ofSeconds(5)));
            long until = System.currentTimeMillis() + 5_000;
            while (requests.get() < 1 && System.currentTimeMillis() < until) Thread.sleep(10);

            var chain = new LineChain(new LlmLineGenerator(client, "gpt-5.6-luna", Duration.ofSeconds(4), new SystemPromptCache()), null, 2);
            long t0 = System.nanoTime();
            GeneratedLine out = chain.generate(LlmLineGeneratorTest.reply(), 1L);
            long tookMs = (System.nanoTime() - t0) / 1_000_000;

            assertThat(requests.get()).as("뒤 손님 요청은 OpenAI 로 나가지 않는다").isEqualTo(1);
            assertThat(out.outcome()).isIn(LineOutcome.FAILED_CLOSED, LineOutcome.FAILED_REPLY);
            assertThat(out.text()).as("대체 대사").isNotBlank();
            assertThat(out.failReason()).as("OpenAI 오류(error)와 갈라 남긴다").isEqualTo("lane_busy");
            assertThat(out.attempts()).as("보내지 않은 시도는 세지 않는다").isZero();
            assertThat(tookMs).as("대기 400ms 를 한 번만 — 사슬이 다시 줄을 서지 않는다").isLessThan(750);
            holder.get();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("★ 429 로 줄이 막히면 사유를 quota 계열로 남기고 다시 부르지 않는다 — 보낸 1회만 센다")
    void quotaIsSeparatedFromError() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", e -> {
            requests.incrementAndGet();
            e.getRequestBody().readAllBytes();
            byte[] b = "{\"error\":{\"code\":\"rate_limit_exceeded\"}}".getBytes();
            e.getResponseHeaders().add("Retry-After", "30");
            e.sendResponseHeaders(429, b.length);
            e.getResponseBody().write(b);
            e.close();
        });
        server.start();
        try (var gateway = new OpenAiGateway(LaneConfig.defaults())) {
            var client = new OpenAiChatLineClient("SYNTHETIC_NOT_A_KEY", "minimal", 64, ProviderUsageRecorder.NO_OP,
                    "http://127.0.0.1:" + server.getAddress().getPort(), gateway, Lane.DIALOGUE_URGENT);
            var chain = new LineChain(new LlmLineGenerator(client, "gpt-5.6-luna", Duration.ofSeconds(4), new SystemPromptCache()), null, 2);

            GeneratedLine first = chain.generate(LlmLineGeneratorTest.reply(), 1L);
            assertThat(first.failReason()).isEqualTo("quota_provider");
            assertThat(first.attempts()).as("OpenAI 가 429 를 준 1회만").isEqualTo(1);

            // 줄이 막힌 동안의 다음 손님 — 보내지 않고 바로 대체 대사.
            GeneratedLine next = chain.generate(LlmLineGeneratorTest.reply(), 2L);
            assertThat(next.failReason()).isEqualTo("quota");
            assertThat(next.attempts()).isZero();
            assertThat(requests.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("★ 급한 대사 줄 기본 대기 상한은 2000ms(설정 app.zzal.openai.lanes.dialogue-urgent.max-wait-ms)")
    void urgentDefaultWaitIsTwoSeconds() {
        assertThat(LaneConfig.defaults().get(Lane.DIALOGUE_URGENT).maxWaitMs()).isEqualTo(2_000);
        var env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("app.zzal.openai.lanes.dialogue-urgent.max-wait-ms", "2500");
        assertThat(LaneConfig.from(env).get(Lane.DIALOGUE_URGENT).maxWaitMs()).isEqualTo(2_500);
    }
}
