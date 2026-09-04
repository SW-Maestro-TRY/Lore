package com.lore.zzal.generation.steps;

import com.lore.zzal.generation.GenerationStep;
import com.lore.zzal.generation.StepContext;
import com.lore.zzal.generation.StepResult;
import com.lore.zzal.generation.client.MotionPostProcessor;

/**
 * 모션 2단계 — 16프레임 격자를 잘라 움짤 하나로 만든다. <b>우리 계산이라 돈이 안 든다.</b>
 *
 * 하는 일은 부화 후처리와 거의 같다(초록 배경 제거 · 절단 · 이물질 제거 · 발 높이 정렬).
 * 다른 것은 정렬 방식뿐이고, 그 이유는 {@link MotionPostProcessor} 에 적어 두었다.
 */
public class MotionPostStep implements GenerationStep {

    public static final String NAME = "post16";

    private final MotionPostProcessor postProcessor;

    public MotionPostStep(MotionPostProcessor postProcessor) {
        this.postProcessor = postProcessor;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int limitSeconds() {
        return 60;
    }

    @Override
    public String label() {
        return "움직임을 다듬는 중";
    }

    @Override
    public StepResult run(StepContext ctx) throws Exception {
        String key = postProcessor.build(ctx.image(MotionGridStep.NAME), ctx.outputPrefix());
        // ★ 완성된 움짤의 키를 결과로 돌려준다. 이걸 모션 행에 적어야 화면이 그림을 찾는다.
        return StepResult.image(NAME, key, "none", java.math.BigDecimal.ZERO);
    }
}
