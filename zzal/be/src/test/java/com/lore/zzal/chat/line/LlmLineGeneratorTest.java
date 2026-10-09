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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("채팅 — LLM 생성기·실패 사슬(재호출 1회 → 중립 닫는 말)")
class LlmLineGeneratorTest {

    static ChatContext cont(TurnType type, int no) {
        PersonaSheet sheet = new PersonaSheet("루아나", List.of(Personality.SHY), null, "현대 · 학교", null, null, false);
        return new ChatContext(7L, sheet, new PetState(DayOfWeek.THURSDAY, 21, 0, 3, 3, 3, 3, false), SessionKind.DAILY,
                new TurnPlan(type, no, false, null, false), null,
                List.of(new HistoryLine(Speaker.PET, "…왔네."), new HistoryLine(Speaker.USER, "오늘 좀 힘들었어")),
                List.of("hello", "joy"));
    }

    static ChatContext reply() {
        return cont(TurnType.CONTINUE, 2);
    }

    private static LlmLineGenerator gen(FakeChatLineClient c) {
        return new LlmLineGenerator(c, "gpt-5-mini", Duration.ofSeconds(4), new SystemPromptCache());
    }

    @Test
    @DisplayName("시스템·사용자 두 메시지로 부르고, JSON 의 line·motion·추출 칸을 쓴다. 비용·모델이 남는다")
    void ok() {
        FakeChatLineClient c = new FakeChatLineClient().full("…힘들었구나. 옆에 있을게.", "joy", "상훈", "학교가 힘들었다", true);
        LineAttempt a = gen(c).generate(reply());
        assertThat(a.ok()).isTrue();
        assertThat(a.text()).isEqualTo("…힘들었구나. 옆에 있을게.");
        assertThat(a.motion()).isEqualTo("joy");
        assertThat(a.extract()).isEqualTo(new LineExtract("상훈", "학교가 힘들었다", true));
        assertThat(a.costUsd()).isEqualByComparingTo(FakeChatLineClient.COST);
        assertThat(c.systems.getFirst()).startsWith("너는 '루아나'다.").contains("[말하는 법]", "\"call_me\"", "\"user_said\"",
                "\"asked_back\"").doesNotContain("[이번 턴]\n종류");
        assertThat(c.users.getFirst()).contains("[이번 턴]", "종류: 이어 말하기").doesNotContain("[말하는 법]");
    }

    @Test
    @DisplayName("추출 칸 — 없음·JSON null·글자 \"null\"·빈 글은 null, asked_back 없으면 false")
    void extractNulls() {
        LineAttempt a = gen(new FakeChatLineClient().reply(
                "{\"line\":\"응\",\"call_me\":\"null\",\"user_said\":\"  \"}")).generate(reply());
        assertThat(a.extract()).isEqualTo(LineExtract.NONE);
        LineAttempt b = gen(new FakeChatLineClient().full("응", "hello", null, null, false)).generate(reply());
        assertThat(b.extract()).isEqualTo(LineExtract.NONE);
    }

    @Test
    @DisplayName("목록 밖 동작은 기본값으로, 첫 턴(부름)은 동작 없음. 코드 블록·바깥 따옴표는 벗긴다")
    void motionAndFence() {
        LineAttempt a = gen(new FakeChatLineClient().reply("```json\n{\"line\":\"\\\"…응.\\\"\",\"motion\":\"sleep\"}\n```"))
                .generate(reply());
        assertThat(a.ok()).isTrue();
        assertThat(a.text()).isEqualTo("…응.");
        assertThat(a.motion()).isEqualTo("hello");
        LineAttempt first = gen(new FakeChatLineClient().line("…안녕.", "joy")).generate(cont(TurnType.GREETING, 1));
        assertThat(first.motion()).isNull();
    }

    @Test
    @DisplayName("파싱 실패·빈 줄·시간 초과·오류는 사유를 담아 실패, 비용은 남긴다(시간 초과는 추정치)")
    void failures() {
        assertThat(gen(new FakeChatLineClient().reply("그냥 글")).generate(reply()).failReason()).isEqualTo("parse");
        assertThat(gen(new FakeChatLineClient().reply("[1,2]")).generate(reply()).failReason()).isEqualTo("parse");
        LineAttempt blank = gen(new FakeChatLineClient().reply("{\"line\":\"  \"}")).generate(reply());
        assertThat(blank.failReason()).isEqualTo("blank");
        assertThat(blank.costUsd()).isEqualByComparingTo(FakeChatLineClient.COST);
        LineAttempt slow = gen(new FakeChatLineClient().fail(new TimeoutException())).generate(reply());
        assertThat(slow.failReason()).isEqualTo("timeout");
        assertThat(slow.costUsd()).isPositive();
        assertThat(gen(new FakeChatLineClient().fail(new IllegalStateException("HTTP 500"))).generate(reply())
                .failReason()).isEqualTo("error");
    }

