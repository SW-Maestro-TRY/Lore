package com.lore.zzal.generation.steps;

import com.lore.zzal.generation.GenerationStep;
import com.lore.zzal.generation.PromptLoader;
import com.lore.zzal.generation.StepContext;
import com.lore.zzal.generation.StepResult;
import com.lore.zzal.generation.client.TextClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 2단계 — 시트를 보고 생김새를 글로 받아 적는다.
 *
 * ★ 왜 글을 한 번 거치나 — 그림만 주고 "이대로 8가지 표정을 그려줘" 하면 캐릭터가 조금씩
 *   달라진다. 글로 못박아 두면 생성이 안정된다(2026-08-26 실측에서 확인).
 *
 * ⚠️ 이 단계가 실패의 원인이 된 적이 있다 — 고양이 시트를 보고 엉뚱한 캐릭터를 묘사하는
 *    문단이 나왔고, 그 문단 때문에 다음 단계가 차단됐다. 그래서 격자가 '거부' 로 실패하면
 *    이 단계부터 다시 한다.
 *
 * ★ 이 단계는 없어질 수도 있다(상훈님 2026-09-02). 그때는 파이프라인 목록에서 빼면 되고,
 *   이 클래스는 남겨 v1 로 만들어진 펫들을 계속 설명한다.
 *
 * 실측 15~22초 · $0.018
 */
@Component
public class IdentityStep implements GenerationStep {

    public static final String NAME = "identity";

    private final TextClient textClient;
    private final PromptLoader prompts;

    public IdentityStep(TextClient textClient, PromptLoader prompts) {
        this.textClient = textClient;
        this.prompts = prompts;
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
        return "생김새를 정리하는 중";
    }

    @Override
    public StepResult run(StepContext ctx) throws Exception {
        String prompt = prompts.prompt(ctx.version(), NAME);
        if (ctx.note() != null && !ctx.note().isBlank()) {
            // 사용자가 직접 쓴 세부사항을 덧붙인다. 남이 정한 설정이 아니라 본인 말이라
            // 자캐 커뮤니티의 '캐조종' 문제가 성립하지 않는다.
            prompt = prompt + "\n\n[주인이 알려준 것]\n" + ctx.note();
        }

        TextClient.Result r = textClient.generate(
                prompt, List.of(ctx.image(SheetStep.NAME)), prompts.model(ctx.version(), NAME));

        return StepResult.text(NAME, r.text(), r.costUsd());
    }
}
