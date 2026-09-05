package com.lore.zzal.generation.steps;

import com.lore.zzal.generation.GenerationStep;
import com.lore.zzal.generation.PromptLoader;
import com.lore.zzal.generation.StepContext;
import com.lore.zzal.generation.StepResult;
import com.lore.zzal.generation.client.ModelSpec;
import com.lore.zzal.generation.client.ImageClient;

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
/**
 * 격자 한 장을 굽는다. v2 는 이 단계가 두 번 돈다 — {@code grid}(1층 8종)·{@code grid2}(2층 8종). 이름만 다르고
 * 프롬프트 파일({@code prompt/{버전}/{이름}.txt})과 출력 키({@code {이름}.png})가 그 이름을 따른다.
 * 빈은 {@link com.lore.zzal.generation.GenerationConfig} 에서 이름별로 만든다.
 */
public class GridStep implements GenerationStep {

    public static final String NAME = "grid";

    /** 격자 프롬프트에서 정체성 문단이 들어갈 자리. 프롬프트 파일과 짝이다. */
    private static final String IDENT_SLOT = "{IDENT}";

    private final ImageClient imageClient;
    private final PromptLoader prompts;
    private final String name;

    public GridStep(ImageClient imageClient, PromptLoader prompts) {
        this(imageClient, prompts, NAME);
    }

    public GridStep(ImageClient imageClient, PromptLoader prompts, String name) {
        this.imageClient = imageClient;
        this.prompts = prompts;
        this.name = name;
    }

    @Override
    public String name() {
        return name;
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
        // ★ 격자 프롬프트에는 {IDENT} 자리가 있다. 그 자리에 정체성 문단이 그대로 들어간다
        //   — 뒤에 덧붙이는 게 아니다. 실험에서 확립한 시드가 정확히 이 구조다
        //   (템플릿 6,195자 + 문단 530자 → 시드 6,047자).
        String prompt = prompts.prompt(ctx.version(), name);
        String identity = ctx.text(IdentityStep.NAME);

        if (identity != null && !identity.isBlank()) {
            prompt = prompt.replace(IDENT_SLOT, identity.trim());
        } else if (prompt.contains(IDENT_SLOT)) {
            // 문단 단계가 빠진 버전인데 자리가 남아 있으면, 그대로 보내면 모델이
            // "{IDENT}" 라는 글자를 그리려 든다. 자리만 지운다.
            prompt = prompt.replace(IDENT_SLOT, "").trim();
        }

        List<String> refs = new ArrayList<>();
        refs.add(ctx.image(SheetStep.NAME));

        ModelSpec spec = prompts.model(ctx.version(), name);
        ImageClient.Result r = imageClient.generate(
                prompt, refs,
                "images/zzal/pets/%d/%s.png".formatted(ctx.petId(), name),
                spec);

        return StepResult.image(name, r.imageKey(), spec.model(), r.costUsd());
    }
}
