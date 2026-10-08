package com.lore.zzal.chat.line;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.zzal.chat.prompt.ChatContext;
import com.lore.zzal.chat.prompt.LineKind;
import com.lore.zzal.chat.prompt.PromptAssembler;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * LLM 생성기 — 지시문 한 벌({@link PromptAssembler}) → OpenAI 1회 → JSON {@code {line, motion}} → {@link LineFilter}.
 *
 * <h3>실패하는 길(전부 사유를 남기고 템플릿으로 떨어진다)</h3>
 * <ul>
 *   <li>{@code cap} — 오늘 나간 돈이 일 상한 이상(호출 안 함)</li>
 *   <li>{@code timeout} — 시간 안에 못 받음(기본 4초). 비용은 추정치로 적는다</li>
 *   <li>{@code error} — HTTP 오류·네트워크</li>
 *   <li>{@code parse} — JSON 이 아니거나 line 이 없음</li>
 *   <li>{@code blank}·{@code length}·{@code questions}·{@code resent}·{@code unsafe} — 출력 검사</li>
 * </ul>
 * ★ 재시도는 없다(v1). v2 의 "걸리면 재생성 1회" 는 {@link LineChain} 에 붙는다.
 */
public class LlmLineGenerator implements LineGenerator {

    public static final String NAME = "llm";

    private final ChatLineClient client;
    private final String model;
    private final Duration timeout;
    private final BigDecimal dailyCapUsd;
    /** 오늘 지금까지 나간 돈. 서비스에서는 {@code zzal_chat_call.cost_usd} 의 합. */
    private final Supplier<BigDecimal> spentToday;
    private final ObjectMapper json = new ObjectMapper();

    public LlmLineGenerator(ChatLineClient client, String model, Duration timeout, BigDecimal dailyCapUsd,
                            Supplier<BigDecimal> spentToday) {
        this.client = client;
        this.model = model;
        this.timeout = timeout;
        this.dailyCapUsd = dailyCapUsd;
        this.spentToday = spentToday;
    }

    @Override
    public String name() {
        return NAME;
    }

    public String model() {
        return model;
    }

    @Override
    public LineAttempt generate(ChatContext ctx) {
        long t0 = System.nanoTime();
        BigDecimal spent = spentToday.get();
        if (spent != null && spent.compareTo(dailyCapUsd) >= 0) {
            return LineAttempt.fail(NAME, null, BigDecimal.ZERO, "cap", 0);
        }
        String prompt = PromptAssembler.assemble(ctx);
        ChatLineClient.Completion c;
        try {
            c = client.complete(prompt, model, timeout);
        } catch (TimeoutException e) {
            return LineAttempt.fail(NAME, model, OpenAiChatLineClient.estimate(model, prompt.length()), "timeout", ms(t0));
        } catch (ChatLineClient.BilledException e) {
            return LineAttempt.fail(NAME, model, e.costUsd(), "parse", ms(t0));
        } catch (Exception e) {
            return LineAttempt.fail(NAME, model, BigDecimal.ZERO, "error", ms(t0));
        }

        String line;
        String motion;
        try {
            JsonNode n = json.readTree(stripFence(c.text()));
            line = clean(n.path("line").asText(""));
            motion = n.path("motion").asText("").strip();
        } catch (Exception e) {
            return LineAttempt.fail(NAME, model, c.costUsd(), "parse", ms(t0));
        }
        String bad = LineFilter.check(line, ctx);
        if (bad != null) {
            // 걸린 대사는 버리지만 시험·샘플이 사유를 확인할 수 있게 담아 둔다(사슬은 쓰지 않고, 로그에도 안 남긴다).
            return new LineAttempt(line, null, NAME, model, c.costUsd(), bad, ms(t0));
        }
        // 동작은 받은 목록 안에서만. 벗어나면 기본값(대사를 버리지는 않는다).
        String picked = ctx.kind() == LineKind.CALL ? null
                : (ctx.motions() != null && ctx.motions().contains(motion) ? motion : ctx.defaultMotion());
        return new LineAttempt(line, picked, NAME, model, c.costUsd(), null, ms(t0));
    }

    /** 모델이 대사를 따옴표로 감싸 오는 경우가 있다 — 바깥 따옴표만 벗긴다. */
    static String clean(String raw) {
        String t = raw == null ? "" : raw.strip();
        if (t.length() >= 2 && (t.startsWith("\"") && t.endsWith("\"") || t.startsWith("“") && t.endsWith("”"))) {
            t = t.substring(1, t.length() - 1).strip();
        }
        return t;
    }

    /** ```json … ``` 으로 감싸 오는 경우. */
    static String stripFence(String text) {
        String t = text.strip();
        if (t.startsWith("```")) {
            int a = t.indexOf('\n');
            int b = t.lastIndexOf("```");
            if (a > 0 && b > a) {
                return t.substring(a + 1, b).strip();
            }
        }
        return t;
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
