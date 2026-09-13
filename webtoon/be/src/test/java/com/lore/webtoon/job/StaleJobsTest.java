package com.lore.webtoon.job;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 서버가 죽을 때 돌던 작업을 기동할 때 치우는 자리.
 *
 * <b>안 치우면 조용히 셋이 나빠진다</b> — 줄이 부풀고, 만든 사람은 영영
 * 기다리고, 값이 안 돌아온다. 2026-09-13 에 엿새 된 유령을 실제로 찾았다.
 */
class StaleJobsTest {

    private WebtoonJobRepository jobs;
    private JobRunner runner;
    private StaleJobs stale;

    @BeforeEach
    void setUp() {
        jobs = mock(WebtoonJobRepository.class);
        runner = mock(JobRunner.class);
        when(runner.refund(anyLong())).thenReturn(Refunded.NONE);
        stale = new StaleJobs(jobs, runner);
    }

    private WebtoonJob running(String id) {
        WebtoonJob job = WebtoonJob.queued(id, 7L, "uid", null, "webtoon_lock_bg",
                WebtoonQuality.DEFAULT_QUALITY, false, "{}", Instant.parse("2026-09-07T12:00:00Z"));
        job.moveTo(JobStatus.RUNNING, JobStage.PAGES, Instant.parse("2026-09-07T12:01:00Z"));
        return job;
    }

    @Test
    @DisplayName("돌던 것으로 남은 작업을 끊긴 것으로 적는다")
    void 끊긴_것으로_적는다() {
        WebtoonJob ghost = running("ghost-1");
        when(jobs.findByStatusOrderByIdAsc(JobStatus.RUNNING)).thenReturn(List.of(ghost));

        stale.sweep();

        assertThat(ghost.getStatus()).isEqualTo(JobStatus.ERROR);
        assertThat(ghost.getError()).isEqualTo(StaleJobs.WHY);
        /* 끝난 때가 찍혀야 한다 — 안 찍히면 "얼마나 걸렸나" 를 재는 쪽에서
           이 작업만 영원히 안 끝난 것으로 남는다. */
        assertThat(ghost.getFinishedAt()).isNotNull();
        verify(jobs).save(ghost);
    }

    @Test
    @DisplayName("값을 돌려준다 — 우리 쪽 사정으로 끊긴 것이다")
    void 값을_돌려준다() {
        WebtoonJob ghost = running("ghost-2");
        when(jobs.findByStatusOrderByIdAsc(JobStatus.RUNNING)).thenReturn(List.of(ghost));

        stale.sweep();

        verify(runner).refund(any());
    }

    @Test
    @DisplayName("사람이 답할 차례는 안 건드린다 — 그건 서버가 죽어서 멈춘 게 아니다")
    void 사람_대기는_안_건드린다() {
        /* AWAITING_* 는 조회 자체를 안 한다. 서버가 다시 뜨면 그 사람은
           이어서 답할 수 있으므로 치우면 안 된다. */
        when(jobs.findByStatusOrderByIdAsc(JobStatus.RUNNING)).thenReturn(List.of());

        stale.sweep();

        verify(jobs, never()).save(any());
        verify(jobs, never()).findByStatusOrderByIdAsc(JobStatus.AWAITING_SHEET);
        verify(jobs, never()).findByStatusOrderByIdAsc(JobStatus.AWAITING_PICK);
    }

    @Test
    @DisplayName("하나가 실패해도 나머지는 치우고 기동을 막지 않는다")
    void 하나가_실패해도_계속한다() {
        WebtoonJob bad = running("bad");
        WebtoonJob ok = running("ok");
        when(jobs.findByStatusOrderByIdAsc(JobStatus.RUNNING)).thenReturn(List.of(bad, ok));
        when(runner.refund(anyLong()))
                .thenThrow(new IllegalStateException("못 돌려줌"))
                .thenReturn(Refunded.NONE);

        stale.sweep();

        // 첫 번째가 터져도 두 번째는 치워진다 — 여기서 죽으면 서버가 안 뜬다.
        assertThat(ok.getStatus()).isEqualTo(JobStatus.ERROR);
    }
}
