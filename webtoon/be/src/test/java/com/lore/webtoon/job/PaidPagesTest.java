package com.lore.webtoon.job;

import com.lore.webtoon.credit.CreditGate;
import com.lore.webtoon.credit.GuestGate;
import com.lore.webtoon.story.StoryStore;
import com.lore.webtoon.work.WorkLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 한 편 값은 기본 장 수까지이고, 넘는 장은 그리기 직전에 잔액이 닿는 만큼만 받는다.
 *
 * <b>이 파일이 지키는 것은 「받은 만큼만 그리고, 그린 만큼만 받는가」다.</b> 잔액보다 많이 그리면
 * 원가가 그대로 손해가 되고(멘토님 너울 한 편 7천 원), 받고 안 그리면 사람이 값만 낸다.
 */
class PaidPagesTest {

    private CreditGate credits;
    private JobRunner runner;

    @BeforeEach
    void 세운다() {
        credits = mock(CreditGate.class);
        HarnessProcess harness = mock(HarnessProcess.class);
        when(harness.dir()).thenReturn(Path.of("webtoon/ai/new_harness"));
        when(harness.runsDir()).thenReturn(Path.of("runs").toAbsolutePath());
        runner = new JobRunner(harness, mock(JobProgress.class), mock(JobStore.class),
                mock(StoryStore.class), mock(AfterRun.class), mock(WorkLedger.class),
                credits, mock(GuestGate.class), mock(JobNotice.class),
                1, 1, "jobs");
    }

    private WebtoonJob 작업(Long user, String quality) {
        WebtoonJob job = mock(WebtoonJob.class);
        when(job.getUserId()).thenReturn(user);
        when(job.getQuality()).thenReturn(quality);
        when(job.getPublicId()).thenReturn("job-1");
        return job;
    }

    @Test
    @DisplayName("기본 장 수 안이면 다 그리고 더 받지 않는다")
    void 기본_안이면_그대로() {
        WebtoonJob job = 작업(7L, "swell");

        assertThat(runner.paidPages(job, WebtoonQuality.BASE_PAGES)).isEqualTo(WebtoonQuality.BASE_PAGES);

        verify(credits, never()).charge(anyLong(), anyInt(), anyString(), anyString());
    }

    @Test
    @DisplayName("잔액이 넉넉하면 넘는 장을 다 받고 다 그린다")
    void 넉넉하면_다() {
        WebtoonJob job = 작업(7L, "swell");
        when(credits.balanceOf(7L)).thenReturn(100);

        int drawn = runner.paidPages(job, 11);

        assertThat(drawn).isEqualTo(11);
        verify(credits).charge(eq(7L), eq(3 * 4), eq("pages:job-1"), anyString());
    }

    @Test
    @DisplayName("잔액이 모자라면 닿는 장까지만 받고 그린다 — 나머지는 이어 그리기로 남는다")
    void 모자라면_닿는_만큼() {
        WebtoonJob job = 작업(7L, "swell");
        when(credits.balanceOf(7L)).thenReturn(9);          // 4크레딧짜리 두 장

        int drawn = runner.paidPages(job, 13);

        assertThat(drawn).isEqualTo(WebtoonQuality.BASE_PAGES + 2);
        verify(credits).charge(eq(7L), eq(8), eq("pages:job-1"), anyString());
    }

    @Test
    @DisplayName("한 장도 못 내면 기본 장까지만 그리고 받지 않는다")
    void 한_장도_못_내면_기본까지() {
        WebtoonJob job = 작업(7L, "surf");
        when(credits.balanceOf(7L)).thenReturn(1);

        assertThat(runner.paidPages(job, 12)).isEqualTo(WebtoonQuality.BASE_PAGES);

        verify(credits, never()).charge(anyLong(), anyInt(), anyString(), anyString());
    }

    @Test
    @DisplayName("게스트는 계정 크레딧이 없어 기본 장까지만이다")
    void 게스트는_기본까지() {
        WebtoonJob job = 작업(null, "surf");

        assertThat(runner.paidPages(job, 12)).isEqualTo(WebtoonQuality.BASE_PAGES);
    }

    @Test
    @DisplayName("못 만들면 더 받은 장 값도 같이 돌려준다")
    void 실패하면_장_값도_돌려준다() {
        JobStore store = mock(JobStore.class);
        HarnessProcess harness = mock(HarnessProcess.class);
        when(harness.runsDir()).thenReturn(Path.of("runs").toAbsolutePath());
        JobRunner withStore = new JobRunner(harness, mock(JobProgress.class), store,
                mock(StoryStore.class), mock(AfterRun.class), mock(WorkLedger.class),
                credits, mock(GuestGate.class), mock(JobNotice.class),
                1, 1, "jobs");
        WebtoonJob job = 작업(7L, "swell");
        when(store.byId(1L)).thenReturn(job);
        when(credits.refund(7L, "pages:job-1")).thenReturn(8);

        assertThat(withStore.refund(1L)).isEqualTo(Refunded.CREDIT);

        verify(credits).refund(7L, "pages:job-1");
        verify(credits).refund(7L, "job-1");
    }
}
