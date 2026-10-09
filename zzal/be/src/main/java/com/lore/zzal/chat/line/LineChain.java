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
 * <h3>★ 튜토리얼 부름(BABY) 첫 턴은 다르다 — {@link #firstBaby}</h3>
 * BABY 는 온보딩 필수 단계(튜토리얼 대화 칸)라 "두 번 실패 → 닫음" 이면 그 사용자는 튜토리얼에서 영구 정지한다.
 * 그래서 BABY 첫 턴만 닫지 않는다 — 두 번 실패면 {@link LineOutcome#RETRY_WAIT}(판은 재시도 대기, 쿨다운 뒤 1회 더),
 * 누적 실패 {@link #BABY_NEUTRAL_AFTER}회면 {@link #BABY_NEUTRAL_LINE} 로 판을 연다({@link LineOutcome#BABY_NEUTRAL}).
 * 그 다음 턴부터는 평소와 같다({@link #generate} — 실패하면 닫는 말).
 *
 * <h3>LLM 이 꺼져 있으면</h3>
 * 대사를 낼 길이 없다. 하루 부름은 부르는 쪽({@code ChatService})이 {@link #llmEnabled()} 를 보고 <b>판을 만들지 않는다</b>
 * ("부름 없음"). BABY 만은 {@link #babyNeutral} 로 판을 연다. 꺼진 채로 {@link #generate} 가 불리면(BABY 중립 판에 답했을 때)
 * 모델을 부르지 않고 바로 중립 닫는 말로 판을 닫는다(사유 {@code llm_off}).
 *
 * <h3>★ 남기는 것</h3>
 * 결과({@link LineOutcome})·걸린 시간·첫 실패 사유를 {@link GeneratedLine} 에 담아 턴 행에 적고(DB),
 * 로그 한 줄과 분석 이벤트 {@code zzal_chat_llm} 을 남긴다 — 실패가 얼마나 나는지 지표로 보려고.
 */
public class LineChain {

    private static final Logger log = LoggerFactory.getLogger(LineChain.class);

    /** 두 번 다 실패했을 때 나가는 말 — 성격 없음, 아무도 탓하지 않고 판을 닫는다. */
    public static final String CLOSING_LINE = "잠깐 딴생각했어. 이따 또 말 걸게.";

    /**
     * 튜토리얼 부름(BABY) 첫 턴의 중립 한 줄 — 성격 없음, 호칭을 묻는다(질문 항목 CALL_ME).
     *
     * ★★ 성격별 고정 문형(템플릿) 전면 제거 정책(상훈 결정 10/10)의 <b>유일한 예외</b>다. BABY 는 온보딩 필수 단계라
     *    LLM 이 끝내 못 내면(누적 실패 {@link #BABY_NEUTRAL_AFTER}회·LLM 꺼짐) 이 한 줄로라도 판을 열어야 튜토리얼이
     *    진행된다. 다른 고정 문형을 더하지 말 것 — 이 줄 말고는 {@link #CLOSING_LINE}(닫는 말)뿐이다.
     */
    public static final String BABY_NEUTRAL_LINE = "안녕. 나 여기 처음이야. 뭐라고 부르면 좋을까?";

    /** BABY 첫 턴 — 모델 호출 실패가 누적 이만큼이면 {@link #BABY_NEUTRAL_LINE} 로 연다(첫 판 2회 + 쿨다운 뒤 1회). */
    public static final int BABY_NEUTRAL_AFTER = 3;

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
            // BABY 중립 판에 답했는데 LLM 이 꺼져 있다 — 모델 없이 닫는 말로 닫는다(보상·튜토리얼 넘김은 이미 났다).
            String motion = ctx.plan().petTurnNo() == 1 ? null : ctx.defaultMotion();
            GeneratedLine out = new GeneratedLine(CLOSING_LINE, motion, FIXED, null, BigDecimal.ZERO, "llm_off",
                    LineOutcome.FAILED_CLOSED, 0, 0, LineExtract.NONE);
            done(ctx, out, userId);
            return out;
        }
        Tries t = tries(ctx, 2);
        if (t.last() == null) {
            String motion = ctx.plan().petTurnNo() == 1 ? null : ctx.defaultMotion();
            GeneratedLine out = new GeneratedLine(CLOSING_LINE, motion, FIXED, t.model(), t.cost(), t.fails(),
                    LineOutcome.FAILED_CLOSED, t.ms(), t.calls(), LineExtract.NONE);
            done(ctx, out, userId);
            return out;
        }
        return success(ctx, t, userId);
    }

    /**
     * 튜토리얼 부름(BABY)의 <b>첫 턴</b> — 판을 닫지 않는다.
     *
     * <ul>
     *   <li>처음({@code failedSoFar}=0): 평소처럼 2회까지 부른다. 둘 다 실패 → {@link LineOutcome#RETRY_WAIT}(대사 없음)</li>
     *   <li>재시도(쿨다운 뒤): <b>1회만</b> 부른다 — 누적 실패가 {@link #BABY_NEUTRAL_AFTER} 에 닿으면
     *       {@link #BABY_NEUTRAL_LINE}({@link LineOutcome#BABY_NEUTRAL})</li>
     * </ul>
     * 판 하나가 첫 턴에 쓰는 모델 호출은 최대 {@link #BABY_NEUTRAL_AFTER}번이다.
     *
     * @param failedSoFar 이 판의 첫 턴에서 이미 실패한 호출 수
     */
    public GeneratedLine firstBaby(ChatContext ctx, Long userId, int failedSoFar) {
        if (llm == null) {
            return babyNeutral(ctx, userId, "llm_off", 0, BigDecimal.ZERO, 0, null);
        }
        int budget = Math.max(1, Math.min(2, BABY_NEUTRAL_AFTER - failedSoFar - 1));
        Tries t = tries(ctx, budget);
        if (t.last() != null) {
            return success(ctx, t, userId);
        }
        if (failedSoFar + t.calls() >= BABY_NEUTRAL_AFTER) {
            return babyNeutral(ctx, userId, t.fails(), t.ms(), t.cost(), t.calls(), t.model());
        }
        GeneratedLine out = new GeneratedLine(null, null, null, t.model(), t.cost(), t.fails(), LineOutcome.RETRY_WAIT,
                t.ms(), t.calls(), LineExtract.NONE);
        done(ctx, out, userId);
        return out;
    }

    /** BABY 중립 한 줄({@link #BABY_NEUTRAL_LINE}) — 누적 실패 3회째이거나 LLM 이 꺼져 있을 때. */
    public GeneratedLine babyNeutral(ChatContext ctx, Long userId, String reason) {
        return babyNeutral(ctx, userId, reason, 0, BigDecimal.ZERO, 0, null);
    }

    private GeneratedLine babyNeutral(ChatContext ctx, Long userId, String reason, long ms, BigDecimal cost, int calls,
                                      String model) {
        GeneratedLine out = new GeneratedLine(BABY_NEUTRAL_LINE, null, FIXED, model, cost, reason,
                LineOutcome.BABY_NEUTRAL, ms, calls, LineExtract.NONE);
        done(ctx, out, userId);
        return out;
    }

    /** 최대 {@code max}번 부른 결과 — 성공한 시도({@code last}, 없으면 null)·걸린 시간·돈·실패 사유("첫/둘째"). */
    private record Tries(LineAttempt last, int calls, long ms, BigDecimal cost, String fails, String model) {
    }

    private Tries tries(ChatContext ctx, int max) {
        long ms = 0;
        BigDecimal cost = BigDecimal.ZERO;
        String fails = null;
        String model = null;
        for (int i = 1; i <= max; i++) {
            LineAttempt a = llm.generate(ctx);
            ms += a.millis();
            cost = cost.add(nz(a.costUsd()));
            model = a.model();
            if (a.ok()) {
                return new Tries(a, i, ms, cost, fails, model);
            }
            fails = fails == null ? a.failReason() : fails + "/" + a.failReason();
        }
        return new Tries(null, max, ms, cost, fails, model);
    }

    private GeneratedLine success(ChatContext ctx, Tries t, Long userId) {
        LineAttempt a = t.last();
        int attempts = t.calls();
        long ms = t.ms();
        BigDecimal cost = t.cost();
        String firstFail = t.fails();
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
                    ctx.kind().name().toLowerCase(Locale.ROOT), out.generator() == null ? "none" : out.generator(), out.outcome().code(), out.latencyMs(),
                    out.attempts(), userId);
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
