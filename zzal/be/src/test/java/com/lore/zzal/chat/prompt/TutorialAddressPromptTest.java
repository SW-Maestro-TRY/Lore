package com.lore.zzal.chat.prompt;

import com.lore.zzal.chat.ChatSlot;
import com.lore.zzal.chat.line.ChatLineClient;
import com.lore.zzal.chat.line.LlmLineGenerator;
import com.lore.zzal.chat.persona.PersonaSheet;
import com.lore.zzal.chat.session.SessionKind;
import com.lore.zzal.chat.session.TurnPlan;
import com.lore.zzal.chat.session.TurnType;
import com.lore.zzal.chat.stage.StageSpeechBatchGenerator;
import com.lore.zzal.chat.stage.StageSpeechTransactions;
import com.lore.zzal.pet.persona.AuthoredPersona;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TutorialAddressPromptTest {
    @Test
    void batchAndSingleSendTheSameAddressGuidanceAndPreserveOriginalSettings() {
        var cases = List.of(new String[]{"ko", "상훈", "한국어"},
                new String[]{"en", "Alex", "영어"}, new String[]{"ja", "ゆきさん", "일본어"});
        for (var language : cases) {
            var sheet = sheet(language[0], language[1], false);
            var requests = tutorialRequests(sheet);
            assertThat(requests).hasSize(5); // 배치 1회 + 두 튜토리얼 장면의 ENTRY/AFTER_ACTION
            assertThat(requests).allSatisfy(request -> {
                assertThat(request.system()).contains("상대를 부르는 말: " + language[1] + "\n",
                        "현재 저장된 값", language[2], "원문: 조용히 말하고 다정하게 반응한다", "chip-persona");
                assertThat(request.system() + request.user()).containsOnlyOnce(PromptAssembler.TUTORIAL_ADDRESS_RULES);
            });
            assertThat(requests.getFirst().system()).contains(PromptAssembler.TUTORIAL_ADDRESS_RULES);
            assertThat(requests.subList(1, requests.size())).allSatisfy(request ->
                    assertThat(request.user()).contains(PromptAssembler.TUTORIAL_ADDRESS_RULES));
        }
    }

    @Test
    void choosingNoAddressRemainsAuthoritativeInBatchAndSingle() {
        assertThat(tutorialRequests(sheet("ja", null, true))).allSatisfy(request ->
                assertThat(request.system()).contains("상대를 따로 부르지 않는다.")
                        .doesNotContain("상대를 부르는 말:", "저장된 호칭이 없으면"));
    }

    @Test
    void ordinaryChatAndFirstMeetingDoNotReceiveTutorialAddressInstructions() {
        var sent = new ArrayList<Request>();
        var generator = single(sent);
        var sheet = sheet("ko", "상훈", false);
        assertThat(generator.generate(context(sheet, null)).ok()).isTrue();
        assertThat(generator.generate(context(sheet, new ConversationScene(ConversationScene.Phase.CONTINUOUS, null))).ok()).isTrue();
        assertThat(generator.generate(context(sheet, new ConversationScene(ConversationScene.Phase.FIRST_MEETING, null))).ok()).isTrue();
        assertThat(generator.generate(context(sheet("ko", null, false), new ConversationScene(
                ConversationScene.Phase.FIRST_MEETING, ConversationScene.Step.FIRST_MEETING_DISCOVERY))).ok()).isTrue();
        assertThat(sent).allSatisfy(request -> assertThat(request.system() + request.user())
                .doesNotContain(PromptAssembler.TUTORIAL_ADDRESS_RULES));
        assertThat(sent.subList(0, 2)).allSatisfy(request -> assertThat(request.system())
                .contains("[현재 장면: 일반 채팅]", "매 문장에 호칭을 넣을 필요는 없다"));
        assertThat(sent.subList(2, 4)).allSatisfy(request -> assertThat(request.system())
                .contains("호칭은 첫 만남에서만 받는다").doesNotContain("[현재 장면: 일반 채팅]"));
        assertThat(sent.get(2).user()).contains("저장된 호칭 선택을 확인한 뒤의 첫 인사");
        assertThat(sent.get(3).user()).contains("호칭 선택을 아직 받지 않은 발견 장면");
    }

    private List<Request> tutorialRequests(PersonaSheet sheet) {
        var sent = new ArrayList<Request>();
        ChatLineClient batchClient = (system, user, model, timeout) -> {
            sent.add(new Request(system, user));
            return completion("{\"speeches\":[{\"step\":\"FEED\",\"phase\":\"ENTRY\",\"line\":\"밥을 챙겨 줄래?\",\"motion\":\"base\"},"
                    + "{\"step\":\"FEED\",\"phase\":\"AFTER_ACTION\",\"line\":\"밥 챙겨 줘서 고마워.\",\"motion\":\"base\"}]}");
        };
        var batch = new StageSpeechBatchGenerator(batchClient, "stub-only", Duration.ofSeconds(1));
        var claims = List.of(ConversationScene.Beat.ENTRY, ConversationScene.Beat.AFTER_ACTION).stream()
                .map(beat -> new StageSpeechTransactions.Claim(true, 1L, UUID.randomUUID(), context(sheet,
                        new ConversationScene(ConversationScene.Phase.TUTORIAL_STAGE, ConversationScene.Step.FEED, beat)), null)).toList();
        assertThat(batch.generate(claims)).hasSize(2).allSatisfy(line -> assertThat(line.text()).isNotBlank());
        var single = single(sent);
        for (var phase : List.of(ConversationScene.Phase.TUTORIAL, ConversationScene.Phase.TUTORIAL_STAGE)) {
            for (var beat : ConversationScene.Beat.values()) {
                assertThat(single.generate(context(sheet, new ConversationScene(phase, ConversationScene.Step.FEED, beat))).ok()).isTrue();
            }
        }
        return sent;
    }

    private LlmLineGenerator single(List<Request> sent) {
        ChatLineClient client = (system, user, model, timeout) -> {
            sent.add(new Request(system, user));
            return completion("{\"line\":\"고마워.\",\"motion\":\"base\",\"memory_deltas\":[]}");
        };
        return new LlmLineGenerator(client, "stub-only", Duration.ofSeconds(1), new SystemPromptCache());
    }

    private PersonaSheet sheet(String language, String address, boolean declined) {
        return new PersonaSheet("별이", List.of(), null, null, null, address, declined,
                new AuthoredPersona(1, List.of(new AuthoredPersona.Field("persona", List.of("chip-persona"),
                        "원문: 조용히 말하고 다정하게 반응한다", false))), language);
    }

    private ChatContext context(PersonaSheet sheet, ConversationScene scene) {
        return new ChatContext(23L, sheet, new PetState(DayOfWeek.MONDAY, 12, 0, 1, 2, 2, 2, false),
                SessionKind.BABY, new TurnPlan(TurnType.CONTINUE, 2, false, null, false, ChatSlot.CONTINUOUS),
                null, List.of(), List.of()).withScene(scene);
    }

    private ChatLineClient.Completion completion(String response) {
        return new ChatLineClient.Completion(response, BigDecimal.ZERO, 0, 0);
    }

    private record Request(String system, String user) {}
}
