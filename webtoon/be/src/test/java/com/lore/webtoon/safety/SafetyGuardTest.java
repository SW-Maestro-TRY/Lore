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
    @DisplayName("막는 분류에 걸리면 400 과 정해진 한 줄")
    void blockedCategoryThrows() {
        SafetyGuard g = new SafetyGuard(answering(flagged("sexual/minors")), true, false);
        assertThatThrownBy(() -> g.checkText("t", "…"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(SafetyGuard.MESSAGE);
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
