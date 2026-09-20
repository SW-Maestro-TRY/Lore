package com.lore.zzal.generation.steps;

import com.lore.zzal.generation.GenerationStep;
import com.lore.zzal.generation.PromptLoader;
import com.lore.zzal.generation.StepContext;
import com.lore.zzal.generation.StepResult;
import com.lore.zzal.generation.client.ModelSpec;
import com.lore.zzal.generation.client.ImageClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 1단계 — 원본 그림에서 캐릭터 시트를 만든다.
 *
 * 시트는 이후 모든 생성의 기준이 된다. 여기가 흔들리면 8상태가 전부 흔들린다.
 * 실측 54~60초 · $0.063 (2026-08-26, 5캐릭터)
 */
@Component
public class SheetStep implements GenerationStep {

    public static final String NAME = "sheet";

    private final ImageClient imageClient;
    private final PromptLoader prompts;

    public SheetStep(ImageClient imageClient, PromptLoader prompts) {
        this.imageClient = imageClient;
        this.prompts = prompts;
    }

    @Override
    public String name() {
        return NAME;
    }

    /** 실측의 2배. 이미지 API 가 붐빌 때 그만큼 늘어지는 것은 흔하다. */
    @Override
    public int limitSeconds() {
        return 120;
    }

    @Override
    public String label() {
        return "이 아이의 설정자료를 그리는 중";
    }

    @Override
    public StepResult run(StepContext ctx) throws Exception {
        String source = ctx.image("source");
        String outputKey = "images/zzal/pets/%d/sheet.png".formatted(ctx.petId());

        ModelSpec spec = prompts.model(ctx.version(), NAME);
        ImageClient.Result r = imageClient.generate(
                prompts.prompt(ctx.version(), NAME),
                List.of(source),
                outputKey,
                spec);

        return StepResult.image(NAME, r.imageKey(), spec.model(), r.costUsd());
    }
}
