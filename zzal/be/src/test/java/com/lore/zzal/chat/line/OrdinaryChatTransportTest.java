package com.lore.zzal.chat.line;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.zzal.chat.ChatSlot;
import com.lore.zzal.chat.persona.PersonaSheet;
import com.lore.zzal.chat.prompt.*;
import com.lore.zzal.chat.session.*;
import com.lore.zzal.pet.persona.AuthoredPersona;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.DayOfWeek;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrdinaryChatTransportTest {
    private static ChatContext context() {
        var persona = new AuthoredPersona(1, List.of(
                new AuthoredPersona.Field("job", List.of("사서"), "밤에 도서관을 지킨다", false),
                new AuthoredPersona.Field("persona", List.of("다정함"), "작은 일에도 관심을 보인다", false)));
        var sheet = new PersonaSheet("별이", List.of(), null, null, null, "별님", false, persona, "ja");
        return new ChatContext(7L, sheet, new PetState(DayOfWeek.MONDAY, 12, 0, 1, 0, 1, 0, false),
                SessionKind.DAILY, new TurnPlan(TurnType.CONTINUE, 2, true, null, true, ChatSlot.CONTINUOUS),
                null, List.of(new HistoryLine(Speaker.PET, "조금 쉬었어요", 1, ChatSlot.CONTINUOUS, 1L, false),
                        new HistoryLine(Speaker.USER, "오늘 그림 완성했어", 0, ChatSlot.CONTINUOUS, 2L, true)),
                List.of("custom:joy"));
    }

    @Test
    void ordinaryUsesNativeHistoryAndAllScenesUseLunaNoneWithoutChangingOutputContract() throws Exception {
        var requests = new ArrayList<JsonNode>();
        var json = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            requests.add(json.readTree(exchange.getRequestBody().readAllBytes()));
            var payload = json.createObjectNode();
            payload.putArray("choices").addObject().putObject("message").put("content",
                    "{\"line\":\"완성했다니 멋져요\",\"motion\":\"custom:joy\",\"call_me\":null,\"user_said\":\"그림 완성\",\"asked_back\":true,\"memory_deltas\":[]}");
            payload.putObject("usage").put("prompt_tokens", 100).put("completion_tokens", 20);
            byte[] bytes = json.writeValueAsBytes(payload);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var client = new OpenAiChatLineClient("stub-not-real", "none", HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat"));
            var generator = new LlmLineGenerator(client, "gpt-5.6-luna", Duration.ofSeconds(2), new SystemPromptCache());
            var ordinary = generator.generate(context());
            assertThat(ordinary.ok()).isTrue();
            assertThat(ordinary.model()).isEqualTo("gpt-5.6-luna");
            assertThat(ordinary.motion()).isEqualTo("custom:joy");
            assertThat(ordinary.extract()).isEqualTo(new LineExtract(null, "그림 완성", true, "[]"));
            assertThat(ordinary.costUsd()).isEqualByComparingTo("0.000044");
            for (var scene : List.of(new ConversationScene(ConversationScene.Phase.FIRST_MEETING, null),
                    new ConversationScene(ConversationScene.Phase.TUTORIAL, ConversationScene.Step.FEED),
                    new ConversationScene(ConversationScene.Phase.TUTORIAL_STAGE, ConversationScene.Step.GAME))) {
                assertThat(generator.generate(context().withScene(scene)).ok()).isTrue();
            }
            assertThat(requests).hasSize(4).allSatisfy(req -> {
                assertThat(req.path("model").asText()).isEqualTo("gpt-5.6-luna");
                assertThat(req.path("reasoning_effort").asText()).isEqualTo("none");
                assertThat(req.path("response_format").path("type").asText()).isEqualTo("json_object");
            });
            var messages = requests.getFirst().path("messages");
            assertThat(messages.size()).isEqualTo(4);
            assertThat(messages.get(0).path("content").asText()).contains("[현재 장면: 일반 채팅]", "별도 인물의 예시",
                    "밤에 도서관을 지킨다", "작은 일에도 관심을 보인다", "사용자 발언이 아니다", "별님", "일본어", "memory_deltas", "call_me", "user_said", "asked_back")
                    .doesNotContain("작가와 짧은 대화를 나눈다");
            assertThat(messages.get(1).path("role").asText()).isEqualTo("assistant");
            assertThat(messages.get(1).path("content").asText()).isEqualTo("조금 쉬었어요");
            assertThat(messages.get(2).path("role").asText()).isEqualTo("user");
            assertThat(messages.get(2).path("content").asText()).isEqualTo("오늘 그림 완성했어");
            assertThat(messages.get(3).path("content").asText()).contains("대화의 배경 정보", "1번째 메시지: 어제", "2번째 메시지: 오늘")
                    .doesNotContain("오늘 그림 완성했어", "조금 쉬었어요", "[지금까지]");
            for (var req : requests.subList(1, 4)) {
                assertThat(req.path("messages").size()).isEqualTo(2);
                assertThat(req.path("messages").get(0).path("content").asText()).doesNotContain("[현재 장면: 일반 채팅]", "<example>");
                assertThat(req.path("messages").get(1).path("content").asText()).contains("상대: 오늘 그림 완성했어");
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sceneCacheCannotLeakAndLegacyClientsStillReceiveCompleteHistory() {
        var ctx = context();
        var cache = new SystemPromptCache();
        String guided = cache.get(ctx.petId(), ctx.sheet());
        String ordinary = cache.get(ctx.petId(), ctx.sheet(), true);
        assertThat(ordinary).contains("[현재 장면: 일반 채팅]").isNotEqualTo(guided);
        assertThat(cache.get(ctx.petId(), ctx.sheet(), true)).isSameAs(ordinary);
        assertThat(cache.get(ctx.petId(), ctx.sheet())).isEqualTo(guided);
        assertThat(cache.builds()).isEqualTo(3);
        var legacy = new FakeChatLineClient().line("잘 됐네요", "custom:joy");
        assertThat(new LlmLineGenerator(legacy, "gpt-5.6-luna", Duration.ofSeconds(1), cache).generate(ctx).ok()).isTrue();
        assertThat(legacy.users.getFirst()).contains("너: 조금 쉬었어요", "상대: 오늘 그림 완성했어", "[이번 턴]");
        assertThat(ctx.withScene(new ConversationScene(ConversationScene.Phase.CONTINUOUS, null)).ordinaryChat()).isTrue();
    }

    @Test
    void lunaPricesDoNotUseUnknownModelFallback() {
        assertThat(OpenAiChatLineClient.cost("gpt-5.6-luna", 1_000_000, 0)).isEqualByComparingTo("0.20");
        assertThat(OpenAiChatLineClient.cost("gpt-5.6-luna", 0, 1_000_000)).isEqualByComparingTo("1.20");
    }
}
