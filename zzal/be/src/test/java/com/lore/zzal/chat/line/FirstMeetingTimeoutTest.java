package com.lore.zzal.chat.line;

import com.lore.zzal.chat.ChatSlot;
import com.lore.zzal.chat.persona.PersonaSheet;
import com.lore.zzal.chat.prompt.ChatContext;
import com.lore.zzal.chat.prompt.ConversationScene;
import com.lore.zzal.chat.prompt.PetState;
import com.lore.zzal.chat.prompt.SystemPromptCache;
import com.lore.zzal.chat.session.SessionKind;
import com.lore.zzal.chat.session.TurnPlan;
import com.lore.zzal.chat.session.TurnType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FirstMeetingTimeoutTest {
    private static final Duration NORMAL = Duration.ofSeconds(4);
    private static final Duration FIRST = Duration.ofSeconds(12);

    private ChatContext context(TurnType type, ChatSlot slot, ConversationScene scene) {
        return new ChatContext(7L, new PersonaSheet("테스트", List.of(), null, null, null, null, false),
                new PetState(DayOfWeek.WEDNESDAY, 12, 0, 3, 3, 3, 3, false), SessionKind.BABY,
                new TurnPlan(type, 1, false, null, false, slot), null, List.of(), List.of()).withScene(scene);
    }

    private ConversationScene discovery() {
        return new ConversationScene(ConversationScene.Phase.FIRST_MEETING,
                ConversationScene.Step.FIRST_MEETING_DISCOVERY);
    }

    private LlmLineGenerator generator(Capture client, Duration first) {
        return new LlmLineGenerator(client, "gpt-5.6-luna", NORMAL, first, new SystemPromptCache());
    }

    @Test void discoveryAndConfirmedAddressGreetingUseFirstMeetingTimeout() {
        Capture client = new Capture();
        LlmLineGenerator generator = generator(client, FIRST);
        for (ConversationScene scene : List.of(discovery(),
                new ConversationScene(ConversationScene.Phase.FIRST_MEETING, null))) {
            assertThat(generator.generate(context(TurnType.FIRST_MEET, ChatSlot.CONTINUOUS, scene)).ok()).isTrue();
        }
        assertThat(client.timeouts).containsExactly(FIRST, FIRST);
        assertThat(client.nativeHistoryCalls).isZero();
    }

    @Test void ordinaryTutorialAndLegacyFirstMeetKeepNormalTimeout() {
        Capture client = new Capture();
        LlmLineGenerator generator = generator(client, FIRST);
        List<ChatContext> others = List.of(
                context(TurnType.CONTINUE, ChatSlot.CONTINUOUS, null),
                context(TurnType.FIRST_MEET, ChatSlot.CONTINUOUS,
                        new ConversationScene(ConversationScene.Phase.CONTINUOUS, null)),
                context(TurnType.FIRST_MEET, ChatSlot.BABY, null),
                context(TurnType.GREETING, ChatSlot.MORNING, null),
                context(TurnType.FIRST_MEET, ChatSlot.CONTINUOUS,
                        new ConversationScene(ConversationScene.Phase.TUTORIAL, ConversationScene.Step.FEED)),
                context(TurnType.CONTINUE, ChatSlot.CONTINUOUS,
                        new ConversationScene(ConversationScene.Phase.TUTORIAL_STAGE, ConversationScene.Step.FEED)),
                context(TurnType.CONTINUE, ChatSlot.CONTINUOUS,
                        new ConversationScene(ConversationScene.Phase.TUTORIAL_STAGE, ConversationScene.Step.CHAT_INVITE)));
        for (ChatContext ctx : others) assertThat(generator.generate(ctx).ok()).isTrue();
        assertThat(client.timeouts).containsExactly(NORMAL, NORMAL, NORMAL, NORMAL, NORMAL, NORMAL, NORMAL);
        assertThat(client.nativeHistoryCalls).isEqualTo(2);
    }

    @Test void firstMeetingOverrideDoesNotChangeOrdinaryTimeoutAndOldConstructorIsCompatible() {
        Capture client = new Capture();
        LlmLineGenerator generator = generator(client, Duration.ofMillis(7500));
        generator.generate(context(TurnType.FIRST_MEET, ChatSlot.CONTINUOUS, discovery()));
        generator.generate(context(TurnType.CONTINUE, ChatSlot.CONTINUOUS, null));
        new LlmLineGenerator(client, "gpt-5.6-luna", NORMAL, new SystemPromptCache())
                .generate(context(TurnType.FIRST_MEET, ChatSlot.CONTINUOUS, discovery()));
        assertThat(client.timeouts).containsExactly(Duration.ofMillis(7500), NORMAL, NORMAL);
    }

    @Test void timeoutStillUsesOneClaimedAttemptWithoutRetry() {
        Capture client = new Capture();
        client.timeout = true;
        GeneratedLine result = new LineChain(generator(client, FIRST), null)
                .generateOnce(context(TurnType.FIRST_MEET, ChatSlot.CONTINUOUS, discovery()), 1L);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.failReason()).isEqualTo("timeout");
        assertThat(client.timeouts).containsExactly(FIRST);
    }

    @Test void configurationDefaultsAndOverrideWireSeparateBudgetsAndRejectNonPositiveValues() throws Exception {
        ChatLineConfig config = new ChatLineConfig();
        assertThat(ChatLineConfig.class.getDeclaredField("firstMeetingTimeoutMs").getAnnotation(Value.class).value())
                .isEqualTo("${app.zzal.chat.first-meeting-timeout-ms:12000}");
        assertBudgets(config, FIRST);
        ReflectionTestUtils.setField(config, "firstMeetingTimeoutMs", 7500L);
        assertBudgets(config, Duration.ofMillis(7500));
        ReflectionTestUtils.setField(config, "firstMeetingTimeoutMs", 0L);
        assertThatThrownBy(() -> configured(config)).hasMessageContaining("first-meeting-timeout-ms must be positive");
        ReflectionTestUtils.setField(config, "firstMeetingTimeoutMs", -1L);
        assertThatThrownBy(() -> configured(config)).hasMessageContaining("first-meeting-timeout-ms must be positive");
    }

    private LineChain configured(ChatLineConfig config) {
        // Builds the HTTP adapter only; it never makes a provider request.
        return config.lineChain(true, "gpt-5.6-luna", 4000, "none", "stub-not-used", new SystemPromptCache(), null, "test");
    }

    private void assertBudgets(ChatLineConfig config, Duration first) {
        Object generator = ReflectionTestUtils.getField(configured(config), "llm");
        assertThat(ReflectionTestUtils.getField(generator, "timeout")).isEqualTo(NORMAL);
        assertThat(ReflectionTestUtils.getField(generator, "firstMeetingTimeout")).isEqualTo(first);
    }

    private static final class Capture implements ChatLineClient {
        final List<Duration> timeouts = new ArrayList<>();
        int nativeHistoryCalls;
        boolean timeout;

        @Override public Completion complete(String system, String user, String model, Duration limit) throws Exception {
            timeouts.add(limit);
            if (timeout) throw new TimeoutException("synthetic only");
            return new Completion("{\"line\":\"반가워.\"}", BigDecimal.ZERO, 0, 0);
        }

        @Override public Completion complete(String system, List<Message> history, String user, String model,
                                             Duration limit) throws Exception {
            nativeHistoryCalls++;
            return complete(system, user, model, limit);
        }
    }
}
