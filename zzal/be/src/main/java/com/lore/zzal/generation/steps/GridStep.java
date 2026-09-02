package com.lore.zzal.generation.steps;

import com.lore.zzal.generation.GenerationStep;
import com.lore.zzal.generation.PromptLoader;
import com.lore.zzal.generation.StepContext;
import com.lore.zzal.generation.StepResult;
import com.lore.zzal.generation.client.ModelSpec;
import com.lore.zzal.generation.client.ImageClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 3단계 — 4x4 격자를 굽는다. 8상태 × 2프레임이 한 장에 들어간다.
 *
 * ★ 한 장에 몰아 굽는 것이 원가 구조의 핵심이다. 8상태를 따로 구우면 8배가 든다.
 *
 * ★ 초록 배경과 격자점 규격이 후처리 스크립트와 짝이다. 프롬프트만 바꾸면 절단이 어긋난다.
 *
 * 실측 54~60초 · $0.086
 */
@Component
public class GridStep implements GenerationStep {

    public static final String NAME = "grid";

    private final ImageClient imageClient;
    private final PromptLoader prompts;

    public GridStep(ImageClient imageClient, PromptLoader prompts) {
        this.imageClient = imageClient;
        this.prompts = prompts;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int limitSeconds() {
        return 120;
    }

    @Override
    public String label() {
        return "움직임을 하나씩 익히는 중";
    }

    @Override
    public StepResult run(StepContext ctx) throws Exception {
        String prompt = prompts.prompt(ctx.version(), NAME);

        // 정체성 문단이 있으면 붙인다. 그 단계가 빠진 버전에서는 없이 간다.
        String identity = ctx.text(IdentityStep.NAME);
        if (identity != null && !identity.isBlank()) {
            prompt = prompt + "\n\n[이 캐릭터]\n" + identity;
        }

        List<String> refs = new ArrayList<>();
        refs.add(ctx.image(SheetStep.NAME));

        ModelSpec spec = prompts.model(ctx.version(), NAME);
        ImageClient.Result r = imageClient.generate(
                prompt, refs,
                "images/zzal/pets/%d/grid.png".formatted(ctx.petId()),
                spec);

        return StepResult.image(NAME, r.imageKey(), spec.model(), r.costUsd());
    }
}
