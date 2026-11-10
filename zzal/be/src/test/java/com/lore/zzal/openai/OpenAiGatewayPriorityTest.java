package com.lore.zzal.openai;

import com.lore.zzal.generation.client.ProviderUsageRecorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-3 (설계 2-1 사이드 이펙트): 그림 줄을 부화와 맞춤·심화가 함께 쓰므로, 대기열에서 부화가 먼저 나간다.
 */
@DisplayName("창구 우선순위 — 그림 줄 대기열에서 부화가 맞춤 뒤에 서지 않는다")
class OpenAiGatewayPriorityTest {

    @Test
    void hatchJumpsAheadOfQueuedCustom() throws Exception {
        var lanes = new EnumMap<>(LaneConfig.defaults());
        lanes.put(Lane.IMAGE, new LaneConfig(1, 0, LaneConfig.defaults().get(Lane.IMAGE).connectTimeout()));
        try (var server = new GatewayTestServer(); var gateway = new OpenAiGateway(lanes)) {
            server.delayMs = 600;
            // 자리 하나를 먼저 잡아 둔다.
            Future<?> first = submit(gateway, server, "busy", null);
            waitUntil(() -> server.inFlight.get() == 1);
            // 맞춤 2건이 먼저 줄을 서고, 그 뒤에 부화 1건.
            Future<?> c1 = submit(gateway, server, "custom-1", null);
            waitUntil(() -> gateway.waiting(Lane.IMAGE) == 1);
            Future<?> c2 = submit(gateway, server, "custom-2", null);
            waitUntil(() -> gateway.waiting(Lane.IMAGE) == 2);
            Future<?> hatch = submit(gateway, server, "hatch", CallPriority.HATCH);
            waitUntil(() -> gateway.waiting(Lane.IMAGE) == 3);

            for (var f : List.of(first, c1, c2, hatch)) f.get(10, TimeUnit.SECONDS);
            assertThat(server.arrivals).containsExactly("busy", "hatch", "custom-1", "custom-2");
            assertThat(server.maxInFlight.get()).isEqualTo(1);
        }
    }

    private static Future<?> submit(OpenAiGateway gateway, GatewayTestServer server, String tag, CallPriority priority) {
        return gateway.executor().submit(() -> {
            try (var p = priority == null ? null : CallPriority.open(priority);
                 var call = gateway.call(Lane.IMAGE, "gpt-image-2", ProviderUsageRecorder.NO_OP)) {
                return call.send(server.request(tag)).statusCode();
            }
        });
    }

    private static void waitUntil(java.util.function.BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("timed out");
            Thread.sleep(10);
        }
    }
}
