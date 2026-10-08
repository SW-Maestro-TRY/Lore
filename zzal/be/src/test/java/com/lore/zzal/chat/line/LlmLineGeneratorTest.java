package com.lore.zzal.chat.line;

import com.lore.zzal.chat.ChatSlot;
import com.lore.zzal.chat.memory.Memory;
import com.lore.zzal.chat.persona.PersonaSheet;
import com.lore.zzal.chat.prompt.ChatContext;
import com.lore.zzal.chat.prompt.LineKind;
import com.lore.zzal.chat.prompt.PetState;
import com.lore.zzal.pet.Personality;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("채팅 v1 — LLM 생성기·폴백 사슬")
class LlmLineGeneratorTest {

    static ChatContext reply(String answer) {
        PersonaSheet sheet = new PersonaSheet("루아나", List.of(Personality.SHY), null, null, "현대 · 학교", null,
                null, false, "light blue beret, aqua jacket");
        return new ChatContext(sheet, new PetState(21, 0, 3, 3, 3, 3, false), LineKind.REPLY, ChatSlot.EVENING,
                "…오늘도 와 줘서, 고마워요.", answer, List.of(Memory.recentAnswer("안녕너무귀여워", null)), null, 1,
                false, List.of("hello", "joy"));
    }

    private static LlmLineGenerator gen(FakeChatLineClient c, BigDecimal spent) {
        return new LlmLineGenerator(c, "gpt-5-mini", Duration.ofSeconds(4), new BigDecimal("2"), () -> spent);
    }

    @Test
    @DisplayName("JSON 의 line·motion 을 그대로 쓴다. 비용·모델이 남는다")
    void ok() {
        FakeChatLineClient c = new FakeChatLineClient().reply("{\"line\":\"…힘들었구나. 옆에 있을게요.\",\"motion\":\"joy\"}");
        LineAttempt a = gen(c, BigDecimal.ZERO).generate(reply("오늘 좀 힘들었어"));
        assertThat(a.ok()).isTrue();
        assertThat(a.text()).isEqualTo("…힘들었구나. 옆에 있을게요.");
        assertThat(a.motion()).isEqualTo("joy");
        assertThat(a.model()).isEqualTo("gpt-5-mini");
        assertThat(a.costUsd()).isEqualByComparingTo(FakeChatLineClient.COST);
    }

    @Test
    @DisplayName("목록 밖 동작은 기본값으로 — 대사는 버리지 않는다. 코드 블록·바깥 따옴표는 벗긴다")
    void motionOutsideListAndFence() {
        FakeChatLineClient c = new FakeChatLineClient().reply("```json\n{\"line\":\"\\\"…응.\\\"\",\"motion\":\"sleep\"}\n```");
        LineAttempt a = gen(c, BigDecimal.ZERO).generate(reply("뭐해"));
        assertThat(a.ok()).isTrue();
        assertThat(a.text()).isEqualTo("…응.");
        assertThat(a.motion()).isEqualTo("hello");
    }

    @Test
    @DisplayName("파싱 실패·빈 줄·시간 초과·오류는 사유를 담아 실패, 비용은 남긴다(시간 초과는 추정치)")
    void failures() {
        assertThat(gen(new FakeChatLineClient().reply("그냥 글"), BigDecimal.ZERO).generate(reply("a")).failReason())
                .isEqualTo("parse");
        LineAttempt blank = gen(new FakeChatLineClient().reply("{\"line\":\"  \"}"), BigDecimal.ZERO).generate(reply("a"));
        assertThat(blank.failReason()).isEqualTo("blank");
        assertThat(blank.costUsd()).isEqualByComparingTo(FakeChatLineClient.COST);
        LineAttempt slow = gen(new FakeChatLineClient().fail(new TimeoutException()), BigDecimal.ZERO).generate(reply("a"));
        assertThat(slow.failReason()).isEqualTo("timeout");
        assertThat(slow.costUsd()).isPositive();
        assertThat(gen(new FakeChatLineClient().fail(new IllegalStateException("HTTP 500")), BigDecimal.ZERO)
                .generate(reply("a")).failReason()).isEqualTo("error");
    }

    @Test
    @DisplayName("출력 검사 — 길이 60자 초과·질문 둘·원망·작가 메모의 민감 소재(술·우울·살인)")
    void filtered() {
        String longLine = "가".repeat(61);
        assertThat(gen(new FakeChatLineClient().reply("{\"line\":\"" + longLine + "\"}"), BigDecimal.ZERO)
                .generate(reply("a")).failReason()).isEqualTo("length");
        assertThat(gen(new FakeChatLineClient().reply("{\"line\":\"뭐 했어? 밥은 먹었어?\"}"), BigDecimal.ZERO)
                .generate(reply("a")).failReason()).isEqualTo("questions");
        assertThat(gen(new FakeChatLineClient().reply("{\"line\":\"왜 이렇게 늦게 왔어요.\"}"), BigDecimal.ZERO)
                .generate(reply("a")).failReason()).isEqualTo("resent");
        for (String bad : List.of("저녁에 맥주 한 잔 생각나요.", "…요즘 좀 우울해.", "오늘도 청부 일이 있었지.", "주인님, 왔어요?")) {
            assertThat(gen(new FakeChatLineClient().reply("{\"line\":\"" + bad + "\"}"), BigDecimal.ZERO)
                    .generate(reply("a")).failReason()).as(bad).isEqualTo("unsafe");
        }
    }

    @Test
    @DisplayName("★ 일 상한 이상이면 부르지 않는다(돈이 안 나간다)")
    void capStopsBeforeCalling() {
        FakeChatLineClient c = new FakeChatLineClient();
        LineAttempt a = gen(c, new BigDecimal("2.0001")).generate(reply("a"));
        assertThat(a.failReason()).isEqualTo("cap");
        assertThat(a.costUsd()).isZero();
        assertThat(c.prompts).isEmpty();
    }

    @Test
    @DisplayName("사슬 — LLM 실패면 템플릿 대사 + 폴백 사유, 비용·모델은 남긴다. LLM 꺼짐이면 템플릿만")
    void chainFallsBack() {
        LineChain chain = new LineChain(gen(new FakeChatLineClient().fail(new TimeoutException()), BigDecimal.ZERO),
                new TemplateLineGenerator(), null);
        GeneratedLine g = chain.generate(reply("미안해"), 1L);
        assertThat(g.generator()).isEqualTo("template");
        assertThat(g.fallbackReason()).isEqualTo("timeout");
        assertThat(g.model()).isEqualTo("gpt-5-mini");
        assertThat(g.text()).startsWith("…");                      // 수줍음 템플릿
        assertThat(g.motion()).isEqualTo("hello");

        GeneratedLine off = LineChain.templateOnly().generate(reply("미안해"), 1L);
        assertThat(off.generator()).isEqualTo("template");
        assertThat(off.fallbackReason()).isNull();
        assertThat(off.costUsd()).isZero();
    }

    @Test
    @DisplayName("단가 — 모델마다 다르고, 모르는 모델은 비싼 쪽(gpt-5)")
    void prices() {
        assertThat(OpenAiChatLineClient.cost("gpt-5-mini", 1_000_000, 0)).isEqualByComparingTo("0.25");
        assertThat(OpenAiChatLineClient.cost("gpt-5-mini", 0, 1_000_000)).isEqualByComparingTo("2.00");
        assertThat(OpenAiChatLineClient.cost("something-new", 1_000_000, 0)).isEqualByComparingTo("1.25");
    }
}
