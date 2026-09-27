package com.lore.webtoon.feedback;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설문 답의 허용 값(#471).
 *
 * <b>이 검사가 지키는 것은 「집계할 때 값이 정해진 몇 가지로만 나오는가」 다.</b>
 * 화면이 보낸 값을 그대로 저장하면 오타 · 다른 언어 문구 · 지어낸 값이 섞여서 세지를 못 한다.
 */
class WebtoonFeedbackQuestionTest {

    @Test
    @DisplayName("점수 문항은 1~5 정수만 받는다")
    void scale() {
        assertThat(WebtoonFeedbackQuestion.S1.accept(4, false)).isEqualTo(4);
        assertThat(WebtoonFeedbackQuestion.S1.accept(0, false)).isNull();
        assertThat(WebtoonFeedbackQuestion.S1.accept(6, false)).isNull();
        assertThat(WebtoonFeedbackQuestion.S1.accept(3.5, false)).isNull();
        assertThat(WebtoonFeedbackQuestion.S1.accept("4", false)).isNull();
    }

    @Test
    @DisplayName("선택 문항은 정해진 기호만 받는다")
    void choice() {
        assertThat(WebtoonFeedbackQuestion.S3.accept("partly", false)).isEqualTo("partly");
        assertThat(WebtoonFeedbackQuestion.S7.accept("partly", false)).isNull();
        assertThat(WebtoonFeedbackQuestion.S8.accept("maybe", false)).isEqualTo("maybe");
        assertThat(WebtoonFeedbackQuestion.S9.accept("일부", false)).isNull();
    }

    @Test
    @DisplayName("「해당 없음」은 전체 설문의 넣은 것이 필요한 문항에서만")
    void notApplicable() {
        assertThat(WebtoonFeedbackQuestion.S4.accept("na", true)).isEqualTo("na");
        assertThat(WebtoonFeedbackQuestion.S4.accept("na", false)).isNull();
        assertThat(WebtoonFeedbackQuestion.S6.accept("na", true)).isNull();
    }

    @Test
    @DisplayName("원하는 기능은 여러 개, 모르는 기호는 빼고 겹치면 한 번만")
    void wants() {
        assertThat(WebtoonFeedbackQuestion.S10.accept(List.of("multi_char", "next_episode", "multi_char", "fly"), true))
                .isEqualTo(List.of("multi_char", "next_episode"));
        assertThat(WebtoonFeedbackQuestion.S10.accept(List.of("fly"), true)).isNull();
        assertThat(WebtoonFeedbackQuestion.S10.accept("multi_char", true)).isNull();
    }
}
