package com.lore.webtoon.job;

import com.lore.webtoon.push.JobPush;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 완성 직후 덤으로 할 일(친구 초대 보상 등)이 불리는 길.
 *
 * 붙어 있으면 DONE 으로 적힌 그 작업을 건네 주고, 아무도 안 붙어 있으면(테스트의 {@code new JobStore})
 * 예전과 똑같이 아무 일도 없어야 한다.
 */
class JobDoneListenerTest {

    private static JobStore store(WebtoonJob job) {
        WebtoonJobRepository jobs = mock(WebtoonJobRepository.class);
        when(jobs.findById(anyLong())).thenReturn(Optional.of(job));
        when(jobs.save(any())).thenAnswer(c -> c.getArgument(0));
        HarnessProcess harness = mock(HarnessProcess.class);
        when(harness.runsDir()).thenReturn(Path.of("/tmp"));
        return new JobStore(jobs, harness, mock(JobPush.class));
    }

    private static WebtoonJob job() {
        return WebtoonJob.seeded("job-1", "uid-1", "style", "{}", Instant.parse("2026-11-03T00:00:00Z"));
    }

    @Test
    @DisplayName("DONE 으로 적힌 뒤 붙어 있는 것들에 그 작업을 건넨다")
    void listenersGetTheDoneJob() {
        WebtoonJob job = job();
        JobStore store = store(job);
        List<WebtoonJob> seen = new ArrayList<>();
        store.setOnDone(List.of(seen::add, seen::add));

        store.done(1L);

        assertThat(seen).containsExactly(job, job);
        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
    }

    @Test
    @DisplayName("아무도 안 붙어 있으면 예전처럼 적기만 한다")
    void noListenersIsFine() {
        WebtoonJob job = job();
        JobStore store = store(job);

        store.setOnDone(null);
        store.done(1L);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
    }
}