    @Test
    @DisplayName("★ 내용 필터 없음(#709) — 길이·괄호·이모지·질문 둘·질문 금지 턴의 물음표·금칙어 모두 그대로 통과")
    void noContentFilter() {
        for (String line : List.of("가".repeat(61), "(꼬리를 흔들며) 왔구나!", "안녕! 🐰", "뭐 했어? 밥은 먹었어?",
                "오늘은 뭐 했어?", "왜 이렇게 늦게 왔어요.", "저녁에 맥주 한 잔 생각나요.")) {
            LineAttempt a = gen(new FakeChatLineClient().line(line, "hello")).generate(reply());
            assertThat(a.ok()).as(line).isTrue();
            assertThat(a.text()).isEqualTo(line);
        }
    }

    @Test
    @DisplayName("★★ 사슬 — 첫 호출 성공 ok · 실패 뒤 재호출 성공 retried_ok(첫 사유·비용 합) · 둘 다 실패 failed_closed")
    void chainOutcomes() {
        GeneratedLine ok = ChatChains.fake(new FakeChatLineClient().line("응.", "joy")).generate(reply(), 1L);
        assertThat(ok.outcome()).isEqualTo(LineOutcome.OK);
        assertThat(ok.attempts()).isEqualTo(1);
        assertThat(ok.failReason()).isNull();
        assertThat(ok.closesSession()).isFalse();

        FakeChatLineClient c = new FakeChatLineClient().reply("깨진 글").line("그렇구나.", "hello");
        GeneratedLine retried = ChatChains.fake(c).generate(reply(), 1L);
        assertThat(retried.outcome()).isEqualTo(LineOutcome.RETRIED_OK);
        assertThat(retried.text()).isEqualTo("그렇구나.");
        assertThat(retried.failReason()).isEqualTo("parse");
        assertThat(retried.attempts()).isEqualTo(2);
        assertThat(retried.costUsd()).isEqualByComparingTo(FakeChatLineClient.COST.multiply(BigDecimal.TWO));
        assertThat(c.users).hasSize(2);

        FakeChatLineClient d = new FakeChatLineClient().fail(new TimeoutException()).reply("{\"line\":\"\"}");
        GeneratedLine closed = ChatChains.fake(d).generate(reply(), 1L);
        assertThat(closed.outcome()).isEqualTo(LineOutcome.FAILED_CLOSED);
        assertThat(closed.closesSession()).isTrue();
        assertThat(closed.text()).isEqualTo(LineChain.CLOSING_LINE);
        assertThat(closed.generator()).isEqualTo(LineChain.FIXED);
        assertThat(closed.failReason()).isEqualTo("timeout/blank");
        assertThat(closed.motion()).isEqualTo("hello");
        assertThat(closed.extract()).isEqualTo(LineExtract.NONE);
        assertThat(d.users).as("재호출은 1회뿐").hasSize(2);
        // 판의 첫 턴이면 동작 없음
        GeneratedLine closedFirst = ChatChains.fake(new FakeChatLineClient().reply("x").reply("y"))
                .generate(cont(TurnType.GREETING, 1), 1L);
        assertThat(closedFirst.motion()).isNull();
    }

    @Test
    @DisplayName("★ 160자(DB 칸) 초과는 잘라 저장 — truncated. 160자 딱이면 ok")
    void chainTruncates() {
        String l161 = "가".repeat(161);
        GeneratedLine g = ChatChains.fake(new FakeChatLineClient().line(l161, "hello")).generate(reply(), 1L);
        assertThat(g.outcome()).isEqualTo(LineOutcome.TRUNCATED);
        assertThat(g.text()).hasSize(160);
        String l160 = "가".repeat(160);
        assertThat(ChatChains.fake(new FakeChatLineClient().line(l160, "hello")).generate(reply(), 1L).outcome())
                .isEqualTo(LineOutcome.OK);
        // 이모지(서로게이트 쌍)는 글자 하나로 센다 — 반쪽이 남지 않는다
        String emoji = "🐰".repeat(161);
        GeneratedLine e = ChatChains.fake(new FakeChatLineClient().line(emoji, "hello")).generate(reply(), 1L);
        assertThat(e.text().codePointCount(0, e.text().length())).isEqualTo(160);
        assertThat(e.outcome()).isEqualTo(LineOutcome.TRUNCATED);
    }

    @Test
    @DisplayName("LLM 꺼진 사슬은 대사를 내지 않는다 — 부르면 예외(부르는 쪽이 판을 만들지 않아야 한다)")
    void offChainRefuses() {
        assertThat(LineChain.off().llmEnabled()).isFalse();
        assertThatThrownBy(() -> LineChain.off().generate(reply(), 1L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("단가 — 모델마다 다르고, 모르는 모델은 비싼 쪽(gpt-5)")
    void prices() {
        assertThat(OpenAiChatLineClient.cost("gpt-5-mini", 1_000_000, 0)).isEqualByComparingTo("0.25");
        assertThat(OpenAiChatLineClient.cost("gpt-5-mini", 0, 1_000_000)).isEqualByComparingTo("2.00");
        assertThat(OpenAiChatLineClient.cost("something-new", 1_000_000, 0)).isEqualByComparingTo("1.25");
    }
}
