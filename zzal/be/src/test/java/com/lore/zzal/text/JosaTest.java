package com.lore.zzal.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 조사 고르기.
 *
 * ★ 여기서 지키는 것은 <b>사용자가 지은 이름</b>이다. 이름은 우리가 못 고르는 입력이라, 한 규칙으로
 *   전부 맞게 나와야 한다. "보리이가 자고 있어요" 는 문법 오류지만 예외도 로그도 안 남는다 —
 *   화면에 뜬 것을 사람이 읽어야만 드러난다(2026-09-05 프론트 연결에서 그렇게 발견됐다).
 */
@DisplayName("조사 — 이름에 맞는 은/는·이/가")
class JosaTest {

    @ParameterizedTest(name = "{0} → 받침 {1}")
    @CsvSource({
            "밤톨, true",       // ㄹ 받침
            "여울, true",
            "보리, false",      // 받침 없음
            "여우, false",
            "구름, true",       // ㅁ 받침
            "바다, false",
            "Tom, true",        // 영문 자음
            "Bori, false",      // 영문 모음
            "MAX, true",
            "코코, false"
    })
    @DisplayName("받침 판정 — 한글 종성·영문 마지막 글자")
    void finalConsonant(String name, boolean expected) {
        assertThat(Josa.hasFinalConsonant(name)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} → 숫자 받침 {1}")
    @CsvSource({"0, true", "1, true", "3, true", "6, true", "7, true", "8, true",
            "2, false", "4, false", "5, false", "9, false"})
    @DisplayName("숫자는 읽는 소리로 — 영·일·삼·육·칠·팔은 받침")
    void digits(String name, boolean expected) {
        assertThat(Josa.hasFinalConsonant("펫" + name)).isEqualTo(expected);
    }

    @Test
    @DisplayName("★ 이름 뒤의 \"이\" — 받침 있으면 붙고 없으면 안 붙는다")
    void nameParticles() {
        assertThat(Josa.nameSubject("밤톨")).isEqualTo("밤톨이가");
        assertThat(Josa.nameSubject("보리")).isEqualTo("보리가");
        assertThat(Josa.nameTopic("밤톨")).isEqualTo("밤톨이는");
        assertThat(Josa.nameTopic("보리")).isEqualTo("보리는");
        assertThat(Josa.nameObject("밤톨")).isEqualTo("밤톨이를");
        assertThat(Josa.nameObject("보리")).isEqualTo("보리를");
        assertThat(Josa.nameSubject("Tom")).isEqualTo("Tom이가");
        assertThat(Josa.nameSubject("Bori")).isEqualTo("Bori가");
    }

    @Test
    @DisplayName("일반 조사 고르기 — of(낱말, 받침용, 없을 때용)")
    void generic() {
        assertThat(Josa.of("밤톨", "은", "는")).isEqualTo("은");
        assertThat(Josa.of("보리", "은", "는")).isEqualTo("는");
        assertThat(Josa.of("밥", "을", "를")).isEqualTo("을");
        assertThat(Josa.of("간식", "을", "를")).isEqualTo("을");
        assertThat(Josa.of("목욕", "을", "를")).isEqualTo("을");
        assertThat(Josa.of("놀이", "을", "를")).isEqualTo("를");
    }

    @Test
    @DisplayName("★ 이모지·결합문자는 건너뛰고 앞 글자로 판정한다")
    void skipsSymbols() {
        assertThat(Josa.nameTopic("밤톨🐣")).isEqualTo("밤톨🐣이는");
        assertThat(Josa.nameSubject("보리🐣")).isEqualTo("보리🐣가");
        assertThat(Josa.nameSubject("밤톨!")).isEqualTo("밤톨!이가");
        assertThat(Josa.nameSubject("Tom~")).isEqualTo("Tom~이가");
    }

    @Test
    @DisplayName("★ 자모가 풀린 이름(NFD)도 같은 판정 — 맥 파일명·일부 IME 가 이 형태를 낸다")
    void normalizesToNfc() {
        String nfd = java.text.Normalizer.normalize("밤톨", java.text.Normalizer.Form.NFD);
        assertThat(nfd).isNotEqualTo("밤톨");                       // 실제로 다른 문자열이다
        assertThat(Josa.hasFinalConsonant(nfd)).isTrue();
        assertThat(Josa.hasFinalConsonant(
                java.text.Normalizer.normalize("보리", java.text.Normalizer.Form.NFD))).isFalse();
    }

    @Test
    @DisplayName("★ 빈 이름·이모지에도 터지지 않는다 — 거절 메시지를 만들다가 500이 나가면 안 된다")
    void neverThrows() {
        assertThat(Josa.hasFinalConsonant(null)).isFalse();
        assertThat(Josa.hasFinalConsonant("")).isFalse();
        assertThat(Josa.hasFinalConsonant("   ")).isFalse();
        assertThat(Josa.nameSubject("🐣")).isEqualTo("🐣가");
        assertThat(Josa.nameTopic("여울 ")).isEqualTo("여울이는");   // 뒤 공백은 무시하고 판정
    }
}
