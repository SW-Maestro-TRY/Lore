package com.lore.zzal.openai;

import com.lore.zzal.alert.ZzalAlerts;
import com.lore.zzal.generation.GenErrorCode;
import com.lore.zzal.generation.GenerationRecorder;
import com.lore.zzal.generation.GenerationRunner;
import com.lore.zzal.generation.GenerationStep;
import com.lore.zzal.generation.RunResult;
import com.lore.zzal.generation.StepContext;
import com.lore.zzal.generation.StepResult;
import com.lore.zzal.generation.client.ProviderUsageRecorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P1 검토 표 2번: 단계 시간 제한은 <b>그림 줄 자리를 얻은 뒤부터</b> 센다. 자리를 기다린 시간은 단계 기한에 넣지 않는다.
 *
 * <h3>★ 왜 필요한가</h3>
 * 부화 셋과 맞춤 하나가 그림 줄 3을 같이 쓰면, 부화 한 단계가 맞춤 그림 한 장(최대 90초)을 기다린 뒤 자기 그림을
 * 그리다 120초를 넘겨 TIMEOUT 으로 실패할 수 있다. 그 실패는 그림 실패로 세여 그림 2회 상한에 닿는다.
 * 시험은 그림 줄을 1자리로 줄이고 단계 제한을 2초로 줄여 같은 모양을 짧게 만든다. 흉내 서버라 과금 0.
 */
@DisplayName("단계 기한 — 줄 자리를 얻은 뒤부터 센다")
class StepDeadlineAfterLaneTest {

    private static Map<Lane, LaneConfig> oneImageSeat() {
        Map<Lane, LaneConfig> m = new EnumMap<>(LaneConfig.defaults());
        m.put(Lane.IMAGE, new LaneConfig(1, 0, Duration.ofSeconds(2)));
        return m;
    }

    private static GenerationRunner runner(OpenAiGateway gateway) {
        GenerationRecorder recorder = mock(GenerationRecorder.class);
        AtomicLong ids = new AtomicLong();
        when(recorder.startStep(anyLong(), anyInt(), anyString())).thenAnswer(inv -> ids.incrementAndGet());
        GenerationRunner runner = new GenerationRunner(recorder, mock(ZzalAlerts.class), gateway);
        ReflectionTestUtils.setField(runner, "limitOverrideSeconds", 0);
        return runner;
    }

    /** 그림 줄로 한 번 보내는 단계(제한 limit 초). */
    private static GenerationStep imageStep(OpenAiGateway gateway, GatewayTestServer server, String tag, int limit) {
        return new GenerationStep() {
            @Override public String name() { return "grid"; }
            @Override public int limitSeconds() { return limit; }
            @Override public String label() { return "grid"; }
            @Override public StepResult run(StepContext ctx) throws Exception {
                try (var call = gateway.call(Lane.IMAGE, "gpt-image-2", ProviderUsageRecorder.NO_OP)) {
                    call.send(server.request(tag));
                }
                return StepResult.image("grid", "grid.png", "gpt-image-2", BigDecimal.ZERO);
            }
        };
    }

    /** 다른 기능(맞춤 등)이 그림 줄 자리 하나를 holdMs 동안 쥔다. 서버에 닿을 때까지 기다린다. */
    private static Future<?> holdSeat(OpenAiGateway gateway, GatewayTestServer server, long holdMs) throws Exception {
        server.delayByTag.put("holder", holdMs);
        Future<?> f = gateway.executor().submit(() -> {
            try (var call = gateway.call(Lane.IMAGE, "gpt-image-2", ProviderUsageRecorder.NO_OP)) {
                return call.send(server.request("holder")).statusCode();
            }
        });
        long until = System.currentTimeMillis() + 5_000;
        while (server.inFlight.get() < 1 && System.currentTimeMillis() < until) Thread.sleep(10);
        assertThat(server.inFlight.get()).isEqualTo(1);
        return f;
    }

    private static RunResult bake(GenerationRunner runner, GenerationStep step) {
        return runner.run(1L, new StepContext(7L, "여울", null, "v1"), List.of(List.of(step)), List.of());
    }

    @Test
    @DisplayName("★★ 자리를 3초 기다린 뒤 0.3초에 끝난 단계(제한 2초)는 TIMEOUT 이 아니다")
    void lateSeatDoesNotTimeOut() throws Exception {
        try (var server = new GatewayTestServer(); var gateway = new OpenAiGateway(oneImageSeat())) {
            server.delayByTag.put("step", 300L);
            Future<?> holder = holdSeat(gateway, server, 3_000);

            RunResult r = bake(runner(gateway), imageStep(gateway, server, "step", 2));

            assertThat(r.errorCode()).as("자리 대기 3초가 제한 2초를 먹으면 안 된다").isNull();
            assertThat(r.success()).isTrue();
            holder.get();
        }
    }

    @Test
    @DisplayName("★ 자리를 얻은 뒤에는 기한이 그대로 걸린다 — 2초 기다리고 3초 걸린 단계(제한 2초)는 TIMEOUT")
    void deadlineStillAppliesAfterSeat() throws Exception {
        try (var server = new GatewayTestServer(); var gateway = new OpenAiGateway(oneImageSeat())) {
            server.delayByTag.put("step", 3_000L);
            Future<?> holder = holdSeat(gateway, server, 2_000);

            long t0 = System.nanoTime();
            RunResult r = bake(runner(gateway), imageStep(gateway, server, "step", 2));
            long tookMs = (System.nanoTime() - t0) / 1_000_000;

            assertThat(r.errorCode()).isEqualTo(GenErrorCode.TIMEOUT);
            assertThat(tookMs).as("자리 얻은 시각(약 2초) + 제한 2초 근처에서 끊는다").isBetween(3_000L, 4_900L);
            holder.get();
        }
    }

    @Test
    @DisplayName("★ 안전망 — 자리 대기는 상한(설정 lane-wait-cap-seconds)까지만 빼 준다. 넘으면 TIMEOUT")
    void laneWaitBeyondCapTimesOut() throws Exception {
        try (var server = new GatewayTestServer(); var gateway = new OpenAiGateway(oneImageSeat())) {
            server.delayByTag.put("step", 100L);
            Future<?> holder = holdSeat(gateway, server, 4_000);
            GenerationRunner runner = runner(gateway);
            ReflectionTestUtils.setField(runner, "laneWaitCapSeconds", 1);

            long t0 = System.nanoTime();
            RunResult r = bake(runner, imageStep(gateway, server, "step", 1));
            long tookMs = (System.nanoTime() - t0) / 1_000_000;

            assertThat(r.errorCode()).isEqualTo(GenErrorCode.TIMEOUT);
            assertThat(tookMs).as("제한 1초 + 대기 상한 1초 근처").isBetween(1_800L, 3_500L);
            holder.get();
        }
    }
}
