package com.lore.zzal.chat.line;

import com.lore.zzal.chat.BanFilter;
import com.lore.zzal.chat.prompt.ChatContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 템플릿 생성기 — 턴 종류별 고정 문형({@link FallbackLines}). 실패하지 않는다. LLM 이 실패하면 여기로 떨어진다.
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
        String line = FallbackLines.line(ctx.sheet(), ctx.plan().type());
        // 판의 첫 턴(부름)에는 반응 동작이 없다 — 사용자가 아직 아무것도 안 했다.
        String motion = ctx.plan().petTurnNo() == 1 ? null : ctx.defaultMotion();
        return new LineAttempt(BanFilter.clean(line), motion, NAME, null, BigDecimal.ZERO, null, 0);
    }
}
