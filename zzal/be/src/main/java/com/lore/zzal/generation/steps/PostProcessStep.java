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

    /**
     * 1층만 굽되 출력은 <b>16종과 같은 자리</b>(basic/)에 두는 버전.
     *
     * ★ 왜 v1 과 자리가 다른가 — 화면이 기본 행동을 {@code .../basic/{key}.webp} 로 조립한다(api-v2.md 2절).
     *   v1 은 그 규약 이전의 8상태라 한 단 위에 떨어뜨리지만, v4 의 8종은 <b>16종의 앞 절반</b>이다.
     *   같은 자리에 놓아야 2층이 확정돼 붙을 때 앞 절반을 다시 굽지 않아도 된다.
     * ★ 이름은 카탈로그가 아니라 설정({@code app.zzal.hatch.states.v4})에서 온다 — 1층 8종의 key 정리가
     *   아직 진행 중이라, 카탈로그를 여기서 같이 건드리면 두 곳이 서로를 기다리게 된다.
     */
    private static final java.util.Set<String> LAYER1_ONLY_TO_BASIC = java.util.Set.of("v4");

    @Override
    public StepResult run(StepContext ctx) throws Exception {
        String grid2 = ctx.image(GRID2);
        if (grid2 == null) {
            if (LAYER1_ONLY_TO_BASIC.contains(ctx.version())) {
                // v4 — 1층 격자 1장 → 기본 행동 8종. 출력 이름은 설정 hatch.states.v4.
                postProcessor.split(ctx.image(GridStep.NAME),
                        "images/zzal/pets/%d/basic".formatted(ctx.petId()), ctx.version());
                return StepResult.free(NAME);
            }
            // v1 — 격자 1장 → 8상태(idle·eat·…). 출력 이름은 설정 hatch.states.v1.
            postProcessor.split(ctx.image(GridStep.NAME), "images/zzal/pets/%d".formatted(ctx.petId()), ctx.version());
            return StepResult.free(NAME);
        }
        // v2 — 격자 2장 → 기본 행동 16종. 출력 = basic/{key}.webp (api-v2.md 2절 규약), 이름은 카탈로그 key.
        String prefix = "images/zzal/pets/%d/basic".formatted(ctx.petId());
        postProcessor.split(ctx.image(GridStep.NAME), prefix, ctx.version(), keysOf(MotionLayer.BASIC_1));
        postProcessor.split(grid2, prefix, ctx.version(), keysOf(MotionLayer.BASIC_2));
        return StepResult.free(NAME);
    }

    /** v2 두 번째 격자의 단계 이름. */
    public static final String GRID2 = "grid2";

    private java.util.List<String> keysOf(MotionLayer layer) {
        return catalog.basic().stream().filter(m -> m.layer() == layer).map(MotionSpec::key).toList();
    }
}
