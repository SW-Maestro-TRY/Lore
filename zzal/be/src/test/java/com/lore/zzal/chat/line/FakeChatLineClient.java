package com.lore.zzal.chat.line;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** 시험용 — 준비해 둔 응답(또는 예외)을 차례로 돌려주고, 받은 지시문을 남긴다. */
public class FakeChatLineClient implements ChatLineClient {

    public final List<String> prompts = new ArrayList<>();
    private final Deque<Object> answers = new ArrayDeque<>();
    public static final BigDecimal COST = new BigDecimal("0.000800");

    public FakeChatLineClient reply(String json) {
        answers.add(json);
        return this;
    }

    public FakeChatLineClient fail(Exception e) {
        answers.add(e);
        return this;
    }

    @Override
    public Completion complete(String prompt, String model, Duration timeout) throws Exception {
        prompts.add(prompt);
        Object a = answers.isEmpty() ? "{\"line\":\"응, 반가워.\",\"motion\":\"\"}" : answers.poll();
        if (a instanceof Exception e) {
            throw e;
        }
        return new Completion((String) a, COST, 1500, 80);
    }
}
