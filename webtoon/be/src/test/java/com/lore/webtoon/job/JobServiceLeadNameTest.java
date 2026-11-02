package com.lore.webtoon.job;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JobServiceLeadNameTest {

    @Test
    @DisplayName("주인공 이름이 바뀌면 입력의 이름만 고친다")
    void 이름만_고친다() {
        String got = JobService.withLeadName("{\"name\":\"미나\",\"genre\":\"판타지\",\"co_names\":[\"도윤\"]}", "나미");
        assertThat(got).contains("\"name\":\"나미\"", "\"genre\":\"판타지\"", "\"co_names\":[\"도윤\"]");
    }

    @Test
    @DisplayName("이름이 그대로거나 입력을 못 읽으면 고치지 않는다")
    void 그대로면_안_고친다() {
        assertThat(JobService.withLeadName("{\"name\":\"미나\"}", "미나")).isNull();
        assertThat(JobService.withLeadName("읽을 수 없는 글", "미나")).isNull();
        assertThat(JobService.withLeadName("{\"name\":\"미나\"}", "")).isNull();
        assertThat(JobService.withLeadName(null, "미나")).isNull();
    }
}
