package com.lore.zzal.generation.steps;

import com.lore.zzal.generation.GenerationStep;
import com.lore.zzal.generation.PromptLoader;
import com.lore.zzal.generation.StepContext;
import com.lore.zzal.generation.StepResult;
import com.lore.zzal.generation.client.ModelSpec;
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
        // ★★ 자유 메모(note)는 여기 안 들어간다 — 2026-09-11 상훈님 결정.
        //   원문: "자유 메모는 움짤보다는 나중에 채팅 기능 넣을 때 퀄리티를 높이기 위한 방법이었어.
        //   메모는 일단 백엔드에 저장이 될 거 아냐. 나중에 쓰는 쪽으로 하자 채팅 때"
        //
        //   ★ 빠뜨린 것이 아니라 <b>뺀 것</b>이다. 메모는 zzal_pet.note 에 그대로 저장되고,
        //     채팅(특히 LLM 을 붙일 때) 재료로 쓴다. 되돌리지 말 것.
        //   ★ 그림이 이름을 기다릴 이유가 사라진 것도 이 변경 때문이다 — 메모가 마지막 남은
        //     "캐릭터 정보에서 그림으로 가는 줄" 이었다. 지금은 그림 등록 즉시 끝까지 굽는다.
        String prompt = prompts.prompt(ctx.version(), NAME);

        ModelSpec spec = prompts.model(ctx.version(), NAME);
        TextClient.Result r = textClient.generate(prompt, List.of(ctx.image(SheetStep.NAME)), spec);

        return StepResult.text(NAME, r.text(), spec.model(), r.costUsd());
    }
}
