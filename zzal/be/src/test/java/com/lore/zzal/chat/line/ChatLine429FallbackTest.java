package com.lore.zzal.chat.line;

import com.lore.zzal.chat.prompt.SystemPromptCache;
import com.lore.zzal.generation.client.ProviderUsageRecorder;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-4 (설계 2-2): 급한 대사 줄은 429 를 받으면 다시 부르지 않고 대체 대사로 간다.
 * 같은 키라 곧바로 다시 보내면 또 막힌다 — 대사 사슬의 두 번째 시도도 OpenAI 로 나가지 않아야 한다.
 */
@DisplayName("대사 429 — 재시도 0회, 대체 대사")
class ChatLine429FallbackTest {

    @Test
    void quotaGoesToFallbackWithoutSecondRequest() throws Exception {
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
        try {
            var client = new OpenAiChatLineClient("SYNTHETIC_NOT_A_KEY", "minimal", 64, ProviderUsageRecorder.NO_OP,
                    "http://127.0.0.1:" + server.getAddress().getPort());
            var chain = new LineChain(new LlmLineGenerator(client, "gpt-5.6-luna", Duration.ofSeconds(4), new SystemPromptCache()), null, 2);
            GeneratedLine out = chain.generate(LlmLineGeneratorTest.reply(), 1L);

            assertThat(requests.get()).as("429 뒤 같은 줄로 다시 보내지 않는다").isEqualTo(1);
            assertThat(out.outcome()).isIn(LineOutcome.FAILED_CLOSED, LineOutcome.FAILED_REPLY);
            assertThat(out.text()).isNotBlank();
        } finally {
            server.stop(0);
        }
    }
}
