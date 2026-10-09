package com.lore.zzal.chat.line;

import com.lore.zzal.chat.prompt.SystemPromptCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 채팅 대사 사슬 조립 — 스위치 {@code app.zzal.chat.llm} 이 채팅을 켤지 정한다.
 *
 * <h3>설정</h3>
 * <ul>
 *   <li>{@code app.zzal.chat.llm} — <b>기본 true</b>(#709 — 운영은 기존 OpenAI 키 {@code ZZAL_OPENAI_API_KEY} 를 그대로 쓴다,
 *       새 파라미터 없음). 끄는 용도로만 남긴다. <b>꺼져 있으면 채팅 자체가 "부름 없음"</b>(판을 만들지 않는다).
 *       성격별 고정 문형(템플릿)은 지웠다</li>
 *   <li>{@code app.zzal.chat.model} — 기본 gpt-5-mini</li>
 *   <li>{@code app.zzal.chat.timeout-ms} — 기본 4000. 넘으면 한 번 더, 또 넘으면 중립 닫는 말</li>
 *   <li>{@code app.zzal.chat.reasoning-effort} — 기본 minimal(gpt-5 계열만 보낸다)</li>
 * </ul>
 * ★ 일 비용 상한({@code daily-cost-usd})은 #709 에서 지웠다 — 판당 5왕복·하루 3판이라 인당 호출 수가 이미 묶여 있다.
 *   비용 기록({@code zzal_chat_session.cost_usd})은 그대로 남는다.
 * ★ 켰는데 키가 없으면 기동을 막는다 — 조용히 꺼진 채로 돌면 "켰는데 안 켜진" 상태가 실제 대화에서야 드러난다.
 */
@Configuration
public class ChatLineConfig {

    private static final Logger log = LoggerFactory.getLogger(ChatLineConfig.class);

    @Bean
    public LineChain lineChain(@Value("${app.zzal.chat.llm:true}") boolean llm,
                               @Value("${app.zzal.chat.model:gpt-5-mini}") String model,
                               @Value("${app.zzal.chat.timeout-ms:4000}") long timeoutMs,
                               @Value("${app.zzal.chat.reasoning-effort:minimal}") String effort,
                               @Value("${app.zzal.openai.api-key:}") String apiKey,
                               SystemPromptCache systems,
                               ChatLineEvents events) {
        if (!llm) {
            log.info("채팅 대사 LLM 꺼짐 — 채팅 부름을 만들지 않습니다(app.zzal.chat.llm=false)");
            return new LineChain(null, events);
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "채팅 LLM 을 켰는데(app.zzal.chat.llm=true) API 키가 없습니다. ZZAL_OPENAI_API_KEY 를 설정하세요.");
        }
        log.info("채팅 대사 LLM 켜짐 — {} · {}ms", model, timeoutMs);
        LlmLineGenerator gen = new LlmLineGenerator(new OpenAiChatLineClient(apiKey, effort), model,
                Duration.ofMillis(timeoutMs), systems);
        return new LineChain(gen, events);
    }
}
