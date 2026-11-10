package com.lore.webtoon.job;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 그림 안전 검사에 걸린 장의 이유 — 화면은 코드(kindsOf)로 언어마다 이름을 붙이고, 메일은 한국어(reasonOf)를 쓴다.
 * 두 길이 같은 분류표에서 나와야 한국어 화면과 다른 언어 화면의 이유가 어긋나지 않는다.
 */
class UnsafeKindsTest {

    @Test
    @DisplayName("분류 원문을 이유 코드로 — 겹치는 것은 한 번, 모르는 것은 뺀다")
    void 코드로_바꾼다() {
        assertThat(JobRunner.kindsOf(List.of("sexual", "sexual/minors", "violence/graphic", "gore", "something")))
                .containsExactly("sexual", "minor", "violence");
        assertThat(JobRunner.kindsOf(List.of("self_harm", "hate", "harassment", "illicit")))
                .containsExactly("self_harm", "hate", "harassment", "illicit");
        assertThat(JobRunner.kindsOf(List.of())).isEmpty();
    }

    @Test
    @DisplayName("한국어 이유는 코드에서 만든다 — 모르면 「안전 기준」")
    void 한국어_이유() {
        assertThat(JobRunner.reasonOf(List.of("sexual", "violence"))).isEqualTo("선정성·폭력·잔혹");
        assertThat(JobRunner.reasonOf(List.of("unknown"))).isEqualTo("안전 기준");
        for (String code : List.of("minor", "sexual", "self_harm", "violence", "hate", "harassment", "illicit")) {
            assertThat(JobRunner.KIND_KO).containsKey(code);
        }
    }
}
