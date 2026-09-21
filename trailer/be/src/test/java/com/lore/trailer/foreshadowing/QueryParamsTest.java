package com.lore.trailer.foreshadowing;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 회차 · 쪽 · 크기 읽기 — 틀린 값은 500 이 아니라 400 이고, 회차만 코드가 따로다.
 */
@DisplayName("쿼리 인자 — 회차 · 쪽 · 크기")
class QueryParamsTest {

    private static void assertRejected(ErrorCode expected, Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    // ── 회차 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("회차는 숫자 그대로 — 앞뒤 빈칸은 뗀다")
    void chapter() {
        assertThat(QueryParams.chapter("1")).isEqualTo(1);
        assertThat(QueryParams.chapter(" 200 ")).isEqualTo(200);
        assertThat(QueryParams.chapter("400")).isEqualTo(400);
    }

    @ParameterizedTest(name = "chapter=\"{0}\" → TRAILER_INVALID_CHAPTER")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "abc", "0", "-1", "1.5", "1e2", "99999999999", "1,2"})
    @DisplayName("★ 없거나 숫자가 아니거나 1보다 작으면 400 TRAILER_INVALID_CHAPTER")
    void chapterRejected(String raw) {
        assertRejected(ErrorCode.TRAILER_INVALID_CHAPTER, () -> QueryParams.chapter(raw));
    }

    // ── 쪽 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("쪽은 0부터. 없으면 0")
    void page() {
        assertThat(QueryParams.page(null)).isZero();
        assertThat(QueryParams.page("")).isZero();
        assertThat(QueryParams.page("0")).isZero();
        assertThat(QueryParams.page("7")).isEqualTo(7);
    }

    @ParameterizedTest(name = "page=\"{0}\" → INVALID_INPUT")
    @ValueSource(strings = {"-1", "x", "1.0"})
    @DisplayName("쪽이 틀리면 400 INVALID_INPUT — 회차 코드가 아니다")
    void pageRejected(String raw) {
        assertRejected(ErrorCode.INVALID_INPUT, () -> QueryParams.page(raw));
    }

    // ── 크기 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("크기는 기본 50, 최대 100")
    void size() {
        assertThat(QueryParams.size(null)).isEqualTo(50);
        assertThat(QueryParams.size("")).isEqualTo(50);
        assertThat(QueryParams.size("1")).isEqualTo(1);
        assertThat(QueryParams.size("100")).isEqualTo(100);
    }

    @ParameterizedTest(name = "size=\"{0}\" → INVALID_INPUT")
    @ValueSource(strings = {"0", "101", "-5", "abc"})
    @DisplayName("크기가 0 이하거나 100 을 넘으면 400 INVALID_INPUT")
    void sizeRejected(String raw) {
        assertRejected(ErrorCode.INVALID_INPUT, () -> QueryParams.size(raw));
    }
}
