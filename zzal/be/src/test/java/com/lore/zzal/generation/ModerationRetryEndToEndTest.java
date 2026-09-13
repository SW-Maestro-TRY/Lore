package com.lore.zzal.generation;

import com.lore.zzal.generation.steps.GridStep;
import com.lore.zzal.generation.steps.IdentityStep;
import com.lore.zzal.generation.steps.PostProcessStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 거부(MODERATION) 실패 경로를 <b>진짜 실행기로</b> 밟아 본다 — 목이 아니라 실제 나란히 굽기로.
 *
 * <h3>★ 왜 실행기까지 태우나</h3>
 * 폐기 목록이 맞는지는 {@code ModerationDiscardTest} 가 본다. 여기서 보는 것은
 * <b>그 목록대로 폐기했을 때 재시도가 실제로 무엇을 다시 굽는가</b>이다.
 * 정상 경로만 보면 재시도는 한 번도 실행된 적 없이 배포된다.
 *
 * <h3>★★ 고치기 전에는 이랬다</h3>
 * 2층만 거부됐을 때 문단만 폐기하고 1층 격자는 성공 기록이 남아 <b>건너뛰어졌다.</b>
 * 그 결과 1층은 옛 문단으로 구운 그림, 2층은 새 문단으로 구운 그림이 되어 생김새가 어긋났다.
 */
@DisplayName("거부 재시도 — 실행기를 태워 무엇이 다시 구워지는지 본다")
class ModerationRetryEndToEndTest {

    private static final Long JOB = 1L;

    private GenerationRecorder recorder;
    private GenerationRunner runner;
    private final AtomicInteger stepIds = new AtomicInteger();
    private final Map<Long, String> started = new ConcurrentHashMap<>();
    private final List<String> baked = new ArrayList<>();

    @BeforeEach
    void setUp() {
        recorder = mock(GenerationRecorder.class);
        when(recorder.startStep(anyLong(), anyInt(), anyString())).thenAnswer(inv -> {
            long id = stepIds.incrementAndGet();
            started.put(id, inv.getArgument(2));
            return id;
        });
        runner = new GenerationRunner(recorder);
    }

    /** 이름을 기록하며 성공하는 단계 — 무엇이 실제로 구워졌는지 세려고. */
    private GenerationStep ok(String name) {
        return step(name, () -> {
            baked.add(name);
            return StepResult.image(name, name + ".png", "m", new BigDecimal("0.08"));
        });
    }

    /** 거부당하는 단계. */
    private GenerationStep blocked(String name) {
        return step(name, () -> {
            baked.add(name);
            throw new IllegalStateException("content_policy violation");
        });
    }

    private static GenerationStep step(String name, java.util.concurrent.Callable<StepResult> body) {
        return new GenerationStep() {
            @Override public String name() {
                return name;
            }

            @Override public int limitSeconds() {
                return 5;
            }

            @Override public String label() {
                return name;
            }

            @Override public StepResult run(StepContext ctx) throws Exception {
                return body.call();
            }
        };
    }

    @Test
    @DisplayName("★★ 2층만 거부 — 다시 시도할 때 문단과 격자 두 장이 모두 다시 구워진다")
    void moderationRebakesBothGrids() {
        List<List<GenerationStep>> stages = List.of(
                List.of(ok("sheet")),
                List.of(ok(IdentityStep.NAME)),
                List.of(ok(GridStep.NAME), blocked(PostProcessStep.GRID2)),
                List.of(ok("postprocess")));

        RunResult first = runner.run(JOB, new StepContext(7L, "여울", null, "v2"), stages, List.of());

        assertThat(first.success()).isFalse();
        assertThat(first.errorCode())
                .as("거부는 다른 실패를 이겨야 재시도가 문단부터 다시 한다")
                .isEqualTo(GenErrorCode.MODERATION_BLOCKED);
        assertThat(baked).containsExactlyInAnyOrder("sheet", IdentityStep.NAME,
                GridStep.NAME, PostProcessStep.GRID2);

        // ── 재시도 ────────────────────────────────────────────────────────
        // 고친 규칙대로 문단과 그것을 쓴 격자 둘을 폐기하고, 시트만 이어받는다.
        baked.clear();
        List<GenStepRecord> resume = List.of(succeeded("sheet"));

        List<List<GenerationStep>> retry = List.of(
                List.of(ok("sheet")),
                List.of(ok(IdentityStep.NAME)),
                List.of(ok(GridStep.NAME), ok(PostProcessStep.GRID2)),
                List.of(ok("postprocess")));
        RunResult second = runner.run(2L, new StepContext(7L, "여울", null, "v2"), retry, resume);

        assertThat(second.success()).isTrue();
        assertThat(baked)
                .as("★ 1층 격자가 여기 없으면 옛 문단으로 구운 그림이 그대로 남는다는 뜻이다")
                .contains(GridStep.NAME, PostProcessStep.GRID2, IdentityStep.NAME);
        assertThat(baked)
                .as("시트는 이어받으므로 다시 굽지 않는다 — 그 값을 두 번 쓰지 않는다")
                .doesNotContain("sheet");
    }

    @Test
    @DisplayName("★ 문단만 폐기했다면 1층이 건너뛰어진다 — 고치기 전의 모습")
    void discardingOnlyIdentityLeavesLayerOneStale() {
        // 옛 규칙: 문단만 폐기 → 격자 1층은 성공 기록이 남아 건너뛴다.
        List<GenStepRecord> resumeOldWay = List.of(succeeded("sheet"), succeeded(GridStep.NAME));

        List<List<GenerationStep>> stages = List.of(
                List.of(ok("sheet")),
                List.of(ok(IdentityStep.NAME)),
                List.of(ok(GridStep.NAME), ok(PostProcessStep.GRID2)),
                List.of(ok("postprocess")));
        runner.run(3L, new StepContext(7L, "여울", null, "v2"), stages, resumeOldWay);

        assertThat(baked)
                .as("1층이 안 구워진다 = 옛 문단으로 만든 그림이 새 문단의 2층과 짝이 된다")
                .doesNotContain(GridStep.NAME);
        assertThat(baked).contains(PostProcessStep.GRID2);
    }

    private static GenStepRecord succeeded(String name) {
        GenStepRecord rec = GenStepRecord.start(JOB, 0, name, java.time.Instant.EPOCH);
        rec.succeed(name + ".png", null, "m", BigDecimal.ZERO, java.time.Instant.EPOCH);
        return rec;
    }
}
