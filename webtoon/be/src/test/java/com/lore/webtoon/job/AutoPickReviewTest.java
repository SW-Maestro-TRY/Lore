package com.lore.webtoon.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 서버가 대신 고를 때 이야기 검수 판정을 읽는가(#517).
 *
 * 하네스는 {@code story_review.json} 을 {@code {"candidates": [...]}} 로 쓰는데,
 * 예전에는 최상위를 배열로만 읽어서 판정이 한 번도 반영되지 않았다.
 */
class AutoPickReviewTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("하네스가 쓰는 형태({candidates: [...]})에서 통과한 후보만 고른다")
    void readsCandidatesObject() throws Exception {
        String json = """
                {"candidates": [
                  {"n": 1, "verdict": "통과"},
                  {"n": 2, "verdict": "주의"},
                  {"n": 3, "verdict": "통과"},
                  {"n": 4, "verdict": "없음"}
                ]}
                """;
        assertThat(JobRunner.passedCandidates(mapper.readTree(json))).containsExactly(1, 3);
    }

    @Test
    @DisplayName("최상위가 배열인 것도 계속 읽는다")
    void readsBareArray() throws Exception {
        String json = """
                [{"n": 2, "verdict": "통과"}, {"n": 3, "verdict": "주의"}]
                """;
        assertThat(JobRunner.passedCandidates(mapper.readTree(json))).containsExactly(2);
    }

    @Test
    @DisplayName("모르는 형태면 빈 목록이다 — 그러면 autoPick 이 전부에서 고른다")
    void unknownShapeIsEmpty() throws Exception {
        assertThat(JobRunner.passedCandidates(mapper.readTree("{\"other\": 1}"))).isEmpty();
        assertThat(JobRunner.passedCandidates(null)).isEmpty();
    }
}
