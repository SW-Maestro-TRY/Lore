package com.lore.zzal.generation.steps;

import com.lore.zzal.generation.GenerationStep;
import com.lore.zzal.generation.PromptLoader;
import com.lore.zzal.generation.StepContext;
import com.lore.zzal.generation.StepResult;
import com.lore.zzal.generation.client.ImageClient;
import com.lore.zzal.generation.client.ModelSpec;

import java.util.List;

/**
 * 모션 1단계 — 16프레임 격자를 굽는다. <b>한 동작이 한 장 통째로</b> 들어간다.
 *
 * <h3>부화 격자와 무엇이 다른가</h3>
 * 격자 골격은 똑같다 — 4x4 · 16칸 · 25개 격자점(5x5) · 초록 배경 · 정사각 1248.
 * 다른 것은 <b>칸의 의미</b>다.
 * <pre>
 *   부화   16칸 = 서로 독립인 2프레임 A-B 루프 8쌍
 *   모션   16칸 = 한 동작이 이어지는 하나의 루프
 * </pre>
 *
 * <h3>자리가 두 개다</h3>
 * 부화 격자는 {@code {IDENT}} 하나뿐이지만 여기는 {@code {MOTION}} 이 더 있다.
 * 어떤 동작인지를 적은 블록(TASK / MUST CHANGE / NEVER CHANGE / 16칸 포즈)이 그 자리에 들어간다.
 *
 * <h3>★ 시트와 문단을 다시 만들지 않는다</h3>
 * 둘 다 부화 때 만들어 펫에 저장돼 있다. 모션마다 다시 만들면 동작 하나당 $0.077 과
 * 1분 이상이 그냥 더 나가고, 무엇보다 <b>캐릭터가 조금씩 달라진다.</b>
 *
 * <h3>★ 원가</h3>
 * 부화는 격자 한 장에 8종이 나오지만 여기는 한 장에 한 동작이다. 즉 동작 수만큼 곱해진다.
 * 그래서 한 번에 여러 장을 굽지 않고 <b>1장 굽고 실패하면 다시</b> 하는 방식을 쓴다
 * (2026-09-03 상훈님 확정).
 */
public class MotionGridStep implements GenerationStep {

    public static final String NAME = "grid16";

    /** 프롬프트 템플릿의 자리들. 파일과 짝이다. */
    private static final String IDENT_SLOT = "{IDENT}";
    private static final String MOTION_SLOT = "{MOTION}";

    /** 앞에서 넘겨받는 재료의 이름표. */
    public static final String IDENTITY_IN = "identity";
    public static final String SHEET_IN = "sheet";
    public static final String MOTION_IN = "motionBlock";

    private final ImageClient imageClient;
    private final PromptLoader prompts;

    public MotionGridStep(ImageClient imageClient, PromptLoader prompts) {
        this.imageClient = imageClient;
        this.prompts = prompts;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int limitSeconds() {
        return 180;
    }

    @Override
    public String label() {
        return "새로운 동작을 익히는 중";
    }

    @Override
    public StepResult run(StepContext ctx) throws Exception {
        String prompt = prompts.prompt(ctx.version(), NAME);

        String identity = ctx.text(IDENTITY_IN);
        prompt = prompt.replace(IDENT_SLOT, identity == null ? "" : identity.trim());

        String motion = ctx.text(MOTION_IN);
        if (motion == null || motion.isBlank()) {
            // 동작 블록이 없으면 모델은 "무엇을" 그릴지 모른 채 16칸을 채우려 든다.
            // 조용히 이상한 결과를 내느니 여기서 멈춘다.
            throw new IllegalStateException("동작 블록이 비어 있습니다 — petId=" + ctx.petId());
        }
        prompt = prompt.replace(MOTION_SLOT, motion.trim());

        ModelSpec spec = prompts.model(ctx.version(), NAME);
        ImageClient.Result r = imageClient.generate(
                prompt,
                List.of(ctx.image(SHEET_IN)),
                ctx.outputPrefix() + "/grid.png",
                spec);

        return StepResult.image(NAME, r.imageKey(), spec.model(), r.costUsd());
    }
}
