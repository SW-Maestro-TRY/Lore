package com.lore.zzal.chat.line;

import com.lore.zzal.chat.ZzalChatCallRepository;
import com.lore.zzal.pet.ZzalRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 채팅 대사 사슬 조립 — 스위치 {@code app.zzal.chat.llm} 이 어느 생성기를 쓸지 정한다.
 *
 * <h3>설정</h3>
 * <ul>
 *   <li>{@code app.zzal.chat.llm} — 기본 false(템플릿만). dev 에서만 켠다</li>
 *   <li>{@code app.zzal.chat.model} — 기본 gpt-5-mini</li>
 *   <li>{@code app.zzal.chat.timeout-ms} — 기본 4000. 넘으면 템플릿</li>
 *   <li>{@code app.zzal.chat.daily-cost-usd} — 기본 2. 그날(한국 날짜) 채팅 LLM 비용 합이 넘으면 템플릿</li>
 *   <li>{@code app.zzal.chat.reasoning-effort} — 기본 minimal(gpt-5 계열만 보낸다)</li>
 * </ul>
 * ★ 켰는데 키가 없으면 기동을 막는다 — 조용히 템플릿으로 돌면 "켰는데 안 켜진" 상태가 실제 대화에서야 드러난다.
 */
@Configuration
public class ChatLineConfig {

    private static final Logger log = LoggerFactory.getLogger(ChatLineConfig.class);

    @Bean
    public LineChain lineChain(@Value("${app.zzal.chat.llm:false}") boolean llm,
                               @Value("${app.zzal.chat.model:gpt-5-mini}") String model,
                               @Value("${app.zzal.chat.timeout-ms:4000}") long timeoutMs,
                               @Value("${app.zzal.chat.daily-cost-usd:2}") BigDecimal dailyCap,
                               @Value("${app.zzal.chat.reasoning-effort:minimal}") String effort,
                               @Value("${app.zzal.openai.api-key:}") String apiKey,
                               ZzalChatCallRepository calls,
                               TemplateLineGenerator template,
                               ChatLineEvents events) {
        if (!llm) {
            return new LineChain(null, template, events);
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "채팅 LLM 을 켰는데(app.zzal.chat.llm=true) API 키가 없습니다. ZZAL_OPENAI_API_KEY 를 설정하세요.");
        }
        log.info("채팅 대사 LLM 켜짐 — {} · {}ms · 일 상한 ${}", model, timeoutMs, dailyCap);
        LlmLineGenerator gen = new LlmLineGenerator(new OpenAiChatLineClient(apiKey, effort), model,
                Duration.ofMillis(timeoutMs), dailyCap,
                () -> calls.sumCostSince(startOfToday()));
        return new LineChain(gen, template, events);
    }

    /** 오늘(한국 날짜) 0시. 시험이 같은 셈을 쓰게 밖에 둔다. */
    static Instant startOfToday() {
        return LocalDate.now(ZzalRules.ZONE).atStartOfDay(ZzalRules.ZONE).toInstant();
    }
}
