package com.lore.webtoon.safety;

import com.lore.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 판정 규칙이 전부다 — 어느 분류를 막고, 못 물어봤을 때 어느 쪽으로 넘어지는가.
 */
class SafetyGuardTest {

    private static ModerationClient answering(ModerationClient.Verdict v) {
        return text -> v;
    }

    private static ModerationClient.Verdict flagged(String... cats) {
        Map<String, Boolean> m = new java.util.LinkedHashMap<>();
        for (String c : cats) {
            m.put(c, true);
        }
        return new ModerationClient.Verdict(true, m, Map.of());
    }

    @Test
    @DisplayName("깨끗하면 그냥 지나간다")
    void cleanPasses() {
        SafetyGuard g = new SafetyGuard(answering(ModerationClient.Verdict.clean()), true, false);
        assertThatCode(() -> g.checkText("t", "몽이", "강아지 그대로 로판 악역 영애")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("막는 분류에 걸리면 400 — 정해진 안내가 앞에 그대로 있다(화면 번역 키)")
    void blockedCategoryThrows() {
        SafetyGuard g = new SafetyGuard(answering(flagged("sexual/minors")), true, false);
        assertThatThrownBy(() -> g.checkText("t", "…"))
                .isInstanceOf(BusinessException.class)
                .hasMessageStartingWith(SafetyGuard.MESSAGE)
                .hasMessageContaining("이유: 미성년자를 성적으로 다루는 표현");
    }

    @Test
    @DisplayName("걸리면 어느 칸의 어느 문장인지 알려 준다 — 걸린 칸만, 걸린 문장만")
    void locatesFieldAndSentence() {
        ModerationClient byWord = text -> text.contains("문제") ? flagged("violence/graphic") : ModerationClient.Verdict.clean();
        SafetyGuard g = new SafetyGuard(byWord, true, false);
        assertThatThrownBy(() -> g.checkFields("t",
                SafetyGuard.Field.of("이름", "몽이"),
                SafetyGuard.Field.of("이야기", "평범하게 시작한다. 여기가 문제의 문장이다! 그리고 끝난다.")))
                .isInstanceOf(BusinessException.class)
                .hasMessageStartingWith(SafetyGuard.MESSAGE)
                .hasMessageContaining("걸린 곳: 「이야기」 칸 「여기가 문제의 문장이다!」")
                .hasMessageContaining("이유: 잔혹한 폭력 묘사")
                .hasMessageNotContaining("몽이")
                .hasMessageNotContaining("평범하게");
    }

    @Test
    @DisplayName("금지어에 걸리면 그 말이 든 칸과 문장을 알려 준다 — 금지어 목록은 안 보인다")
    void bannedWordSpot() {
        SafetyGuard g = new SafetyGuard(answering(ModerationClient.Verdict.clean()), true, false);
        assertThatThrownBy(() -> g.checkFields("t",
                SafetyGuard.Field.of("캐릭터 설명", "밝은 성격이다."),
                SafetyGuard.Field.of("추가 설정", "첫 문장. 둘이 성관계를 맺는 장면을 넣어 줘.")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("「추가 설정」 칸 「둘이 성관계를 맺는 장면을 넣어 줘.」")
                .hasMessageContaining("이유: 선정적인 표현")
                .hasMessageNotContaining("첫 문장");
    }

    @Test
    @DisplayName("깨끗한 글은 전처럼 한 번만 묻는다 — 자리를 찾는 물음은 걸렸을 때만")
    void cleanAsksOnce() {
        java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
        SafetyGuard g = new SafetyGuard(text -> { asked.incrementAndGet(); return ModerationClient.Verdict.clean(); }, true, false);
        g.checkFields("t", SafetyGuard.Field.of("이름", "몽이"), SafetyGuard.Field.of("이야기", "한 문장. 두 문장."));
        assertThat(asked.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("문장 나누기 — 마침표·물음표·느낌표·줄바꿈")
    void splitsSentences() {
        assertThat(SafetyGuard.sentences("하나다. 둘이야? 셋!\n넷"))
                .containsExactly("하나다.", "둘이야?", "셋!", "넷");
    }

    @Test
    @DisplayName("OpenAI 응답의 results 여러 개를 같은 순서로 읽는다")
    void parsesManyResults() throws Exception {
        OpenAiModerationClient c = new OpenAiModerationClient("k", 8);
        List<ModerationClient.Verdict> vs = c.parseAll("""
                {"results":[{"flagged":false,"categories":{"sexual":false}},
                            {"flagged":true,"categories":{"sexual":true}}]}""");
        assertThat(vs).hasSize(2);
        assertThat(SafetyGuard.hits(vs.get(0))).isEmpty();
        assertThat(SafetyGuard.hits(vs.get(1))).containsExactly("sexual");
    }

    @Test
    @DisplayName("평범한 폭력(violence)은 안 막고, 잔혹 묘사(violence/graphic)만 막는다")
    void plainViolenceAllowed() {
        assertThat(SafetyGuard.hits(flagged("violence"))).isEmpty();
        assertThat(SafetyGuard.hits(flagged("violence", "violence/graphic"))).containsExactly("violence/graphic");
    }

    @Test
    @DisplayName("빈 칸만 있으면 묻지도 않는다")
    void blankFieldsSkipCall() {
        AtomicReference<String> asked = new AtomicReference<>();
        SafetyGuard g = new SafetyGuard(text -> { asked.set(text); return ModerationClient.Verdict.clean(); }, true, false);
        g.checkText("t", null, "", "   ");
        assertThat(asked.get()).isNull();
    }

    @Test
    @DisplayName("여러 칸을 한 번에 묻는다")
    void fieldsJoined() {
        AtomicReference<String> asked = new AtomicReference<>();
        SafetyGuard g = new SafetyGuard(text -> { asked.set(text); return ModerationClient.Verdict.clean(); }, true, false);
        g.checkText("t", "이름", null, "설명");
        assertThat(asked.get()).isEqualTo("이름\n설명");
    }

    @Test
    @DisplayName("못 물어봤으면 기본은 통과(fail-open)")
    void unavailableFailsOpen() {
        ModerationClient down = text -> { throw new ModerationClient.ModerationUnavailable("down", null); };
        SafetyGuard g = new SafetyGuard(down, true, false);
        assertThatCode(() -> g.checkText("t", "아무 글")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("fail-closed 면 못 물어봤을 때 막는다 — 사용자 문구는 검사 문구가 아니라 잠시 뒤 안내")
    void unavailableFailsClosedWhenAsked() {
        ModerationClient down = text -> { throw new ModerationClient.ModerationUnavailable("down", null); };
        SafetyGuard g = new SafetyGuard(down, true, true);
        assertThatThrownBy(() -> g.checkText("t", "아무 글"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("잠시 뒤");
    }

    @Test
    @DisplayName("꺼져 있으면 아무것도 안 한다")
    void disabled() {
        SafetyGuard g = new SafetyGuard(answering(flagged("sexual")), false, true);
        assertThatCode(() -> g.checkText("t", "…")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("OpenAI 응답을 읽는다")
    void parsesOpenAiBody() throws Exception {
        OpenAiModerationClient c = new OpenAiModerationClient("k", 8);
        ModerationClient.Verdict v = c.parse("""
                {"id":"modr-1","model":"omni-moderation-latest","results":[{"flagged":true,
                 "categories":{"sexual":false,"hate":true,"violence":false},
                 "category_scores":{"sexual":0.01,"hate":0.93,"violence":0.02}}]}""");
        assertThat(v.flagged()).isTrue();
        assertThat(v.categories()).containsEntry("hate", true).containsEntry("sexual", false);
        assertThat(v.scores().get("hate")).isEqualTo(0.93);
        assertThat(SafetyGuard.hits(v)).isEqualTo(List.of("hate"));
    }

    @Test
    @DisplayName("열쇠가 없으면 「못 물어봄」이다 — 통과로 속이지 않는다")
    void noKeyIsUnavailable() {
        OpenAiModerationClient c = new OpenAiModerationClient("", 8);
        assertThat(c.ready()).isFalse();
        assertThatThrownBy(() -> c.moderate("x")).isInstanceOf(ModerationClient.ModerationUnavailable.class);
    }
}
