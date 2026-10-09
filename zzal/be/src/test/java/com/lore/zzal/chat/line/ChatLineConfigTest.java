package com.lore.zzal.chat.line;

import com.lore.common.analytics.AnalyticsService;
import com.lore.zzal.chat.prompt.SystemPromptCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("채팅 — 스위치·이벤트")
class ChatLineConfigTest {

    private final ChatLineConfig config = new ChatLineConfig();

    private static ChatLineEvents events(AnalyticsService analytics) {
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new ChatLineEvents(analytics, tx);
    }

    @Test
    @DisplayName("꺼짐이면 부름 없음(꺼진 사슬), 켰는데 키가 없으면 기동을 막는다(설정 이름을 말한다)")
    void switchAndKey() {
        AnalyticsService analytics = mock(AnalyticsService.class);
        LineChain off = config.lineChain(false, "gpt-5-mini", 4000, "minimal", "", new SystemPromptCache(),
                events(analytics));
        assertThat(off.llmEnabled()).isFalse();
        assertThatThrownBy(() -> config.lineChain(true, "gpt-5-mini", 4000, "minimal", " ", new SystemPromptCache(),
                events(analytics))).hasMessageContaining("ZZAL_OPENAI_API_KEY");
        LineChain on = config.lineChain(true, "gpt-5-mini", 4000, "minimal", "sk-test", new SystemPromptCache(),
                events(analytics));
        assertThat(on.llmEnabled()).isTrue();
    }

    @Test
    @DisplayName("★ 스위치 기본값은 켜짐 — 운영은 기존 키(ZZAL_OPENAI_API_KEY)만으로 켜진다(새 파라미터 없음)")
    void defaultIsOn() throws Exception {
        Method m = ChatLineConfig.class.getMethod("lineChain", boolean.class, String.class, long.class, String.class,
                String.class, SystemPromptCache.class, ChatLineEvents.class);
        Value v = m.getParameters()[0].getAnnotation(Value.class);
        assertThat(v.value()).isEqualTo("${app.zzal.chat.llm:true}");
    }

    @Test
    @DisplayName("★ 사슬이 남기는 이벤트 — reason=outcome, ms=걸린 시간, count=호출 횟수, step·action·code")
    void eventFromChain() {
        AnalyticsService analytics = mock(AnalyticsService.class);
        LineChain chain = new LineChain(new LlmLineGenerator(new FakeChatLineClient().reply("깨짐").line("응", "hello"),
                "gpt-5-mini", java.time.Duration.ofSeconds(4), new SystemPromptCache()), events(analytics));
        chain.generate(LlmLineGeneratorTest.cont(com.lore.zzal.chat.session.TurnType.CLOSE, 6), 9L);
        verify(analytics).collect(org.mockito.ArgumentMatchers.argThat(b -> {
                    var p = b.events().getFirst().props();
                    return "llm".equals(p.get("type")) && "retried_ok".equals(p.get("reason")) && "close".equals(p.get("action"))
                            && Integer.valueOf(6).equals(p.get("step")) && "daily".equals(p.get("code"))
                            && Integer.valueOf(2).equals(p.get("count")) && p.get("ms") instanceof Long;
                }), eq(ChatLineEvents.SERVER_ANON), eq(9L), isNull());
    }

    @Test
    @DisplayName("이벤트 zzal_chat_llm — 허용된 키(action·type·reason·ms·count)로 서버 익명 번호에 남긴다")
    void eventRecorded() {
        AnalyticsService analytics = mock(AnalyticsService.class);
        events(analytics).record("continue", 3, "daily", "fixed", "failed_closed", 8001, 2, 9L);
        verify(analytics).collect(org.mockito.ArgumentMatchers.argThat(b ->
                        b.events().size() == 1 && b.events().getFirst().name().equals("zzal_chat_llm")
                                && "failed_closed".equals(b.events().getFirst().props().get("reason"))
                                && "fixed".equals(b.events().getFirst().props().get("type"))
                                && Long.valueOf(8001).equals(b.events().getFirst().props().get("ms"))
                                && Integer.valueOf(3).equals(b.events().getFirst().props().get("step"))
                                && "daily".equals(b.events().getFirst().props().get("code"))),
                eq(ChatLineEvents.SERVER_ANON), eq(9L), isNull());
    }
}
