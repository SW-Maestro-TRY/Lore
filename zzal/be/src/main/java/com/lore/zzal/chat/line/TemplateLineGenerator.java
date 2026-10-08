package com.lore.zzal.chat.line;

import com.lore.zzal.chat.BanFilter;
import com.lore.zzal.chat.ChatTemplates;
import com.lore.zzal.chat.memory.Memory;
import com.lore.zzal.chat.prompt.ChatContext;
import com.lore.zzal.chat.prompt.LineKind;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 템플릿 생성기 — 채팅 v0 그대로({@link ChatTemplates}). 실패하지 않는다. LLM 이 실패하면 여기로 떨어진다.
 *
 * ★ 문장 고르는 규칙은 옛 코드와 같다: 답은 답 글자의 해시로 3벌 중 하나, 재언급은 기억 맨 앞 1개.
 *   원망 필터도 예전처럼 {@link BanFilter#clean} 으로 건다.
 */
@Component
public class TemplateLineGenerator implements LineGenerator {

    public static final String NAME = "template";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public LineAttempt generate(ChatContext ctx) {
        String line = switch (ctx.kind()) {
            case CALL -> ChatTemplates.call(ctx.sheet().lead(), ctx.slot(), ctx.sheet().name());
            case REPLY, RECALL -> ChatTemplates.reply(ctx.sheet().lead(), ctx.answer(), memories(ctx), ctx.answerCount());
        };
        String motion = ctx.kind() == LineKind.CALL ? null : ctx.defaultMotion();
        return new LineAttempt(BanFilter.clean(line), motion, NAME, null, BigDecimal.ZERO, null, 0);
    }

    private static List<String> memories(ChatContext ctx) {
        return ctx.memories() == null ? List.of() : ctx.memories().stream().map(Memory::text).toList();
    }
}
