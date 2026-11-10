package com.lore.zzal.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.common.analytics.AnalyticsEvent;
import com.lore.common.analytics.AnalyticsEventRepository;
import com.lore.zzal.generation.client.PersistentProviderUsageRecorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P1-5 (설계 2-3): 호출 1건 = 시작 1행 + 사용량 1행. 같은 callId 로 잇고, 사용량 행에 줄·자리 대기·동시 실행 수가 실린다.
 */
@DisplayName("창구 기록 — 호출 1건에 started 1 · usage 1, 줄 지표가 채워진다")
class OpenAiGatewayUsageRowTest {

    @Test
    void oneCallOneStartedOneUsage() throws Exception {
        List<AnalyticsEvent> rows = new CopyOnWriteArrayList<>();
        var events = mock(AnalyticsEventRepository.class);
        when(events.insertOnce(any())).thenAnswer(inv -> { rows.add(inv.getArgument(0)); return 1; });
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var recorder = new PersistentProviderUsageRecorder(events, manager).forFeature("diary");
        var json = new ObjectMapper();

        try (var server = new GatewayTestServer(); var gateway = new OpenAiGateway(LaneConfig.defaults())) {
            server.delayMs = 400;
            // 느긋한 줄(자리 1)에 2건 — 두 번째는 자리를 기다린다.
            List<Future<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                calls.add(gateway.executor().submit(() -> {
                    try (var call = gateway.call(Lane.DIALOGUE_BACKGROUND, "gpt-5-mini", recorder)) {
                        int s = call.send(server.request("x")).statusCode();
                        call.success();
                        return s;
                    }
                }));
            }
            for (var c : calls) assertThat(c.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        }

        var started = rows.stream().filter(r -> r.getName().equals("zzal_provider_call_started")).toList();
        var usage = rows.stream().filter(r -> r.getName().equals("zzal_provider_usage")).toList();
        assertThat(started).hasSize(2);
        assertThat(usage).hasSize(2);
        long maxWait = 0;
        for (var u : usage) {
            var p = json.readTree(u.getProps());
            assertThat(p.path("lane").asText()).isEqualTo("dialogue-background");
            assertThat(p.has("queue_wait_ms")).isTrue();
            assertThat(p.path("in_flight_at_start").asInt()).isEqualTo(1);
            assertThat(p.path("call_id").asText()).isEqualTo(u.getEventId());
            maxWait = Math.max(maxWait, p.path("queue_wait_ms").asLong());
            // 같은 callId 의 시작 행이 하나 있다
            assertThat(started).filteredOn(s -> {
                try { return json.readTree(s.getProps()).path("call_id").asText().equals(u.getEventId()); }
                catch (Exception e) { return false; }
            }).hasSize(1);
        }
        assertThat(maxWait).as("두 번째 호출은 첫 호출이 끝날 때까지 자리를 기다렸다").isGreaterThanOrEqualTo(250L);
    }
}
