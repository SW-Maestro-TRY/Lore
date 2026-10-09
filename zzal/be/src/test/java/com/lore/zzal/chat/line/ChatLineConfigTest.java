package com.lore.zzal.chat.line;

import com.lore.common.analytics.AnalyticsService;
import com.lore.zzal.chat.prompt.SystemPromptCache;
import com.lore.zzal.chat.session.ZzalChatSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("채팅 v1 — 스위치·이벤트")
class ChatLineConfigTest {

    private final ChatLineConfig config = new ChatLineConfig();

    private static ChatLineEvents events(AnalyticsService analytics) {
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new ChatLineEvents(analytics, tx);
    }

    @Test
    @DisplayName("꺼짐(기본)이면 템플릿만, 켰는데 키가 없으면 기동을 막는다(설정 이름을 말한다)")
    void switchAndKey() {
        ZzalChatSessionRepository repo = mock(ZzalChatSessionRepository.class);
        AnalyticsService analytics = mock(AnalyticsService.class);
        LineChain off = config.lineChain(false, "gpt-5-mini", 4000, new BigDecimal("2"), "minimal", "",
                repo, new SystemPromptCache(), new TemplateLineGenerator(), events(analytics));
        assertThat(off.llmEnabled()).isFalse();
        assertThatThrownBy(() -> config.lineChain(true, "gpt-5-mini", 4000, new BigDecimal("2"), "minimal", " ",
                repo, new SystemPromptCache(), new TemplateLineGenerator(), events(analytics)))
                .hasMessageContaining("ZZAL_OPENAI_API_KEY");
        LineChain on = config.lineChain(true, "gpt-5-mini", 4000, new BigDecimal("2"), "minimal", "sk-test",
                repo, new SystemPromptCache(), new TemplateLineGenerator(), events(analytics));
        assertThat(on.llmEnabled()).isTrue();
    }

    @Test
    @DisplayName("이벤트 zzal_chat_llm — 허용된 키(action·type·reason·ms)로 서버 익명 번호에 남긴다")
    void eventRecorded() {
        AnalyticsService analytics = mock(AnalyticsService.class);
        events(analytics).record("continue", 3, "daily", "template", "timeout", 4001, 9L);
        verify(analytics).collect(org.mockito.ArgumentMatchers.argThat(b ->
                        b.events().size() == 1 && b.events().getFirst().name().equals("zzal_chat_llm")
                                && "timeout".equals(b.events().getFirst().props().get("reason"))
                                && "template".equals(b.events().getFirst().props().get("type"))
                                && Integer.valueOf(3).equals(b.events().getFirst().props().get("step"))
                                && "daily".equals(b.events().getFirst().props().get("code"))),
                eq(ChatLineEvents.SERVER_ANON), eq(9L), isNull());
    }
}
