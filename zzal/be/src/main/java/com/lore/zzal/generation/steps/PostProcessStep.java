package com.lore.zzal.generation.steps;

import com.lore.zzal.generation.GenerationStep;
import com.lore.zzal.generation.StepContext;
import com.lore.zzal.generation.StepResult;
import com.lore.zzal.generation.client.PostProcessor;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.MotionLayer;
import com.lore.zzal.motion.MotionSpec;
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
    private final MotionCatalog catalog;

    public PostProcessStep(PostProcessor postProcessor, MotionCatalog catalog) {
        this.postProcessor = postProcessor;
        this.catalog = catalog;
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
        String grid2 = ctx.image(GRID2);
        if (grid2 == null) {
            // v1 — 격자 1장 → 8상태(idle·eat·…). 출력 이름은 설정 hatch.states.v1.
            postProcessor.split(ctx.image(GridStep.NAME), "images/zzal/pets/%d".formatted(ctx.petId()));
            return StepResult.free(NAME);
        }
        // v2 — 격자 2장 → 기본 행동 16종. 출력 = basic/{key}.webp (api-v2.md 2절 규약), 이름은 카탈로그 key.
        String prefix = "images/zzal/pets/%d/basic".formatted(ctx.petId());
        postProcessor.split(ctx.image(GridStep.NAME), prefix, keysOf(MotionLayer.BASIC_1));
        postProcessor.split(grid2, prefix, keysOf(MotionLayer.BASIC_2));
        return StepResult.free(NAME);
    }

    /** v2 두 번째 격자의 단계 이름. */
    public static final String GRID2 = "grid2";

    private java.util.List<String> keysOf(MotionLayer layer) {
        return catalog.basic().stream().filter(m -> m.layer() == layer).map(MotionSpec::key).toList();
    }
}
