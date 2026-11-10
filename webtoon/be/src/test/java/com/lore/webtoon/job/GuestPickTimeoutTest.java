package com.lore.webtoon.job;

import com.lore.webtoon.story.StoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 게스트의 이야기 고르기는 30초 안에 답이 없으면 서버가 1번으로 넘긴다(#851).
 * 로그인한 사람은 그대로 기다린다. 화면이 앞에 떠 있는 동안({@code watching})은 시계가 다시 0 이 된다.
 */
class GuestPickTimeoutTest {

    private WebtoonJobRepository jobs;
    private JobStore store;
    private JobRunner runner;
    private StoryStore stories;
    private CheckpointTimeouts timeouts;

    @BeforeEach
    void 세운다() {
        jobs = mock(WebtoonJobRepository.class);
        store = mock(JobStore.class);
        runner = mock(JobRunner.class);
        stories = mock(StoryStore.class);
        when(store.directionsOf(anyLong())).thenReturn(List.of(Map.of("n", 1), Map.of("n", 2)));
        when(runner.autoPick(anyString(), anyInt())).thenReturn(1);
        timeouts = new CheckpointTimeouts(jobs, store, runner, stories, false, 300, 30);
    }

    /** 고르기에 들어선 지 {@code agoSeconds}초 된 작업. 게스트면 userId 가 없다. */
    private WebtoonJob 고르는_중(long id, Long userId, long agoSeconds) throws Exception {
        WebtoonJob job = WebtoonJob.queued("job-" + id, userId, "uid", null, "romance_fantasy",
                WebtoonQuality.DEFAULT_QUALITY, "ko", true, "{}", Instant.now().minusSeconds(agoSeconds + 60));
        job.learnRun("run-" + id, Instant.now());
        job.moveTo(JobStatus.AWAITING_PICK, JobStage.STORY, Instant.now().minusSeconds(agoSeconds));
        java.lang.reflect.Field f = WebtoonJob.class.getDeclaredField("id");
        f.setAccessible(true);
        f.set(job, id);
        when(jobs.findById(id)).thenReturn(Optional.of(job));
        return job;
    }

    @Test
    @DisplayName("게스트가 30초 넘게 안 고르면 1번으로 넘긴다")
    void 게스트는_넘긴다() throws Exception {
        WebtoonJob job = 고르는_중(1L, null, 40);
        when(jobs.findByStatusOrderByIdAsc(JobStatus.AWAITING_PICK)).thenReturn(List.of(job));

        timeouts.sweep();

        verify(store).pick(1L, 1);
        verify(stories).choose("run-1", 1);
        verify(runner).resumeAfterPick(1L);
    }

    @Test
    @DisplayName("게스트라도 30초가 안 됐으면 기다린다")
    void 아직은_기다린다() throws Exception {
        WebtoonJob job = 고르는_중(2L, null, 10);
        when(jobs.findByStatusOrderByIdAsc(JobStatus.AWAITING_PICK)).thenReturn(List.of(job));

        timeouts.sweep();

        verify(runner, never()).resumeAfterPick(anyLong());
        assertThat(timeouts.guestPickSecondsLeft(job, Instant.now())).isBetween(18, 20);
    }

    @Test
    @DisplayName("로그인한 사람은 시간이 지나도 그대로 기다린다 — 마이페이지에서 돌아와 고른다")
    void 로그인은_기다린다() throws Exception {
        WebtoonJob job = 고르는_중(3L, 7L, 600);
        when(jobs.findByStatusOrderByIdAsc(JobStatus.AWAITING_PICK)).thenReturn(List.of(job));

        timeouts.sweep();

        verify(runner, never()).resumeAfterPick(anyLong());
        assertThat(timeouts.guestPickSecondsLeft(job, Instant.now())).isNull();
    }

    @Test
    @DisplayName("화면이 앞에 떠 있으면(watching) 시계가 다시 0 — 보고 있는 동안은 안 넘긴다")
    void 보고_있으면_안_넘긴다() throws Exception {
        WebtoonJob job = 고르는_중(4L, null, 40);
        when(jobs.findByStatusOrderByIdAsc(JobStatus.AWAITING_PICK)).thenReturn(List.of(job));
        timeouts.seen(job, Instant.now());

        timeouts.sweep();

        verify(runner, never()).resumeAfterPick(anyLong());
        assertThat(timeouts.guestPickSecondsLeft(job, Instant.now())).isBetween(28, 30);
    }

    @Test
    @DisplayName("0 으로 두면 끈다")
    void 끌_수_있다() throws Exception {
        CheckpointTimeouts off = new CheckpointTimeouts(jobs, store, runner, stories, false, 300, 0);
        WebtoonJob job = 고르는_중(5L, null, 600);
        when(jobs.findByStatusOrderByIdAsc(JobStatus.AWAITING_PICK)).thenReturn(List.of(job));

        off.sweep();

        verify(runner, never()).resumeAfterPick(anyLong());
        assertThat(off.guestPickSecondsLeft(job, Instant.now())).isNull();
    }
}
