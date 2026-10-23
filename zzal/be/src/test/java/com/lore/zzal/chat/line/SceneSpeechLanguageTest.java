package com.lore.zzal.chat.line;

import com.lore.zzal.chat.ChatSlot;
import com.lore.zzal.chat.persona.PersonaSheet;
import com.lore.zzal.chat.prompt.*;
import com.lore.zzal.chat.session.*;
import com.lore.zzal.chat.stage.*;
import com.lore.zzal.pet.persona.AuthoredPersona;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Captures the actual provider boundary; no provider or network requests. */
class SceneSpeechLanguageTest {
    private ChatContext context(String language, ConversationScene scene) {
        var persona = new AuthoredPersona(1, List.of(new AuthoredPersona.Field(
                "persona", List.of("다정함"), "존댓말로 부드럽게 말한다. 예: 반가워요.", false)));
        var sheet = new PersonaSheet("여울", List.of(), null, null, null, "상훈님", false, persona, language);
        return new ChatContext(7L, sheet,
                new PetState(DayOfWeek.WEDNESDAY, 12, 0, 3, 3, 3, 3, false), SessionKind.BABY,
                new TurnPlan(TurnType.FIRST_MEET, 1, false, null, false, ChatSlot.CONTINUOUS),
                null, List.of(), List.of()).withScene(scene);
    }

    private void assertSceneLanguage(String system, String label) {
        assertThat(system).contains("기본 언어: " + label,
                "첫 만남과 튜토리얼 장면의 대사는 위 기본 언어로 작성한다",
                "장면 안내·캐릭터 설정·말투 예시·저장 호칭의 언어를 상대의 대화 언어로 판단하지 않는다",
                "캐릭터 이름과 저장 호칭은 원문을 보존", "존댓말로 부드럽게 말한다", "상훈님", "여울");
    }

    @ParameterizedTest @CsvSource({"en,영어,Hello 상훈님.", "ja,일본어,こんにちは、상훈님。"})
    void discoveryGreetingAndSingleTutorialCarrySelectedLanguageWithKoreanPersona(
            String language, String label, String reply) {
        String expectedLabel = label;
        var calls = new AtomicInteger();
        ChatLineClient client = (system, user, model, timeout) -> {
            calls.incrementAndGet();
            assertSceneLanguage(system, expectedLabel);
            return new ChatLineClient.Completion("{\"line\":\"" + reply + "\"}", BigDecimal.ZERO, 0, 0);
        };
        var generator = new LlmLineGenerator(client, "stub", Duration.ofSeconds(1), new SystemPromptCache());
        for (var scene : List.of(
                new ConversationScene(ConversationScene.Phase.FIRST_MEETING, ConversationScene.Step.FIRST_MEETING_DISCOVERY),
                new ConversationScene(ConversationScene.Phase.FIRST_MEETING, null),
                new ConversationScene(ConversationScene.Phase.TUTORIAL_STAGE, ConversationScene.Step.FEED))) {
            var result = new LineChain(generator, null).generateOnce(context(language, scene), 1L);
            assertThat(result.text()).isEqualTo(reply);
            assertThat(result.attempts()).isEqualTo(1);
        }
        assertThat(calls.get()).isEqualTo(3);
    }

    @ParameterizedTest @CsvSource({"en,영어", "ja,일본어"})
    void tutorialBatchKeepsLanguageRulesAfterRemovingMemoryExtraction(String language, String label) {
        var calls = new AtomicInteger();
        ChatLineClient client = (system, user, model, timeout) -> {
            calls.incrementAndGet();
            assertSceneLanguage(system, label);
            assertThat(system).doesNotContain("[의미기억 추출]");
            assertThat(system).contains("모든 speeches[].line은 요청 데이터의 responseLanguage 언어로 작성한다");
            var request = new com.fasterxml.jackson.databind.ObjectMapper().readTree(user);
            assertThat(request.path("responseLanguage").asText()).isEqualTo(language);
            assertThat(request.path("scenes").size()).isEqualTo(1);
            assertThat(request.path("scenes").get(0).path("step").asText()).isEqualTo("FEED");
            assertThat(request.path("scenes").get(0).path("phase").asText()).isEqualTo("ENTRY");
            return new ChatLineClient.Completion(
                    "{\"speeches\":[{\"step\":\"FEED\",\"phase\":\"ENTRY\",\"line\":\"stub\"}]}", BigDecimal.ZERO, 0, 0);
        };
        var ctx = context(language, new ConversationScene(ConversationScene.Phase.TUTORIAL_STAGE, ConversationScene.Step.FEED));
        var results = new StageSpeechBatchGenerator(client, "stub", Duration.ofSeconds(1)).generate(List.of(
                new StageSpeechTransactions.Claim(true, 1L, UUID.randomUUID(), ctx, null)));
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().outcome()).isEqualTo(LineOutcome.OK);
        assertThat(calls.get()).isEqualTo(1);
    }

    @ParameterizedTest @CsvSource({"en,영어", "ja,일본어"})
    void ordinaryChatRetainsActualUserLanguagePriorityAcrossCachedModeSwitch(String language, String label) {
        var ctx = context(language, null);
        var cache = new SystemPromptCache();
        assertSceneLanguage(cache.get(7L, ctx.sheet(), false), label);
        String ordinary = cache.get(7L, ctx.sheet(), true);
        assertThat(ordinary).contains("사용자가 이번에 명시한 언어 전환 요청이 가장 우선한다",
                "이번 사용자 말과 이어지는 대화의 언어", "상훈님", "존댓말로 부드럽게 말한다")
                .doesNotContain("첫 만남과 튜토리얼 장면의 대사는 위 기본 언어로 작성한다");
        assertSceneLanguage(cache.get(7L, ctx.sheet(), false), label);
        assertThat(cache.builds()).isEqualTo(3);
    }
}
