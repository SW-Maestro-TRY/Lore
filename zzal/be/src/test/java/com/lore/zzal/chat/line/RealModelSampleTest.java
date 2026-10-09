package com.lore.zzal.chat.line;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lore.zzal.chat.persona.PersonaSheet;
import com.lore.zzal.chat.persona.PersonaSheetBuilderSampleAccess;
import com.lore.zzal.chat.prompt.ChatContext;
import com.lore.zzal.chat.prompt.HistoryLine;
import com.lore.zzal.chat.prompt.PetState;
import com.lore.zzal.chat.prompt.SystemPromptCache;
import com.lore.zzal.chat.session.QuestionItem;
import com.lore.zzal.chat.session.SessionKind;
import com.lore.zzal.chat.session.Speaker;
import com.lore.zzal.chat.session.TurnPlan;
import com.lore.zzal.chat.session.TurnType;
import com.lore.zzal.pet.Personality;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * ★ 실제 OpenAI 를 부르는 샘플 뽑기 — <b>돈이 나간다. 상훈님 승인 뒤에만 돌린다.</b> 평소 시험에서는 돌지 않는다.
 *
 * 환경변수 셋이 다 있을 때만: {@code CHAT_V1_SAMPLE_KEY} · {@code CHAT_V1_SAMPLE_IN}(펫 재료 JSON) · {@code CHAT_V1_SAMPLE_OUT}.
 * 펫마다 [오늘 첫 인사 · 이어 말하기(답 "오늘 좀 힘들었어") · 이어 말하기(되묻기 "너는 뭐 해?") · 닫기] 4턴을
 * 서비스와 같은 지시문·필터로 한 줄씩 뽑고, 같은 자리의 폴백 문형을 나란히 남긴다.
 */
@EnabledIfEnvironmentVariable(named = "CHAT_V1_SAMPLE_KEY", matches = ".+")
@DisplayName("채팅 — 실제 모델 샘플(수동)")
class RealModelSampleTest {

    @Test
    void sample() throws Exception {
        String model = System.getenv().getOrDefault("CHAT_V1_SAMPLE_MODEL", "gpt-5-mini");
        ObjectMapper json = new ObjectMapper();
        JsonNode pets = json.readTree(Files.readString(Path.of(System.getenv("CHAT_V1_SAMPLE_IN"))));
        OpenAiChatLineClient client = new OpenAiChatLineClient(System.getenv("CHAT_V1_SAMPLE_KEY"),
                System.getenv().getOrDefault("CHAT_V1_SAMPLE_EFFORT", "minimal"));
        LlmLineGenerator gen = new LlmLineGenerator(client, model, Duration.ofSeconds(30), new BigDecimal("1"),
                () -> BigDecimal.ZERO, new SystemPromptCache());
        ArrayNode out = json.createArrayNode();
        BigDecimal total = BigDecimal.ZERO;
        long id = 1;
        for (JsonNode p : pets) {
            String lead = p.path("personality").asText("");
            PersonaSheet sheet = new PersonaSheet(p.path("name").asText().strip(),
                    lead.isBlank() ? List.of() : List.of(Personality.valueOf(lead)), null,
                    blank(p.path("world").asText("")), blank(p.path("note").asText("")),
                    PersonaSheetBuilderSampleAccess.appearance(p.path("identity").asText("")), null, false);
            String lastLine = p.path("answers").size() == 0 ? null : p.path("answers").get(p.path("answers").size() - 1).asText();
            PetState st = new PetState(DayOfWeek.THURSDAY, 20, 0, 3, 3, 3, 3, false);
            List<HistoryLine> h = new ArrayList<>();
            String[][] scenes = {{null, "GREETING"}, {"오늘 좀 힘들었어", "CONTINUE"}, {"너는 뭐 해?", "CONTINUE"},
                    {"나 이제 가 볼게", "CLOSE"}};
            for (int i = 0; i < scenes.length; i++) {
                if (scenes[i][0] != null) {
                    h.add(new HistoryLine(Speaker.USER, scenes[i][0]));
                }
                TurnType type = TurnType.valueOf(scenes[i][1]);
                boolean asked = scenes[i][0] != null && scenes[i][0].contains("?");
                TurnPlan plan = new TurnPlan(type, i + 1, i == 0 || (i % 2 == 0 && !asked) && type != TurnType.CLOSE,
                        i == 0 ? QuestionItem.CALL_ME : null, asked);
                ChatContext ctx = new ChatContext(id, sheet, st, SessionKind.DAILY, plan, lastLine, List.copyOf(h),
                        List.of("hello", "joy"));
                LineAttempt a = gen.generate(ctx);
                total = total.add(a.costUsd());
                h.add(new HistoryLine(Speaker.PET, a.ok() ? a.text() : FallbackLines.line(sheet, type)));
                ObjectNode row = out.addObject();
                row.put("pet", p.path("id").asInt());
                row.put("name", sheet.name());
                row.put("turn", type.label());
                row.put("line", a.text());
                row.put("fail", a.failReason());
                row.put("ms", a.millis());
                row.put("cost", a.costUsd());
                row.put("fallback", FallbackLines.line(sheet, type));
            }
            id++;
        }
        ObjectNode doc = json.createObjectNode();
        doc.put("model", model);
        doc.put("totalCost", total);
        doc.set("rows", out);
        Files.writeString(Path.of(System.getenv("CHAT_V1_SAMPLE_OUT")), json.writerWithDefaultPrettyPrinter().writeValueAsString(doc));
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }
}
