package com.lore.zzal.chat.line;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lore.zzal.chat.ChatSlot;
import com.lore.zzal.chat.ChatTemplates;
import com.lore.zzal.chat.memory.Memory;
import com.lore.zzal.chat.persona.PersonaSheet;
import com.lore.zzal.chat.persona.PersonaSheetBuilderSampleAccess;
import com.lore.zzal.chat.prompt.ChatContext;
import com.lore.zzal.chat.prompt.LineKind;
import com.lore.zzal.chat.prompt.PetState;
import com.lore.zzal.pet.Personality;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * ★ 실제 OpenAI 를 부르는 샘플 뽑기 — <b>돈이 나간다.</b> 평소 시험에서는 돌지 않는다.
 *
 * 환경변수 셋이 다 있을 때만 돈다: {@code CHAT_V1_SAMPLE_KEY}(API 키) · {@code CHAT_V1_SAMPLE_IN}(펫 재료 JSON) ·
 * {@code CHAT_V1_SAMPLE_OUT}(결과 JSON). 펫 하나 × 상황 4개(아침 부름 · "오늘 좀 힘들었어" · 이름 유래 · 배고플 때 "뭐해?")
 * 를 서비스와 같은 지시문·필터로 한 줄씩 뽑고, 같은 자리의 템플릿 대사를 나란히 남긴다.
 */
@EnabledIfEnvironmentVariable(named = "CHAT_V1_SAMPLE_KEY", matches = ".+")
@DisplayName("채팅 v1 — 실제 모델 샘플(수동)")
class RealModelSampleTest {

    @Test
    void sample() throws Exception {
        String model = System.getenv().getOrDefault("CHAT_V1_SAMPLE_MODEL", "gpt-5-mini");
        ObjectMapper json = new ObjectMapper();
        JsonNode pets = json.readTree(Files.readString(Path.of(System.getenv("CHAT_V1_SAMPLE_IN"))));
        OpenAiChatLineClient client = new OpenAiChatLineClient(System.getenv("CHAT_V1_SAMPLE_KEY"),
                System.getenv().getOrDefault("CHAT_V1_SAMPLE_EFFORT", "minimal"));
        // 샘플은 시간 제한을 넉넉히 — 4초를 넘긴 것도 대사를 보고 싶다(걸린 시간은 따로 적는다).
        LlmLineGenerator gen = new LlmLineGenerator(client, model, Duration.ofSeconds(30), new BigDecimal("1"),
                () -> BigDecimal.ZERO);
        ArrayNode out = json.createArrayNode();
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode p : pets) {
            Personality lead = p.path("personality").asText("").isBlank() ? null
                    : Personality.valueOf(p.path("personality").asText());
            PersonaSheet sheet = new PersonaSheet(p.path("name").asText().strip(),
                    lead == null ? List.of() : List.of(lead), null, null, blank(p.path("world").asText("")),
                    blank(p.path("note").asText("")), null, false,
                    PersonaSheetBuilderSampleAccess.appearance(p.path("identity").asText("")));
            List<Memory> mem = new ArrayList<>();
            for (JsonNode a : p.path("answers")) {
                mem.addFirst(Memory.recentAnswer(a.asText(), null));
            }
            int answered = mem.size();
            String name = sheet.name();
            List<ChatContext> scenes = List.of(
                    new ChatContext(sheet, new PetState(8, 10, 2, 3, 2, 3, false), LineKind.CALL, ChatSlot.MORNING,
                            null, null, mem, null, answered, false, List.of()),
                    reply(sheet, ChatSlot.EVENING, new PetState(21, 0, 3, 3, 3, 3, false), "오늘 좀 힘들었어", mem, answered),
                    reply(sheet, ChatSlot.NOON, new PetState(15, 0, 3, 3, 3, 3, false),
                            "너 이름이 왜 " + name + (batchim(name) ? "이야?" : "야?"), mem, answered),
                    reply(sheet, ChatSlot.NOON, new PetState(13, 0, 3, 1, 2, 2, false), "뭐해?", mem, answered));
            String[] keys = {"a", "b", "c", "d"};
            for (int i = 0; i < scenes.size(); i++) {
                ChatContext ctx = scenes.get(i);
                LineAttempt a = gen.generate(ctx);
                total = total.add(a.costUsd());
                ObjectNode row = out.addObject();
                row.put("pet", p.path("id").asInt());
                row.put("name", name);
                row.put("scene", keys[i]);
                row.put("allowQuestion", ctx.allowQuestion());
                row.put("line", a.text());
                row.put("motion", a.motion());
                row.put("fail", a.failReason());
                row.put("ms", a.millis());
                row.put("cost", a.costUsd());
                row.put("template", ctx.kind() == LineKind.CALL
                        ? ChatTemplates.call(sheet.lead(), ctx.slot(), name)
                        : ChatTemplates.reply(sheet.lead(), ctx.answer(), List.of(), 1));
                row.put("callLine", ctx.callLine());
            }
        }
        ObjectNode doc = json.createObjectNode();
        doc.put("model", model);
        doc.put("totalCost", total);
        doc.set("rows", out);
        Files.writeString(Path.of(System.getenv("CHAT_V1_SAMPLE_OUT")), json.writerWithDefaultPrettyPrinter().writeValueAsString(doc));
    }

    private static ChatContext reply(PersonaSheet sheet, ChatSlot slot, PetState st, String answer, List<Memory> mem,
                                     int answered) {
        // 서비스와 같은 규칙 — 보통 답은 세 번에 한 번만 질문 허용(answeredBefore % 3 == 1).
        return new ChatContext(sheet, st, LineKind.REPLY, slot, ChatTemplates.call(sheet.lead(), slot, sheet.name()),
                answer, mem, null, answered, answered % 3 == 1, List.of("hello", "joy"));
    }

    private static boolean batchim(String name) {
        char c = name.charAt(name.length() - 1);
        return c >= 0xAC00 && c <= 0xD7A3 && (c - 0xAC00) % 28 != 0;
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }
}
