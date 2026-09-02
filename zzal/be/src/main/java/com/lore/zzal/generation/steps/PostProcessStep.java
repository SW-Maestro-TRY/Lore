package com.lore.zzal.generation.steps;

import com.lore.zzal.generation.GenerationStep;
import com.lore.zzal.generation.StepContext;
import com.lore.zzal.generation.StepResult;
import com.lore.zzal.generation.client.PostProcessor;
import org.springframework.stereotype.Component;

/**
 * 4단계 — 격자 한 장을 잘라 8개 움짤로 만든다. **우리 계산이라 돈이 안 든다.**
 *
 * 하는 일 — 초록 배경 제거 · 16칸 절단 · 이물질 제거 · 발 높이 정렬 · 투명 배경 조립.
 * 이 로직은 상훈님이 실험에서 여러 사고를 잡아 가며 다듬은 것이라(마젠타 격자점·땀 오인식·
 * 머리 틈 초록 잔여) 자바로 다시 쓰지 않고 **검증된 파이썬을 그대로 실행**한다.
 *
 * 실측 1~2초.
 */
@Component
public class PostProcessStep implements GenerationStep {

    public static final String NAME = "postprocess";

    private final PostProcessor postProcessor;

    public PostProcessStep(PostProcessor postProcessor) {
        this.postProcessor = postProcessor;
    }

    @Override
    public String name() {
        return NAME;
    }

    /** 우리 서버 안 계산이라 늘어질 이유가 없다. */
    @Override
    public int limitSeconds() {
        return 30;
    }

    @Override
    public String label() {
        return "깨어날 준비를 하는 중";
    }

    @Override
    public StepResult run(StepContext ctx) throws Exception {
        postProcessor.split(ctx.image(GridStep.NAME), "images/zzal/pets/%d".formatted(ctx.petId()));
        return StepResult.free(NAME);
    }
}
