package com.lore.zzal.chat.line;

import com.lore.zzal.chat.persona.PersonaSheet;
import com.lore.zzal.chat.prompt.ChatContext;
import com.lore.zzal.chat.prompt.HistoryLine;
import com.lore.zzal.chat.prompt.PetState;
import com.lore.zzal.chat.prompt.SystemPromptCache;
import com.lore.zzal.chat.session.SessionKind;
import com.lore.zzal.chat.session.Speaker;
import com.lore.zzal.chat.session.TurnPlan;
import com.lore.zzal.chat.session.TurnType;
import com.lore.zzal.pet.Personality;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("채팅 — LLM 생성기·폴백 사슬·출력 검사")
class LlmLineGeneratorTest {

    static ChatContext cont(TurnType type, int no) {
        PersonaSheet sheet = new PersonaSheet("루아나", List.of(Personality.SHY), null, "현대 · 학교", null,
                "light blue beret", null, false);
        return new ChatContext(7L, sheet, new PetState(DayOfWeek.THURSDAY, 21, 0, 3, 3, 3, 3, false), SessionKind.DAILY,
                new TurnPlan(type, no, false, null, false), "미안해",
                List.of(new HistoryLine(Speaker.PET, "…왔네."), new HistoryLine(Speaker.USER, "오늘 좀 힘들었어")),
                List.of("hello", "joy"));
    }

    static ChatContext reply() {
        return cont(TurnType.CONTINUE, 2);
    }

    private static LlmLineGenerator gen(FakeChatLineClient c, BigDecimal spent) {
        return new LlmLineGenerator(c, "gpt-5-mini", Duration.ofSeconds(4), new BigDecimal("2"), () -> spent,
                new SystemPromptCache());
    }

    @Test
    @DisplayName("시스템·사용자 두 메시지로 부르고, JSON 의 line·motion 을 쓴다. 비용·모델이 남는다")
    void ok() {
        FakeChatLineClient c = new FakeChatLineClient().line("…힘들었구나. 옆에 있을게.", "joy");
        LineAttempt a = gen(c, BigDecimal.ZERO).generate(reply());
        assertThat(a.ok()).isTrue();
        assertThat(a.text()).isEqualTo("…힘들었구나. 옆에 있을게.");
        assertThat(a.motion()).isEqualTo("joy");
        assertThat(a.costUsd()).isEqualByComparingTo(FakeChatLineClient.COST);
        assertThat(c.systems.getFirst()).startsWith("너는 '루아나'다.").contains("[말하는 법]").doesNotContain("[이번 턴]\n종류");
        assertThat(c.users.getFirst()).contains("[이번 턴]", "종류: 이어 말하기").doesNotContain("[말하는 법]");
    }

    @Test
    @DisplayName("목록 밖 동작은 기본값으로, 첫 턴(부름)은 동작 없음. 코드 블록·바깥 따옴표는 벗긴다")
    void motionAndFence() {
        LineAttempt a = gen(new FakeChatLineClient().reply("```json\n{\"line\":\"\\\"…응.\\\"\",\"motion\":\"sleep\"}\n```"),
                BigDecimal.ZERO).generate(reply());
        assertThat(a.ok()).isTrue();
        assertThat(a.text()).isEqualTo("…응.");
        assertThat(a.motion()).isEqualTo("hello");
        LineAttempt first = gen(new FakeChatLineClient().line("…안녕.", "joy"), BigDecimal.ZERO)
                .generate(cont(TurnType.GREETING, 1));
        assertThat(first.motion()).isNull();
    }

    @Test
    @DisplayName("파싱 실패·빈 줄·시간 초과·오류는 사유를 담아 실패, 비용은 남긴다(시간 초과는 추정치)")
    void failures() {
        assertThat(gen(new FakeChatLineClient().reply("그냥 글"), BigDecimal.ZERO).generate(reply()).failReason())
                .isEqualTo("parse");
        LineAttempt blank = gen(new FakeChatLineClient().reply("{\"line\":\"  \"}"), BigDecimal.ZERO).generate(reply());
        assertThat(blank.failReason()).isEqualTo("blank");
        assertThat(blank.costUsd()).isEqualByComparingTo(FakeChatLineClient.COST);
        LineAttempt slow = gen(new FakeChatLineClient().fail(new TimeoutException()), BigDecimal.ZERO).generate(reply());
        assertThat(slow.failReason()).isEqualTo("timeout");
        assertThat(slow.costUsd()).isPositive();
        assertThat(gen(new FakeChatLineClient().fail(new IllegalStateException("HTTP 500")), BigDecimal.ZERO)
                .generate(reply()).failReason()).isEqualTo("error");
    }

