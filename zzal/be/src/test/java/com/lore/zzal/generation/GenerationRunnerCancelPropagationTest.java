package com.lore.zzal.generation;

import com.lore.zzal.alert.ZzalAlerts;
import com.lore.zzal.generation.client.ModelSpec;
import com.lore.zzal.generation.client.OpenAiTextClient;
import com.lore.zzal.generation.client.ProviderUsageRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-1 · P1-7 (설계 3-8): 바깥(부화 스레드)이 끼어들기를 받으면 안쪽 단계 호출도 끊긴다.
 *
 * <h3>★ 왜 필요한가</h3>
 * 단계는 다른 스레드에서 돈다. 바깥이 끊겨도 안쪽을 끊지 않으면 OpenAI 호출이 끝까지 가서
 * <b>아무도 받지 않는 응답에 돈이 나간다</b>. 종료(3-4) 때 진행 중 단계를 거두는 것도 이 전달에 기댄다.
 */
@DisplayName("생성 실행기 — 바깥 끼어들기가 안쪽 호출까지 간다")
class GenerationRunnerCancelPropagationTest {

    private static final Long JOB = 1L;

    private GenerationRecorder recorder;
    private GenerationRunner runner;
    private final AtomicInteger stepIds = new AtomicInteger();

    @BeforeEach
    void setUp() {
        recorder = mock(GenerationRecorder.class);
        when(recorder.startStep(anyLong(), anyInt(), anyString())).thenAnswer(inv -> (long) stepIds.incrementAndGet());
        runner = new GenerationRunner(recorder, mock(ZzalAlerts.class));
        ReflectionTestUtils.setField(runner, "limitOverrideSeconds", 0);
    }

    private static GenerationStep step(String name, int limit, java.util.concurrent.Callable<StepResult> body) {
        return new GenerationStep() {
            @Override public String name() { return name; }
            @Override public int limitSeconds() { return limit; }
            @Override public String label() { return name; }
            @Override public StepResult run(StepContext ctx) throws Exception { return body.call(); }
        };
    }

    private Thread outer(List<GenerationStep> stage, AtomicReference<RunResult> out) {
        Thread t = new Thread(() -> out.set(runner.run(JOB, new StepContext(7L, "여울", null, "v1"),
                List.of(stage), List.of())), "hatch-outer");
        t.start();
        return t;
    }

    @Test
    @DisplayName("★ 바깥 스레드를 끊으면 안쪽 단계가 InterruptedException 을 받고, 단계는 INTERRUPTED·비용 0")
    void outerInterruptReachesInnerStep() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch innerInterrupted = new CountDownLatch(1);
        GenerationStep blocking = step("grid", 60, () -> {
            started.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                innerInterrupted.countDown();
                throw e;
            }
            return StepResult.image("grid", "grid.png", "m", new BigDecimal("0.086"));
        });

        AtomicReference<RunResult> result = new AtomicReference<>();
        Thread t = outer(List.of(blocking), result);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        t.interrupt();

        assertThat(innerInterrupted.await(3, TimeUnit.SECONDS))
                .as("바깥 끼어들기 뒤에도 안쪽 단계가 계속 돌면 안 된다").isTrue();
        t.join(5_000);
        assertThat(t.isAlive()).isFalse();

        ArgumentCaptor<GenErrorCode> code = ArgumentCaptor.forClass(GenErrorCode.class);
        verify(recorder, timeout(2_000)).failStep(eq(1L), code.capture(), eq(BigDecimal.ZERO));
        assertThat(code.getValue().name()).isEqualTo("INTERRUPTED");
        assertThat(result.get()).isNotNull();
        assertThat(result.get().success()).isFalse();
        assertThat(result.get().errorCode().name()).isEqualTo("INTERRUPTED");
    }

    @Test
    @DisplayName("★ 끼어들기가 JDK HttpClient 요청을 실제로 끊는다 — 서버가 연결 끊김(EOF)을 본다")
    void interruptClosesTheHttpRequest() throws Exception {
        CountDownLatch requestArrived = new CountDownLatch(1);
        CountDownLatch clientClosed = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread srv = new Thread(() -> {
                try (Socket s = server.accept()) {
                    InputStream in = s.getInputStream();
                    // 요청 머리와 몸을 다 읽은 뒤에도 응답하지 않는다(OpenAI 가 오래 생각하는 중).
                    byte[] buf = new byte[8192];
                    int n = in.read(buf);
                    if (n > 0) requestArrived.countDown();
                    while (true) {
                        int r = in.read(buf);
                        if (r < 0) { clientClosed.countDown(); return; }
                    }
                } catch (Exception e) {
                    clientClosed.countDown();   // 연결 재설정도 끊김이다
                }
            }, "never-answering-openai");
            srv.setDaemon(true);
            srv.start();

            String base = "http://127.0.0.1:" + server.getLocalPort();
            OpenAiTextClient client = new OpenAiTextClient(null, "SYNTHETIC_NOT_A_KEY", 64, ProviderUsageRecorder.NO_OP, base);
            GenerationStep identity = step("identity", 60, () -> {
                var r = client.generate("p", List.of(), ModelSpec.of("gpt-5"));
                return StepResult.text("identity", r.text(), "gpt-5", r.costUsd());
            });

            AtomicReference<RunResult> result = new AtomicReference<>();
            Thread t = outer(List.of(identity), result);
            assertThat(requestArrived.await(5, TimeUnit.SECONDS)).isTrue();
            t.interrupt();

            assertThat(clientClosed.await(3, TimeUnit.SECONDS))
                    .as("끊긴 단계의 HTTP 요청이 서버 쪽에서 닫혀야 과금 대기가 끝난다").isTrue();
            t.join(5_000);
        }
    }

    @Test
    @DisplayName("★ 창구 실행기가 먼저 닫혔으면(종료 순서) 단계 제출 거부를 INTERRUPTED 로 적고 돌아간다 — 잡이 RUNNING 고아로 남지 않는다")
    void rejectedSubmitIsRecordedAsInterrupted() {
        var gateway = new com.lore.zzal.openai.OpenAiGateway(com.lore.zzal.openai.LaneConfig.defaults());
        gateway.close();
        GenerationRunner closed = new GenerationRunner(recorder, mock(ZzalAlerts.class), gateway);
        ReflectionTestUtils.setField(closed, "limitOverrideSeconds", 0);
        GenerationStep never = step("grid", 60, () -> StepResult.image("grid", "grid.png", "m", BigDecimal.ONE));

        RunResult result = closed.run(JOB, new StepContext(7L, "여울", null, "v1"), List.of(List.of(never)), List.of());

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode().name()).isEqualTo("INTERRUPTED");
        verify(recorder).failStep(eq(1L), eq(GenErrorCode.INTERRUPTED), eq(BigDecimal.ZERO));
        verify(recorder).failJob(eq(JOB), eq(GenErrorCode.INTERRUPTED), org.mockito.ArgumentMatchers.any());
        assertThat(Thread.interrupted()).as("종료 중이라는 표시를 부르는 쪽에 남긴다").isTrue();
    }
}
