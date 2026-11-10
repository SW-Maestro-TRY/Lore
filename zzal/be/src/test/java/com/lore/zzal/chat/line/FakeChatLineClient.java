package com.lore.zzal.chat.line;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** 시험용 — 준비해 둔 응답(또는 예외)을 차례로 돌려주고, 받은 시스템·사용자 메시지를 남긴다. 돈이 안 나간다. */
public class FakeChatLineClient implements ChatLineClient {

    public final List<String> systems = java.util.Collections.synchronizedList(new ArrayList<>());
    public final List<String> users = java.util.Collections.synchronizedList(new ArrayList<>());
    private final Deque<Object> answers = new ArrayDeque<>();
    public static final BigDecimal COST = new BigDecimal("0.000300");

    private String fallback = "{\"line\":\"응, 반가워.\",\"motion\":\"hello\"}";

    /** 준비한 응답이 다 떨어졌을 때 돌려줄 것. */
    public FakeChatLineClient whenEmpty(String json) {
        this.fallback = json;
        return this;
    }

    public FakeChatLineClient reply(String json) {
        answers.add(json);
        return this;
    }

    /** {"line": …, "motion": …} 한 줄. */
    public FakeChatLineClient line(String line, String motion) {
        return reply("{\"line\":\"" + line.replace("\"", "\\\"") + "\",\"motion\":\"" + motion + "\"}");
    }

    /** 추출 칸까지 — {"line", "motion", "call_me", "user_said", "asked_back"}. null 은 JSON null. */
    public FakeChatLineClient full(String line, String motion, String callMe, String userSaid, boolean askedBack) {
        return reply("{\"line\":" + q(line) + ",\"motion\":" + q(motion) + ",\"call_me\":" + q(callMe)
                + ",\"user_said\":" + q(userSaid) + ",\"asked_back\":" + askedBack + "}");
    }

    private static String q(String v) {
        return v == null ? "null" : "\"" + v.replace("\"", "\\\"") + "\"";
    }

    public FakeChatLineClient fail(Exception e) {
        answers.add(e);
        return this;
    }

    @Override
    public synchronized Completion complete(String system, String user, String model, Duration timeout) throws Exception {
        systems.add(system);
        users.add(user);
        Object a = answers.isEmpty() ? fallback : answers.poll();
        if (a instanceof Exception e) {
            throw e;
        }
        return new Completion((String) a, COST, 1500, 80);
    }
}
