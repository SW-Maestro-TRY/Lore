package com.lore.trailer.foreshadowing;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 검색어 나누기 — 화면의 {@code matchesCard}(trailer/fe/lib/search.ts)와 같은 답을 내야 한다.
 */
@DisplayName("검색어 — T 번호 · 단어 나누기 · 상한")
class SearchTermsTest {

    @ParameterizedTest(name = "\"{0}\" 은 빈 검색어")
    @ValueSource(strings = {"", " ", "\t\n", "　"})
    @DisplayName("비었거나 빈칸뿐이면 거르지 않는다 — 전각 빈칸(U+3000)도 빈칸이다")
    void blankIsEmpty(String raw) {
        assertThat(SearchTerms.parse(raw).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("null 도 빈 검색어")
    void nullIsEmpty() {
        assertThat(SearchTerms.parse(null).isEmpty()).isTrue();
    }

    @ParameterizedTest(name = "\"{0}\" → T12")
    @ValueSource(strings = {"T12", "t12", " T12 ", "\tt12\n"})
    @DisplayName("★ T 번호 꼴이면 그 카드 한 장 — 대소문자와 앞뒤 빈칸을 가리지 않는다")
    void threadId(String raw) {
        SearchTerms terms = SearchTerms.parse(raw);

        assertThat(terms.threadId()).contains("T12");
        assertThat(terms.words()).isEmpty();
        assertThat(terms.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("T 번호에 다른 말이 붙으면 단어 검색이다 — \"T12 루피\" 는 두 단어")
    void threadIdWithWordsIsWordSearch() {
        SearchTerms terms = SearchTerms.parse("T12 루피");

        assertThat(terms.threadId()).isEmpty();
        assertThat(terms.words()).containsExactly("t12", "루피");
    }

    @Test
    @DisplayName("\"T012\" 는 T 번호로 읽되 T12 가 아니다 — 화면도 그랬다")
    void leadingZeroIsKept() {
        assertThat(SearchTerms.parse("T012").threadId()).contains("T012");
    }

    @Test
    @DisplayName("단어는 소문자로 바꾸고, 빈칸 여러 개도 한 번에 나눈다")
    void words() {
        SearchTerms terms = SearchTerms.parse("  Luffy   SHANKS\t약속 ");

        assertThat(terms.threadId()).isEmpty();
        assertThat(terms.words()).containsExactly("luffy", "shanks", "약속");
    }

    @Test
    @DisplayName("normalize — 소문자 · 빈칸 없음. \"밀짚 모자\" 로 \"밀짚모자\" 를 찾는 규칙")
    void normalize() {
        assertThat(SearchTerms.normalize("Monkey D. Luffy")).isEqualTo("monkeyd.luffy");
        assertThat(SearchTerms.normalize(" 밀짚　모자 ")).isEqualTo("밀짚모자");
        assertThat(SearchTerms.normalize("12화")).isEqualTo("12화");
    }

    @Test
    @DisplayName("★ 200자까지 — 201자는 400 INVALID_INPUT")
    void lengthLimit() {
        assertThat(SearchTerms.parse("a".repeat(SearchTerms.MAX_LENGTH)).words()).containsExactly("a".repeat(200));

        assertThatThrownBy(() -> SearchTerms.parse("a".repeat(SearchTerms.MAX_LENGTH + 1)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    @DisplayName("★ 단어 10개까지 — 11개는 400 INVALID_INPUT")
    void wordLimit() {
        String ten = String.join(" ", java.util.Collections.nCopies(SearchTerms.MAX_WORDS, "w"));
        assertThat(SearchTerms.parse(ten).words()).hasSize(10);

        assertThatThrownBy(() -> SearchTerms.parse(ten + " w"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    @DisplayName("LIKE 의 % · _ · \\ 를 글자 그대로 찾게 가린다")
    void escapeLike() {
        assertThat(ForeshadowingSpecs.escapeLike("50%")).isEqualTo("50\\%");
        assertThat(ForeshadowingSpecs.escapeLike("a_b")).isEqualTo("a\\_b");
        assertThat(ForeshadowingSpecs.escapeLike("c:\\d")).isEqualTo("c:\\\\d");
        assertThat(ForeshadowingSpecs.escapeLike("루피")).isEqualTo("루피");
    }
}
