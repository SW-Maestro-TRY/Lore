package com.lore.zzal.openai;

import com.lore.zzal.generation.client.ProviderUsageRecorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * P1-4 (설계 2-2): 429·5xx 를 창구가 한 가지 예외 타입으로 올리고, 429 면 그 줄만 잠시 막는다.
 */
@DisplayName("창구 429·5xx — 예외 타입 통일과 줄별 차단")
class OpenAiGatewayQuotaTest {

    private static int send(OpenAiGateway gateway, Lane lane, GatewayTestServer server, String tag) throws Exception {
        try (var call = gateway.call(lane, "m", ProviderUsageRecorder.NO_OP)) {
            return call.send(server.request(tag)).statusCode();
        }
    }

    @Test
    @DisplayName("429(Retry-After 5초) → 같은 줄은 5초 동안 보내지 않고, 다른 줄은 그대로 나간다")
    void retryAfterBlocksOnlyThatLane() throws Exception {
        try (var server = new GatewayTestServer(); var gateway = new OpenAiGateway(LaneConfig.defaults())) {
            server.status = 429;
            server.headers.put("Retry-After", "5");
            server.body = "{\"error\":{\"code\":\"rate_limit_exceeded\",\"message\":\"Rate limit reached\"}}";
            var first = catchThrowableOfType(ProviderQuotaException.class, () -> send(gateway, Lane.IMAGE, server, "img-1"));
            assertThat(first.retryAfter()).isEqualTo(Duration.ofSeconds(5));
            assertThat(first.status()).isEqualTo(429);
            assertThat(server.requests.get()).isEqualTo(1);

            server.status = 200;
            server.headers.clear();
            server.body = "{}";
            // 같은 줄 — 보내지 않고 바로 같은 예외
            long t0 = System.nanoTime();
            assertThatThrownBy(() -> send(gateway, Lane.IMAGE, server, "img-2")).isInstanceOf(ProviderQuotaException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(500));
            assertThat(server.arrivals).doesNotContain("img-2");
            // 다른 줄 — 영향 없음
            assertThat(send(gateway, Lane.DIALOGUE_URGENT, server, "chat")).isEqualTo(200);
            assertThat(send(gateway, Lane.DIALOGUE_BACKGROUND, server, "bg")).isEqualTo(200);

            // 5초 뒤 같은 줄이 다시 나간다
            long deadline = System.currentTimeMillis() + 7_000;
            while (gateway.blockedFor(Lane.IMAGE).compareTo(Duration.ZERO) > 0 && System.currentTimeMillis() < deadline) Thread.sleep(100);
            assertThat(send(gateway, Lane.IMAGE, server, "img-3")).isEqualTo(200);
            assertThat(server.arrivals).containsExactly("img-1", "chat", "bg", "img-3");
        }
    }

    @Test
    @DisplayName("본문 insufficient_quota 도 한도 — Retry-After 가 없으면 60초 막는다")
    void insufficientQuotaUsesDefaultBlock() throws Exception {
        try (var server = new GatewayTestServer(); var gateway = new OpenAiGateway(LaneConfig.defaults())) {
            server.status = 429;
            server.body = "{\"error\":{\"code\":\"insufficient_quota\",\"type\":\"insufficient_quota\"}}";
            var e = catchThrowableOfType(ProviderQuotaException.class, () -> send(gateway, Lane.DIALOGUE_URGENT, server, "chat"));
            assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(60));
            assertThat(gateway.blockedFor(Lane.DIALOGUE_URGENT)).isBetween(Duration.ofSeconds(55), Duration.ofSeconds(60));
            assertThat(gateway.blockedFor(Lane.IMAGE)).isEqualTo(Duration.ZERO);
        }
    }

    @Test
    @DisplayName("5xx → ProviderServerException, 줄은 막지 않는다")
    void serverErrorIsTypedAndDoesNotBlock() throws Exception {
        try (var server = new GatewayTestServer(); var gateway = new OpenAiGateway(LaneConfig.defaults())) {
            server.status = 503;
            var e = catchThrowableOfType(ProviderServerException.class, () -> send(gateway, Lane.IMAGE, server, "a"));
            assertThat(e.status()).isEqualTo(503);
            assertThat(gateway.blockedFor(Lane.IMAGE)).isEqualTo(Duration.ZERO);
            server.status = 200;
            assertThat(send(gateway, Lane.IMAGE, server, "b")).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("연결 실패(네트워크)도 ProviderServerException")
    void networkFailureIsServerError() throws Exception {
        int port;
        try (var s = new java.net.ServerSocket(0)) { port = s.getLocalPort(); }
        try (var gateway = new OpenAiGateway(LaneConfig.defaults())) {
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/v1/x"))
                    .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build();
            assertThatThrownBy(() -> {
                try (var call = gateway.call(Lane.IMAGE, "m", ProviderUsageRecorder.NO_OP)) { call.send(req); }
            }).isInstanceOf(ProviderServerException.class);
        }
    }
}
