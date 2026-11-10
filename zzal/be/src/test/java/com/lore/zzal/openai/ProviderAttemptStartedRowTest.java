package com.lore.zzal.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.common.analytics.AnalyticsEvent;
import com.lore.common.analytics.AnalyticsEventRepository;
import com.lore.zzal.generation.client.PersistentProviderUsageRecorder;
import com.lore.zzal.generation.client.ProviderUsageContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P1-5 (설계 1-3): 보내기 직전에 "시작" 기록을 따로 커밋한다. JVM 이 호출 도중 죽어도
 * "시작만 있고 끝이 없는 호출"이 남아 과금됐을 수도 있는 끊긴 호출을 셀 수 있다.
 */
@DisplayName("호출 전 기록 — 막힌 호출 중에도 시작 행은 이미 커밋됐다")
class ProviderAttemptStartedRowTest {

    private final List<AnalyticsEvent> rows = new CopyOnWriteArrayList<>();
    private final AtomicInteger commits = new AtomicInteger();

    private PersistentProviderUsageRecorder writer(AnalyticsEventRepository events) {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        doAnswer(inv -> { commits.incrementAndGet(); return null; }).when(manager).commit(any());
        return new PersistentProviderUsageRecorder(events, manager);
    }

    private AnalyticsEventRepository capturing() {
        var events = mock(AnalyticsEventRepository.class);
        when(events.insertOnce(any())).thenAnswer(inv -> { rows.add(inv.getArgument(0)); return 1; });
        return events;
    }

    private List<AnalyticsEvent> named(String name) {
        return rows.stream().filter(r -> r.getName().equals(name)).toList();
    }

    @Test
    @DisplayName("OpenAI 가 대답하지 않는 동안 started 1행이 커밋돼 있고, 끝 행은 아직 없다")
    void startedCommittedWhileCallIsBlocked() throws Exception {
        var recorder = writer(capturing()).forFeature("chat");
        try (var server = new GatewayTestServer(); var gateway = new OpenAiGateway(LaneConfig.defaults())) {
            server.delayMs = 2_000;
            var done = gateway.executor().submit(() -> {
                try (var usage = ProviderUsageContext.open("ordinary_chat", 7L, 11L, "step-3", 2);
                     var call = gateway.call(Lane.DIALOGUE_URGENT, "gpt-5-mini", recorder)) {
                    return call.send(server.request("x")).statusCode();
                }
            });
            long deadline = System.currentTimeMillis() + 5_000;
            while (server.inFlight.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10);
            assertThat(server.inFlight.get()).isEqualTo(1);

            assertThat(named("zzal_provider_call_started")).hasSize(1);
            assertThat(named("zzal_provider_usage")).isEmpty();
            assertThat(commits.get()).isGreaterThanOrEqualTo(1);
            var props = new ObjectMapper().readTree(named("zzal_provider_call_started").getFirst().getProps());
            assertThat(props.path("feature").asText()).isEqualTo("ordinary_chat");
            assertThat(props.path("pet_id").asLong()).isEqualTo(7L);
            assertThat(props.path("job_id").asLong()).isEqualTo(11L);
            assertThat(props.path("request_id").asText()).isEqualTo("step-3");
            assertThat(props.path("model_requested").asText()).isEqualTo("gpt-5-mini");
            assertThat(props.path("lane").asText()).isEqualTo("dialogue-urgent");
            assertThat(props.path("call_id").asText()).isNotBlank();

            assertThat(done.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("시작 기록이 막혀도(연결을 못 얻음) 유료 호출은 0.5초 안팎만 늦고 그대로 나간다")
    void stuckStartedWriteDoesNotBlockTheCall() throws Exception {
        var events = mock(AnalyticsEventRepository.class);
        when(events.insertOnce(any())).thenAnswer(inv -> {
            AnalyticsEvent row = inv.getArgument(0);
            if (row.getName().equals("zzal_provider_call_started")) Thread.sleep(30_000);   // 풀이 바닥난 DB
            rows.add(row);
            return 1;
        });
        var recorder = writer(events).forFeature("chat");
        try (var server = new GatewayTestServer(); var gateway = new OpenAiGateway(LaneConfig.defaults())) {
            long t0 = System.nanoTime();
            try (var call = gateway.call(Lane.DIALOGUE_URGENT, "gpt-5-mini", recorder)) {
                assertThat(call.send(server.request("x")).statusCode()).isEqualTo(200);
            }
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isLessThan(2_000L);
            assertThat(server.requests.get()).isEqualTo(1);
            assertThat(named("zzal_provider_usage")).hasSize(1);
        }
    }

    @Test
    @DisplayName("시작 기록이 예외로 실패해도 호출은 나가고 끝 기록은 남는다")
    void failingStartedWriteDoesNotBlockTheCall() throws Exception {
        var events = mock(AnalyticsEventRepository.class);
        when(events.insertOnce(any())).thenAnswer(inv -> {
            AnalyticsEvent row = inv.getArgument(0);
            if (row.getName().equals("zzal_provider_call_started")) throw new IllegalStateException("synthetic outage");
            rows.add(row);
            return 1;
        });
        var recorder = writer(events).forFeature("chat");
        try (var server = new GatewayTestServer(); var gateway = new OpenAiGateway(LaneConfig.defaults())) {
            try (var call = gateway.call(Lane.DIALOGUE_URGENT, "gpt-5-mini", recorder)) {
                assertThat(call.send(server.request("x")).statusCode()).isEqualTo(200);
            }
            assertThat(server.requests.get()).isEqualTo(1);
            assertThat(named("zzal_provider_usage")).hasSize(1);
        }
    }
}
