package com.lore.zzal.generation;

import com.lore.zzal.alert.ZzalAlerts;
import com.lore.zzal.generation.steps.Layer2PostStep;
import com.lore.zzal.generation.steps.MotionPostStep;
import com.lore.zzal.generation.steps.PostProcessStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P1-7 (설계 3-8): CPU 를 쓰는 후처리(파이썬 자르기)는 가상 스레드라도 동시에 2개까지.
 * 단계 스레드 수에 상한이 없어졌으니 CPU 일은 따로 막아야 코어를 넘기지 않는다.
 */
@DisplayName("후처리 세마포어 — 4건을 동시에 넣어도 동시 실행 최대 2")
class PostprocessSemaphoreTest {

    @Test
    void atMostTwoPostprocessAtOnce() {
        var recorder = mock(GenerationRecorder.class);
        AtomicInteger ids = new AtomicInteger();
        when(recorder.startStep(anyLong(), anyInt(), anyString())).thenAnswer(inv -> (long) ids.incrementAndGet());
        var runner = new GenerationRunner(recorder, mock(ZzalAlerts.class));
        ReflectionTestUtils.setField(runner, "limitOverrideSeconds", 0);

        AtomicInteger now = new AtomicInteger();
        AtomicInteger max = new AtomicInteger();
        AtomicInteger otherMax = new AtomicInteger();
        AtomicInteger otherNow = new AtomicInteger();
        List<GenerationStep> stage = new ArrayList<>();
        String[] names = {PostProcessStep.NAME, Layer2PostStep.NAME, MotionPostStep.NAME, PostProcessStep.NAME};
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            int n = i;
            stage.add(step(name + "#" + n, name, () -> {
                max.accumulateAndGet(now.incrementAndGet(), Math::max);
                try { Thread.sleep(300); } finally { now.decrementAndGet(); }
            }));
        }
        // 후처리가 아닌 단계(그림 호출)는 이 세마포어에 걸리지 않는다.
        for (int i = 0; i < 3; i++) {
            stage.add(step("grid#" + i, "grid", () -> {
                otherMax.accumulateAndGet(otherNow.incrementAndGet(), Math::max);
                try { Thread.sleep(300); } finally { otherNow.decrementAndGet(); }
            }));
        }

        RunResult r = runner.run(1L, new StepContext(7L, "여울", null, "v1"), List.of(stage), List.of());

        assertThat(r.success()).isTrue();
        assertThat(max.get()).isEqualTo(2);
        assertThat(otherMax.get()).isEqualTo(3);
    }

    interface Body { void run() throws Exception; }

    /** 기록 이름(key)은 겹치지 않게, 후처리 판정 이름(name)은 실제 단계 이름으로. */
    private static GenerationStep step(String key, String name, Body body) {
        return new GenerationStep() {
            @Override public String name() { return name; }
            @Override public int limitSeconds() { return 30; }
            @Override public String label() { return key; }
            @Override public StepResult run(StepContext ctx) throws Exception {
                body.run();
                return StepResult.free(key);
            }
        };
    }
}
