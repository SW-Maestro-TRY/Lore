package com.lore.zzal.chat.line;

import com.lore.zzal.chat.prompt.ChatContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

/**
 * 생성기 고르기 + 폴백 사슬. 채팅은 대사가 필요할 때 여기만 부른다.
 *
 * <h3>사슬</h3>
 * LLM 이 켜져 있으면({@code app.zzal.chat.llm=true}) LLM → 실패하면 템플릿. 꺼져 있으면 템플릿만.
 * 템플릿은 실패하지 않으므로 대사는 반드시 나온다.
 *
 * <h3>★ 남기는 것</h3>
 * 어느 생성기가 냈는지·폴백 사유·비용을 {@link GeneratedLine} 에 담아 부름 행에 적고(DB),
 * 로그 한 줄과 분석 이벤트 {@code zzal_chat_llm} 을 남긴다 — v2 로 갈 때 템플릿·v1·v2 를 같은 잣대로 비교하려고.
 * LLM 이 꺼져 있어도 이벤트를 남긴다(type=template, reason=ok) — 켜기 전후 비교의 바탕선이다.
 *
 * <h3>★ v2 에서 붙는 자리</h3>
 * "필터에 걸리면 재생성 1회" 는 {@link #generate} 안, LLM 실패 직후에 한 번 더 부르는 것으로 붙는다.
 */
public class LineChain {

    private static final Logger log = LoggerFactory.getLogger(LineChain.class);

    private final LineGenerator primary;   // null 이면 템플릿만
    private final TemplateLineGenerator template;
    private final ChatLineEvents events;

    public LineChain(LineGenerator primary, TemplateLineGenerator template, ChatLineEvents events) {
        this.primary = primary;
        this.template = template;
        this.events = events;
    }

    /** 템플릿만 쓰는 사슬(LLM 꺼짐·시험용). */
    public static LineChain templateOnly() {
        return new LineChain(null, new TemplateLineGenerator(), null);
    }

    public boolean llmEnabled() {
        return primary != null;
    }

    public GeneratedLine generate(ChatContext ctx, Long userId) {
        if (primary == null) {
            LineAttempt t = template.generate(ctx);
            GeneratedLine out = new GeneratedLine(t.text(), t.motion(), t.generator(), null, BigDecimal.ZERO, null);
            // ★ LLM 이 꺼져 있어도 남긴다(type=template, reason=ok) — 켜기 전후를 같은 잣대로 비교하려고.
            record(ctx, out, 0, userId);
            return out;
        }
        LineAttempt a = primary.generate(ctx);
        GeneratedLine out;
        if (a.ok()) {
            out = new GeneratedLine(a.text(), a.motion(), a.generator(), a.model(), a.costUsd(), null);
        } else {
            LineAttempt t = template.generate(ctx);
            out = new GeneratedLine(t.text(), t.motion(), t.generator(), a.model(), a.costUsd(), a.failReason());
        }
        log.info("채팅 대사 — {} {}번째 · {} · {} · {}ms · ${}{}", ctx.plan().type(), ctx.plan().petTurnNo(), out.generator(),
                a.model() == null ? "-" : a.model(), a.millis(), a.costUsd(),
                out.fallbackReason() == null ? "" : " · 폴백 " + out.fallbackReason());
        record(ctx, out, a.millis(), userId);
        return out;
    }

    /**
     * 이벤트 {@code zzal_chat_llm} — 프론트 {@code zzal_chat_turn} 과 키 뜻이 같다:
     * action = 턴 종류(first_meet·greeting·reunion·continue·close), step = 판 안의 펫 턴 번호,
     * code = 판 종류(baby·first_meet·daily·long_absence), type = 생성기, reason = 폴백 사유(없으면 ok).
     */
    private void record(ChatContext ctx, GeneratedLine out, long millis, Long userId) {
        if (events != null) {
            events.record(ctx.plan().type().name().toLowerCase(java.util.Locale.ROOT), ctx.plan().petTurnNo(),
                    ctx.kind().name().toLowerCase(java.util.Locale.ROOT), out.generator(), out.fallbackReason(),
                    millis, userId);
        }
    }
}
