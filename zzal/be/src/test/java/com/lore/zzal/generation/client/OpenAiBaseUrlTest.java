package com.lore.zzal.generation.client;

import com.lore.zzal.chat.line.OpenAiChatLineClient;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

/** P0-2: 세 클라이언트가 설정 app.zzal.openai.base-url 한 값을 읽는다. 비면 실제 주소. */
class OpenAiBaseUrlTest {
    private static final String BODY = "{\"id\":\"r\",\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1},"
            + "\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"data\":[{\"b64_json\":\"AA==\"}]}";

    @Test
    void blankFallsBackToDefault() {
        assertThat(OpenAiBaseUrl.resolve(null)).isEqualTo("https://api.openai.com");
        assertThat(OpenAiBaseUrl.resolve("  ")).isEqualTo("https://api.openai.com");
        assertThat(OpenAiBaseUrl.endpoint("", "/v1/images/edits").toString())
                .isEqualTo("https://api.openai.com/v1/images/edits");
        assertThat(OpenAiBaseUrl.endpoint("http://127.0.0.1:8090/", "/v1/chat/completions").toString())
                .isEqualTo("http://127.0.0.1:8090/v1/chat/completions");
    }

    @Test
    void configuredBaseReachesAllThreeClients() throws Exception {
        var paths = new CopyOnWriteArrayList<String>();
        var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", e -> {
            paths.add(e.getRequestURI().getPath());
            e.getRequestBody().readAllBytes();
            byte[] b = BODY.getBytes();
            e.sendResponseHeaders(200, b.length);
            e.getResponseBody().write(b);
            e.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var storage = mock(com.lore.common.s3.S3Storage.class);
            assertThatCode(() -> new OpenAiImageClient(storage, "SYNTHETIC_NOT_A_KEY", 5, ProviderUsageRecorder.NO_OP, base, com.lore.zzal.openai.TestGateways.lanes())
                    .generate("p", List.of(), "images/t.png", ModelSpec.of("gpt-image-2"))).doesNotThrowAnyException();
            new OpenAiTextClient(null, "SYNTHETIC_NOT_A_KEY", 64, ProviderUsageRecorder.NO_OP, base, com.lore.zzal.openai.TestGateways.lanes())
                    .generate("p", List.of(), ModelSpec.of("gpt-5"));
            new OpenAiChatLineClient("SYNTHETIC_NOT_A_KEY", "minimal", 64, ProviderUsageRecorder.NO_OP, base, com.lore.zzal.openai.TestGateways.lanes(), com.lore.zzal.openai.Lane.DIALOGUE_URGENT)
                    .complete("s", "u", "gpt-5-mini", Duration.ofSeconds(5));
            assertThat(paths).containsExactly("/v1/images/edits", "/v1/chat/completions", "/v1/chat/completions");
        } finally {
            server.stop(0);
        }
    }
}
