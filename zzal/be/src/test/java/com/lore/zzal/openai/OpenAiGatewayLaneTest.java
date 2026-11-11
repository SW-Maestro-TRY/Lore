package com.lore.zzal.openai;

import com.lore.zzal.generation.client.ProviderUsageRecorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-3 (설계 2-1): 줄마다 동시 실행 상한. 그림 3 · 급한 대사 10(자리 대기 1초) · 느긋한 대사 1.
 */
@DisplayName("창구 줄 — 동시 실행 상한과 급한 대사 자리 대기 2초")
class OpenAiGatewayLaneTest {

    @Test
    @DisplayName("그림 4건을 넣으면 OpenAI 쪽 동시 처리 최대 3, 4번째는 자리가 날 때까지 기다렸다 나간다")
    void imageLaneCapsAtThree() throws Exception {
        try (var server = new GatewayTestServer(); var gateway = new OpenAiGateway(LaneConfig.defaults())) {
            server.delayMs = 1_500;
            List<Future<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                int n = i;
                calls.add(gateway.executor().submit(() -> {
                    try (var call = gateway.call(Lane.IMAGE, "gpt-image-2", ProviderUsageRecorder.NO_OP)) {
                        return call.send(server.request("image-" + n)).statusCode();
                    }
                }));
            }
            for (var c : calls) assertThat(c.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(server.requests.get()).isEqualTo(4);
            assertThat(server.maxInFlight.get()).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("급한 대사 11번째는 2초 기다린 뒤 자리 없음 예외 — OpenAI 로는 나가지 않는다(안 \"다\", 2026-11-11)")
    void urgentEleventhGivesUpAfterTwoSeconds() throws Exception {
        try (var server = new GatewayTestServer(); var gateway = new OpenAiGateway(LaneConfig.defaults())) {
            server.delayMs = 4_000;
            List<Future<Integer>> held = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                int n = i;
                held.add(gateway.executor().submit(() -> {
                    try (var call = gateway.call(Lane.DIALOGUE_URGENT, "gpt-5-mini", ProviderUsageRecorder.NO_OP)) {
                        return call.send(server.request("chat-" + n)).statusCode();
                    }
                }));
            }
            long deadline = System.currentTimeMillis() + 5_000;
            while (server.inFlight.get() < 10 && System.currentTimeMillis() < deadline) Thread.sleep(20);
            assertThat(server.inFlight.get()).isEqualTo(10);

            long t0 = System.nanoTime();
            assertThatThrownBy(() -> {
                try (var call = gateway.call(Lane.DIALOGUE_URGENT, "gpt-5-mini", ProviderUsageRecorder.NO_OP)) {
                    call.send(server.request("chat-11"));
                }
            }).isInstanceOf(LaneBusyException.class);
            long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertThat(waitedMs).isBetween(1_900L, 3_000L);
            assertThat(server.arrivals).doesNotContain("chat-11");
            for (var c : held) assertThat(c.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("느긋한 대사 줄은 한 번에 1건")
    void backgroundLaneIsSingle() throws Exception {
        try (var server = new GatewayTestServer(); var gateway = new OpenAiGateway(LaneConfig.defaults())) {
            server.delayMs = 500;
            List<Future<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                int n = i;
                calls.add(gateway.executor().submit(() -> {
                    try (var call = gateway.call(Lane.DIALOGUE_BACKGROUND, "gpt-5-mini", ProviderUsageRecorder.NO_OP)) {
                        return call.send(server.request("bg-" + n)).statusCode();
                    }
                }));
            }
            for (var c : calls) assertThat(c.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(server.maxInFlight.get()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("줄 사이는 자리를 나누지 않는다 — 그림 줄이 꽉 차도 급한 대사는 바로 나간다")
    void lanesAreIndependent() throws Exception {
        try (var server = new GatewayTestServer(); var gateway = new OpenAiGateway(LaneConfig.defaults())) {
            server.delayMs = 1_000;
            List<Future<Integer>> images = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                int n = i;
                images.add(gateway.executor().submit(() -> {
                    try (var call = gateway.call(Lane.IMAGE, "gpt-image-2", ProviderUsageRecorder.NO_OP)) {
                        return call.send(server.request("img-" + n)).statusCode();
                    }
                }));
            }
            Thread.sleep(200);
            long t0 = System.nanoTime();
            try (var call = gateway.call(Lane.DIALOGUE_URGENT, "gpt-5-mini", ProviderUsageRecorder.NO_OP)) {
                assertThat(call.send(server.request("chat")).statusCode()).isEqualTo(200);
            }
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isLessThan(1_800L);
            for (var c : images) c.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("★ 급한 대사 줄이 차면 온 순서대로 자리를 얻는다 — 늦게 온 손님이 먼저 가지 않는다")
    void urgentSeatsGoInArrivalOrder() throws Exception {
        java.util.Map<Lane, LaneConfig> lanes = new java.util.EnumMap<>(LaneConfig.defaults());
        lanes.put(Lane.DIALOGUE_URGENT, new LaneConfig(1, 5_000, java.time.Duration.ofSeconds(2)));
        try (var server = new GatewayTestServer(); var gateway = new OpenAiGateway(lanes)) {
            server.delayMs = 300;
            List<Future<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                int n = i;
                calls.add(gateway.executor().submit(() -> {
                    try (var call = gateway.call(Lane.DIALOGUE_URGENT, "gpt-5-mini", ProviderUsageRecorder.NO_OP)) {
                        return call.send(server.request("chat-" + n)).statusCode();
                    }
                }));
                // 다음 손님은 앞 손님이 줄에 선(또는 나간) 뒤에 온다.
                long until = System.currentTimeMillis() + 3_000;
                while (gateway.waiting(Lane.DIALOGUE_URGENT) + server.requests.get() < i + 1
                        && System.currentTimeMillis() < until) Thread.sleep(5);
            }
            for (var c : calls) assertThat(c.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(server.arrivals).containsExactly("chat-0", "chat-1", "chat-2", "chat-3", "chat-4");
        }
    }
}
