package com.lore.zzal.chat.line;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.zzal.chat.prompt.ChatContext;
import com.lore.zzal.chat.prompt.PromptAssembler;
import com.lore.zzal.chat.prompt.SystemPromptCache;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * LLM 생성기 — 시스템 메시지(펫당 캐시) + 사용자 메시지(이번 턴)({@link PromptAssembler}) → OpenAI 1회
 * → JSON {@code {line, motion, call_me, user_said, asked_back}}.
 *
 * <h3>실패하는 길(사유를 남기고 {@link LineChain} 이 한 번 더 부른다)</h3>
 * <ul>
 *   <li>{@code timeout} — 시간 안에 못 받음(기본 4초). 비용은 추정치로 적는다</li>
 *   <li>{@code error} — HTTP 오류·네트워크</li>
 *   <li>{@code parse} — JSON 이 아니거나 응답에 글이 없음</li>
 *   <li>{@code blank} — line 이 비었음</li>
 * </ul>
 *
 * <h3>★ 내용은 거르지 않는다(#709)</h3>
 * 괄호·이모지·질문 수·금칙어 같은 코드단 내용 검사는 지웠다 — 정상 대사를 버리고 고정 문형으로 갈아 끼우는 쪽이
 * 더 나빴다(상훈 결정 10/10 "코드단에서 뭔가를 필터링하는 건 무의미"). 금기는 지시문 [말하는 법] 이 맡는다.
 * 길이(160자 DB 칸)는 거르지 않고 {@link LineChain} 이 잘라 저장한다.
 */
public class LlmLineGenerator implements LineGenerator {

    public static final String NAME = "llm";

    /** 추출 칸 길이 — 호칭은 펫 칸(20자), 요지는 턴 칸(160자)에 맞춘다. */
    static final int CALL_ME_MAX = 20;
    static final int USER_SAID_MAX = 160;

    private final ChatLineClient client;
    private final String model;
    private final Duration timeout;
    private final SystemPromptCache systems;
    private final ObjectMapper json = new ObjectMapper();

    public LlmLineGenerator(ChatLineClient client, String model, Duration timeout, SystemPromptCache systems) {
        this.client = client;
        this.systems = systems;
        this.model = model;
        this.timeout = timeout;
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
        String system = systems.get(ctx.petId(), ctx.sheet());
        String user = PromptAssembler.user(ctx);
        ChatLineClient.Completion c;
        try {
            c = client.complete(system, user, model, timeout);
        } catch (TimeoutException e) {
            return LineAttempt.fail(NAME, model,
                    OpenAiChatLineClient.estimate(model, system.length() + user.length()), "timeout", ms(t0));
        } catch (ChatLineClient.BilledException e) {
            return LineAttempt.fail(NAME, model, e.costUsd(), "parse", ms(t0));
        } catch (Exception e) {
            return LineAttempt.fail(NAME, model, BigDecimal.ZERO, "error", ms(t0));
        }

        String line;
        String motion;
        LineExtract extract;
        try {
            JsonNode n = json.readTree(stripFence(c.text()));
            if (n == null || !n.isObject()) {
                return LineAttempt.fail(NAME, model, c.costUsd(), "parse", ms(t0));
            }
            line = clean(n.path("line").asText(""));
            motion = n.path("motion").asText("").strip();
            extract = new LineExtract(field(n, "call_me", CALL_ME_MAX), field(n, "user_said", USER_SAID_MAX),
                    n.path("asked_back").asBoolean(false));
        } catch (Exception e) {
            return LineAttempt.fail(NAME, model, c.costUsd(), "parse", ms(t0));
        }
        if (line.isBlank()) {
            return LineAttempt.fail(NAME, model, c.costUsd(), "blank", ms(t0));
        }
        // 동작은 받은 목록 안에서만. 벗어나면 기본값(대사를 버리지는 않는다).
        // 판의 첫 턴(부름)에는 반응 동작이 없다.
        String picked = ctx.plan().petTurnNo() == 1 ? null
                : (ctx.motions() != null && ctx.motions().contains(motion) ? motion : ctx.defaultMotion());
        return new LineAttempt(line, picked, NAME, model, c.costUsd(), null, ms(t0), extract);
    }

    /** 추출 칸 하나 — 없음·JSON null·빈 글·글자 "null" 은 null. 칸 길이에 맞춰 자른다. */
    static String field(JsonNode n, String key, int max) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull() || v.isContainerNode()) {
            return null;
        }
        String t = clean(v.asText(""));
        if (t.isEmpty() || t.equalsIgnoreCase("null") || t.equalsIgnoreCase("none")) {
            return null;
        }
        return t.length() > max ? t.substring(0, max) : t;
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
