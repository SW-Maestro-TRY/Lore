package com.lore.zzal.chat.line;

import com.lore.zzal.chat.prompt.ChatContext;
import com.lore.zzal.chat.session.ZzalChatTurn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * 대사 한 줄 — LLM 을 부르고, 실패하면 <b>한 번 더</b>, 또 실패하면 <b>중립 닫는 말</b>로 판을 닫는다(#709).
 *
 * <h3>실패 경로 둘뿐</h3>
 * <ol>
 *   <li>빈 줄·JSON 깨짐·시간 초과·오류 → 재호출 1회 → 또 실패면 {@link #CLOSING_LINE}(성격 없음) + 판 닫기
 *       ({@link GeneratedLine#closesSession()})</li>
 *   <li>DB 칸(160자) 초과 → 잘라서 저장({@link LineOutcome#TRUNCATED})</li>
 * </ol>
 * 내용 검사(괄호·이모지·질문 수·금칙어)와 성격별 고정 문형(템플릿)은 지웠다 — 상훈 결정 10/10.
 *
 * <h3>LLM 이 꺼져 있으면</h3>
 * 대사를 낼 길이 없다. 부르는 쪽({@code ChatService})이 {@link #llmEnabled()} 를 보고 <b>판을 만들지 않는다</b>
 * ("부름 없음"). 여기를 꺼진 채로 부르면 예외다 — 조용히 아무 말이나 내지 않는다.
 *
 * <h3>★ 남기는 것</h3>
 * 결과({@link LineOutcome})·걸린 시간·첫 실패 사유를 {@link GeneratedLine} 에 담아 턴 행에 적고(DB),
 * 로그 한 줄과 분석 이벤트 {@code zzal_chat_llm} 을 남긴다 — 실패가 얼마나 나는지 지표로 보려고.
 */
public class LineChain {

    private static final Logger log = LoggerFactory.getLogger(LineChain.class);

    /** 두 번 다 실패했을 때 나가는 말 — 성격 없음, 아무도 탓하지 않고 판을 닫는다. */
    public static final String CLOSING_LINE = "잠깐 딴생각했어. 이따 또 말 걸게.";

    /** 중립 닫는 말을 낸 곳의 이름(턴·판의 generator 칸). */
    public static final String FIXED = "fixed";

    private final LineGenerator llm;   // null 이면 꺼짐
    private final ChatLineEvents events;

    public LineChain(LineGenerator llm, ChatLineEvents events) {
        this.llm = llm;
        this.events = events;
    }

    /** 꺼진 사슬 — 채팅은 "부름 없음". */
    public static LineChain off() {
        return new LineChain(null, null);
    }

    public boolean llmEnabled() {
        return llm != null;
    }

    public GeneratedLine generate(ChatContext ctx, Long userId) {
        if (llm == null) {
            throw new IllegalStateException("채팅 LLM 이 꺼져 있습니다(app.zzal.chat.llm=false) — 판을 만들지 않아야 합니다");
        }
        LineAttempt a = llm.generate(ctx);
        long ms = a.millis();
        BigDecimal cost = nz(a.costUsd());
        String firstFail = a.failReason();
        int attempts = 1;
        if (!a.ok()) {
            LineAttempt b = llm.generate(ctx);
            attempts = 2;
            ms += b.millis();
            cost = cost.add(nz(b.costUsd()));
            if (!b.ok()) {
                String motion = ctx.plan().petTurnNo() == 1 ? null : ctx.defaultMotion();
                GeneratedLine out = new GeneratedLine(CLOSING_LINE, motion, FIXED, b.model(), cost,
                        firstFail + "/" + b.failReason(), LineOutcome.FAILED_CLOSED, ms, attempts, LineExtract.NONE);
                done(ctx, out, userId);
                return out;
            }
            a = b;
        }
        String text = a.text();
        LineOutcome outcome = attempts > 1 ? LineOutcome.RETRIED_OK : LineOutcome.OK;
        if (text.codePointCount(0, text.length()) > ZzalChatTurn.LINE_MAX) {
            text = ZzalChatTurn.fit(text);
            outcome = LineOutcome.TRUNCATED;
        }
        GeneratedLine out = new GeneratedLine(text, a.motion(), a.generator(), a.model(), cost, firstFail, outcome, ms,
                attempts, a.extract() == null ? LineExtract.NONE : a.extract());
        done(ctx, out, userId);
        return out;
    }

    private void done(ChatContext ctx, GeneratedLine out, Long userId) {
        log.info("채팅 대사 — {} {}번째 · {} · {} · {}ms · {}회 · ${}{}", ctx.plan().type(), ctx.plan().petTurnNo(),
                out.outcome().code(), out.model() == null ? "-" : out.model(), out.latencyMs(), out.attempts(),
                out.costUsd(), out.failReason() == null ? "" : " · 실패 " + out.failReason());
        if (events != null) {
            events.record(ctx.plan().type().name().toLowerCase(Locale.ROOT), ctx.plan().petTurnNo(),
                    ctx.kind().name().toLowerCase(Locale.ROOT), out.generator(), out.outcome().code(), out.latencyMs(),
                    out.attempts(), userId);
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
