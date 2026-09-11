package com.lore.zzal.generation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 실행기 — 묶음 안은 나란히, 묶음 사이는 순서대로. 한쪽이 실패해도 다른 쪽은 버리지 않는다.
 *
 * <h3>★ 왜 실패를 일부러 일으키나</h3>
 * 정상 경로만 보면 재시도·건너뛰기가 <b>한 번도 실행된 적 없이</b> 배포된다. 격자 두 장을 나란히
 * 굽게 되면서 "한쪽만 실패" 라는 새 경우가 생겼고, 그때 성한 쪽을 버리면 그 한 장 값을 다시 쓴다.
 */
@DisplayName("생성 실행기 — 나란히 굽기와 실패 경로")
class GenerationRunnerTest {

    private static final Long JOB = 1L;

    private GenerationRecorder recorder;
    private GenerationRunner runner;
    private final AtomicInteger stepIds = new AtomicInteger();
    private final Map<Long, String> startedSteps = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        recorder = mock(GenerationRecorder.class);
        when(recorder.startStep(anyLong(), anyInt(), anyString())).thenAnswer(inv -> {
            long id = stepIds.incrementAndGet();
            startedSteps.put(id, inv.getArgument(2));
            return id;
        });
        runner = new GenerationRunner(recorder);
        ReflectionTestUtils.setField(runner, "limitOverrideSeconds", 0);
    }

    /** 이름·비용·동작을 마음대로 정하는 가짜 단계. */
    private static GenerationStep step(String name, int limit, java.util.concurrent.Callable<StepResult> body) {
        return new GenerationStep() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int limitSeconds() {
                return limit;
            }

            @Override
            public String label() {
                return name;
            }

            @Override
            public StepResult run(StepContext ctx) throws Exception {
                return body.call();
            }
        };
    }

    private static GenerationStep ok(String name, String cost) {
        return step(name, 5, () -> StepResult.image(name, name + ".png", "m", new BigDecimal(cost)));
    }

    private static GenerationStep boom(String name, String message) {
        return step(name, 5, () -> {
            throw new IllegalStateException(message);
        });
    }

    private static StepContext ctx() {
        return new StepContext(7L, "여울", null, "v2");
    }

    @Test
    @DisplayName("★ 한 묶음의 두 단계가 나란히 돈다 — 순서대로였다면 걸릴 시간보다 짧다")
    void stageRunsConcurrently() throws Exception {
        CountDownLatch both = new CountDownLatch(2);
        GenerationStep a = step("grid", 5, () -> {
            both.countDown();
            // 짝이 시작하지 않으면 여기서 못 빠져나간다 — 순서대로라면 영영 안 끝난다
            assertThat(both.await(3, TimeUnit.SECONDS)).isTrue();
            return StepResult.image("grid", "grid.png", "m", new BigDecimal("0.086"));
        });
        GenerationStep b = step("grid2", 5, () -> {
            both.countDown();
            assertThat(both.await(3, TimeUnit.SECONDS)).isTrue();
            return StepResult.image("grid2", "grid2.png", "m", new BigDecimal("0.086"));
        });

        StepContext ctx = ctx();
        RunResult r = runner.run(JOB, ctx, List.of(List.of(a, b)), List.of());

        assertThat(r.success()).isTrue();
        assertThat(ctx.image("grid")).isEqualTo("grid.png");
        assertThat(ctx.image("grid2")).isEqualTo("grid2.png");
        assertThat(r.costUsd()).isEqualByComparingTo("0.172");   // ★ 두 장 값이 정확히 합쳐진다
    }

    @Test
    @DisplayName("★★ 한쪽만 실패 — 성한 쪽은 성공으로 남고, 다시 시도하면 실패한 쪽만 굽는다")
    void oneSideFailsTheOtherSurvives() {
        StepContext first = ctx();
        RunResult r = runner.run(JOB, first,
                List.of(List.of(ok("grid", "0.086"), boom("grid2", "upstream 500"))), List.of());

        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo(GenErrorCode.UNKNOWN);
        assertThat(r.costUsd()).isEqualByComparingTo("0.086");   // ★ 성한 쪽 값은 계산에 들어간다
        assertThat(first.image("grid")).isEqualTo("grid.png");   // ★ 산출물도 버리지 않는다
        assertThat(first.image("grid2")).isNull();

        ArgumentCaptor<StepResult> saved = ArgumentCaptor.forClass(StepResult.class);
        verify(recorder).succeedStep(anyLong(), saved.capture());
        assertThat(saved.getValue().name()).isEqualTo("grid");
        verify(recorder).failStep(anyLong(), eq(GenErrorCode.UNKNOWN));

        // ── 다시 시도 — 성공한 grid 는 기록으로 이어받고, grid2 만 구워야 한다
        List<String> ranAgain = new ArrayList<>();
        GenerationStep grid = step("grid", 5, () -> {
            ranAgain.add("grid");
            return StepResult.image("grid", "grid.png", "m", new BigDecimal("0.086"));
        });
        GenerationStep grid2 = step("grid2", 5, () -> {
            ranAgain.add("grid2");
            return StepResult.image("grid2", "grid2.png", "m", new BigDecimal("0.086"));
        });
        GenStepRecord done = GenStepRecord.start(JOB, 0, "grid", java.time.Instant.now());
        done.succeed("grid.png", null, "m", new BigDecimal("0.086"), java.time.Instant.now());

        StepContext second = ctx();
        RunResult retry = runner.run(2L, second, List.of(List.of(grid, grid2)), List.of(done));

        assertThat(retry.success()).isTrue();
        assertThat(ranAgain).containsExactly("grid2");            // ★ 격자 한 장 값을 다시 쓰지 않는다
        assertThat(retry.costUsd()).isEqualByComparingTo("0.086");
    }

    @Test
    @DisplayName("★ 거부는 다른 실패를 이긴다 — 재시도가 문단부터 다시 하도록")
    void moderationWinsOverUnknown() {
        RunResult r = runner.run(JOB, ctx(), List.of(List.of(
                boom("grid", "upstream 500"),
                boom("grid2", "content_policy violation"))), List.of());

        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo(GenErrorCode.MODERATION_BLOCKED);
    }

    @Test
    @DisplayName("묶음 사이는 순서대로 — 앞 묶음이 실패하면 뒤 묶음은 아예 안 돈다")
    void laterStagesDoNotRunAfterFailure() {
        List<String> ran = new ArrayList<>();
        GenerationStep post = step("post", 5, () -> {
            ran.add("post");
            return StepResult.free("post");
        });

        RunResult r = runner.run(JOB, ctx(),
                List.of(List.of(boom("grid", "boom")), List.of(post)), List.of());

        assertThat(r.success()).isFalse();
        assertThat(ran).isEmpty();
        verify(recorder, never()).succeedJob(anyLong(), any(), any());
    }

    @Test
    @DisplayName("시간 초과도 그 단계만 실패로 남는다")
    void timeoutFailsOnlyThatStep() {
        GenerationStep slow = step("grid2", 1, () -> {
            Thread.sleep(5_000);
            return StepResult.image("grid2", "grid2.png", "m", BigDecimal.ZERO);
        });

        StepContext ctx = ctx();
        RunResult r = runner.run(JOB, ctx, List.of(List.of(ok("grid", "0.086"), slow)), List.of());

        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo(GenErrorCode.TIMEOUT);
        assertThat(ctx.image("grid")).isEqualTo("grid.png");
        verify(recorder).failStep(anyLong(), eq(GenErrorCode.TIMEOUT));
    }
}