    @Test
    @DisplayName("출력 검사 — 60자 초과·괄호(지문)·이모지·질문 둘·원망·민감 소재")
    void filtered() {
        record Case(String line, String reason) {
        }
        for (Case k : List.of(
                new Case("가".repeat(61), "length"),
                new Case("(꼬리를 흔들며) 왔구나!", "bracket"),
                new Case("*폴짝* 안녕!", "bracket"),
                new Case("[웃음] 그렇구나", "bracket"),
                new Case("안녕! 🐰", "emoji"),
                new Case("오늘도 좋아 ✨", "emoji"),
                new Case("좋아 ♡", "emoji"),
                new Case("뭐 했어? 밥은 먹었어?", "questions"),
                new Case("오늘은 뭐 했어?", "asked"),            // 질문 금지 턴에 물음표 하나
                new Case("왜 이렇게 늦게 왔어요.", "resent"),
                new Case("저녁에 맥주 한 잔 생각나요.", "unsafe"),
                new Case("…요즘 좀 우울해.", "unsafe"),
                new Case("주인님, 왔어요.", "unsafe"))) {
            assertThat(gen(new FakeChatLineClient().line(k.line(), "hello"), BigDecimal.ZERO).generate(reply())
                    .failReason()).as(k.line()).isEqualTo(k.reason());
        }
        assertThat(LineFilter.check("…응~ 나도 좋아.", reply())).isNull();
        // 질문 허용 턴이면 물음표 하나는 통과
        ChatContext allowed = new ChatContext(7L, reply().sheet(), reply().state(), SessionKind.DAILY,
                new TurnPlan(TurnType.CONTINUE, 3, true, null, false), null, List.of(), List.of("hello"));
        assertThat(LineFilter.check("심상훈! 이름 길다. 상훈이라고 불러도 돼? 나는 우사기야.", allowed)).isNull();
    }

    @Test
    @DisplayName("★ 일 상한 이상이면 부르지 않는다(돈이 안 나간다)")
    void capStopsBeforeCalling() {
        FakeChatLineClient c = new FakeChatLineClient();
        LineAttempt a = gen(c, new BigDecimal("2.0001")).generate(reply());
        assertThat(a.failReason()).isEqualTo("cap");
        assertThat(a.costUsd()).isZero();
        assertThat(c.users).isEmpty();
    }

    @Test
    @DisplayName("사슬 — LLM 실패면 턴 종류에 맞는 폴백 문형 + 폴백 사유, 비용·모델은 남긴다. LLM 꺼짐이면 템플릿만")
    void chainFallsBack() {
        LineChain chain = new LineChain(gen(new FakeChatLineClient().line("(웃으며) 그렇구나", "hello"), BigDecimal.ZERO),
                new TemplateLineGenerator(), null);
        GeneratedLine g = chain.generate(reply(), 1L);
        assertThat(g.generator()).isEqualTo("template");
        assertThat(g.fallbackReason()).isEqualTo("bracket");
        assertThat(g.model()).isEqualTo("gpt-5-mini");
        assertThat(g.text()).isEqualTo("…그렇구나. 잘 들었어.");   // 수줍음 · 이어 말하기
        assertThat(g.motion()).isEqualTo("hello");

        GeneratedLine close = LineChain.templateOnly().generate(cont(TurnType.CLOSE, 6), 1L);
        assertThat(close.text()).isEqualTo("…나 동네 쪽에 잠깐 있을게. 또 말 걸게.");
        assertThat(close.fallbackReason()).isNull();
        assertThat(close.costUsd()).isZero();
    }

    @Test
    @DisplayName("단가 — 모델마다 다르고, 모르는 모델은 비싼 쪽(gpt-5)")
    void prices() {
        assertThat(OpenAiChatLineClient.cost("gpt-5-mini", 1_000_000, 0)).isEqualByComparingTo("0.25");
        assertThat(OpenAiChatLineClient.cost("gpt-5-mini", 0, 1_000_000)).isEqualByComparingTo("2.00");
        assertThat(OpenAiChatLineClient.cost("something-new", 1_000_000, 0)).isEqualByComparingTo("1.25");
    }
}
