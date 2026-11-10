package com.lore.zzal.chat.line;

import com.lore.zzal.chat.prompt.SystemPromptCache;

import java.time.Duration;

/** 시험용 사슬 — 목 LLM 으로 도는 켜진 사슬. 돈이 안 나간다. */
public final class ChatChains {

    private ChatChains() {
    }

    public static LineChain fake(FakeChatLineClient client) {
        return fake(client, new SystemPromptCache());
    }

    public static LineChain fake(FakeChatLineClient client, SystemPromptCache systems) {
        return new LineChain(new LlmLineGenerator(client, "gpt-5-mini", Duration.ofSeconds(4), systems), null);
    }
}
