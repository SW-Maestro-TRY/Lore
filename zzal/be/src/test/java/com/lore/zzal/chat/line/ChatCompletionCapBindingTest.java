package com.lore.zzal.chat.line;

import com.lore.zzal.chat.prompt.SystemPromptCache;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ChatCompletionCapBindingTest {
    @Test void optionalCapBindsToProviderAndUnsetPreservesPreviousBehavior() {
        for (Integer cap : new Integer[]{null, 1024}) {
            try (var context = new AnnotationConfigApplicationContext()) {
                TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                        "app.zzal.chat.llm=true", "app.zzal.openai.api-key=not-a-real-key", "app.lore.env=local");
                if (cap != null) TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                        "app.zzal.chat.max-completion-tokens=" + cap);
                context.registerBean(SystemPromptCache.class, SystemPromptCache::new);
                context.registerBean(ChatLineEvents.class, () -> mock(ChatLineEvents.class));
                context.register(ChatLineConfig.class);
                context.refresh();
                var generator = ReflectionTestUtils.getField(context.getBean(LineChain.class), "llm");
                var client = ReflectionTestUtils.getField(generator, "client");
                assertThat(ReflectionTestUtils.getField(client, "maxCompletionTokens")).isEqualTo(cap);
            }
        }
    }
}
