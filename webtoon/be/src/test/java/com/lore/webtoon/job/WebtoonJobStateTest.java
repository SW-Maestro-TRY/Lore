package com.lore.webtoon.job;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WebtoonJobStateTest {

    private static WebtoonJob fresh() {
        return WebtoonJob.queued("job-1", 1L, "uid", null, "noir", "surf", true, "{}", Instant.now());
    }

    @Test
    void 끊긴_것으로_적힌_작업이_끝까지_가면_실패_사유가_지워진다() {
        WebtoonJob job = fresh();
        job.moveTo(JobStatus.RUNNING, JobStage.PAGES, Instant.now());
        job.failed("서버가 다시 시작되어 만들기가 끊겼습니다", null, Instant.now());

        job.moveTo(JobStatus.DONE, JobStage.BIND, Instant.now());

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(job.getError()).isNull();
    }

    @Test
    void 실패는_사유를_그대로_둔다() {
        WebtoonJob job = fresh();
        job.failed("이야기 후보를 만들지 못했습니다", null, Instant.now());

        assertThat(job.getError()).isEqualTo("이야기 후보를 만들지 못했습니다");
    }

    @Test
    void 예시_작업은_처음부터_끝난_것이라_줄에_서지_않는다() {
        WebtoonJob job = WebtoonJob.seeded("example-r1", "lore-example-seed", "noir", "{}", Instant.now());

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(job.getStatus().isOver()).isTrue();
        assertThat(job.getFinishedAt()).isNotNull();
    }
}
