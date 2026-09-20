package com.lore.zzal.generation;

import com.lore.zzal.generation.steps.GridStep;
import com.lore.zzal.generation.steps.IdentityStep;
import com.lore.zzal.generation.steps.PostProcessStep;
import com.lore.zzal.generation.steps.SheetStep;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 파이프라인을 목으로 짤 때 쓰는 것들.
 *
 * ★ 목 단계는 {@code name()} 이 null 이다. {@link PipelineRegistry} 가 기동할 때
 *   "문단에 기대는 단계" 의 이름이 실제로 있는지 확인하므로, <b>이름이 있는 목</b>이 필요하다.
 *   그 확인은 조용히 걸러 내지 않으려고 일부러 터뜨리는 것이라(어긋난 격자가 사용자에게 가는 것을 막는다),
 *   시험 쪽이 이름을 채워 주는 것이 맞다.
 */
final class StepMocks {

    private StepMocks() {
    }

    /** 이름이 채워진 문단 단계 목. */
    static IdentityStep identity() {
        IdentityStep step = mock(IdentityStep.class);
        when(step.name()).thenReturn(IdentityStep.NAME);
        return step;
    }

    /** 시트 단계 목. */
    static SheetStep sheet() {
        SheetStep step = mock(SheetStep.class);
        when(step.name()).thenReturn(SheetStep.NAME);
        return step;
    }

    /** 후처리 단계 목. */
    static PostProcessStep post() {
        PostProcessStep step = mock(PostProcessStep.class);
        when(step.name()).thenReturn(PostProcessStep.NAME);
        return step;
    }

    /** 1층 격자 목. */
    static GridStep grid() {
        return grid(GridStep.NAME);
    }

    /** 2층 격자 목. */
    static GridStep grid2() {
        return grid(PostProcessStep.GRID2);
    }

    private static GridStep grid(String name) {
        GridStep step = mock(GridStep.class);
        when(step.name()).thenReturn(name);
        return step;
    }
}
