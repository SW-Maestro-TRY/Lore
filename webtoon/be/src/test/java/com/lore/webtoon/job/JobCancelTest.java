package com.lore.webtoon.job;

import com.lore.webtoon.credit.CreditGate;
import com.lore.webtoon.credit.GuestGate;
import com.lore.webtoon.story.StoryStore;
import com.lore.webtoon.work.WorkLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 사람이 「그만두기」를 눌렀을 때.
 *
 * <b>이 파일이 지키는 것은 「낸 것을 정확히 한 번 돌려주는가」다.</b> 취소는
 * 두 곳에서 들어올 수 있다 — 그만두라고 한 자리와, 그 말을 본 걸음. 두 곳이
 * 다 돌려주면 로그인 안 한 사람의 하루 몫이 두 번 돌아오고, 한 곳도 안
 * 돌려주면 만들어진 것 없이 값만 빠진다.
 */
class JobCancelTest {

    private JobStore store;
    private CreditGate credits;
    private GuestGate guests;
    private HarnessProcess harness;
    private JobRunner runner;

    @BeforeEach
    void 세운다() {
        store = mock(JobStore.class);
        credits = mock(CreditGate.class);
        guests = mock(GuestGate.class);
        harness = mock(HarnessProcess.class);
        when(harness.dir()).thenReturn(Path.of("haeun/new_harness"));
        runner = new JobRunner(harness, mock(JobProgress.class), store,
                mock(StoryStore.class), mock(AfterRun.class), mock(WorkLedger.class),
                credits, guests, "runs", "jobs");
    }

    private WebtoonJob 작업(JobStatus status) {
        WebtoonJob job = mock(WebtoonJob.class);
        when(job.getStatus()).thenReturn(status);
        when(job.getPublicId()).thenReturn("job-1");
        when(job.getRunId()).thenReturn("run-1");
        when(store.byId(1L)).thenReturn(job);
        return job;
    }

    @Test
    @DisplayName("사람이 볼 차례에 그만두면 그 자리에서 끝난다 — 줄이 빌 때까지 안 기다린다")
    void 기다리는_중에_그만두기() {
        작업(JobStatus.AWAITING_SHEET);

        runner.cancel(1L);

        verify(store).failed(eq(1L), eq(JobRunner.CANCELLED), any());
    }

    @Test
    @DisplayName("돌려준 것을 화면에 적는다 — 크레딧을 돌려줬으면 credit")
    void 돌려준_것을_적는다() {
        작업(JobStatus.AWAITING_PICK);
        when(credits.refund(any(), anyString())).thenReturn(12);

        runner.cancel(1L);

        verify(store).failed(1L, JobRunner.CANCELLED, Refunded.CREDIT);
    }

    @Test
    @DisplayName("이미 끝난 것은 안 건드린다 — 다 만들어진 순간에 눌렀을 수 있다")
    void 끝난_것은_그냥_둔다() {
        작업(JobStatus.DONE);

        runner.cancel(1L);

        verify(store, never()).failed(any(), anyString(), any());
        verify(credits, never()).refund(any(), anyString());
        verify(guests, never()).refundKey(anyString());
    }

    @Test
    @DisplayName("두 번 눌러도 한 번만 돌려준다 — 무료 횟수는 부를 때마다 하나씩 돌아온다")
    void 두_번_눌러도_한_번() {
        WebtoonJob job = 작업(JobStatus.AWAITING_SHEET);

        runner.cancel(1L);
        // 첫 번째가 끝냈다. 이제 이 작업은 끝난 것이다.
        when(job.getStatus()).thenReturn(JobStatus.ERROR);
        runner.cancel(1L);

        verify(store).failed(eq(1L), eq(JobRunner.CANCELLED), any());
        verify(credits).refund(any(), anyString());
    }

    @Test
    @DisplayName("모르는 작업이면 아무 일도 안 한다")
    void 모르는_작업() {
        when(store.byId(1L)).thenReturn(null);

        runner.cancel(1L);

        verify(store, never()).failed(any(), anyString(), any());
    }
}
