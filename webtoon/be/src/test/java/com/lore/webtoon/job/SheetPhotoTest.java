package com.lore.webtoon.job;

import com.lore.webtoon.credit.CreditGate;
import com.lore.webtoon.credit.GuestGate;
import com.lore.webtoon.story.StoryStore;
import com.lore.webtoon.work.WorkLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 올린 사진을 언제 지우나(#525).
 *
 * 시트 확인에서 「다시 만들기」를 누르면 하네스가 사양을 사진부터 다시 쓴다. 예전에는
 * 시트가 나오자마자 사진을 지워서, 사진을 올린 사람의 다시 만들기가 전부
 * 「사진 파일이 없습니다」로 죽었다. <b>이 파일이 지키는 것은 「다시 만들 수 있는 동안은
 * 사진이 남아 있고, 그럴 일이 없어지면 지워진다」다.</b>
 */
class SheetPhotoTest {

    @TempDir
    Path tmp;

    private JobStore store;
    private HarnessProcess harness;
    private JobRunner runner;
    private WebtoonJob job;
    private Path photo;

    @BeforeEach
    void 세운다() throws Exception {
        store = mock(JobStore.class);
        harness = mock(HarnessProcess.class);
        when(harness.dir()).thenReturn(Path.of("webtoon/ai/new_harness"));
        when(harness.runsDir()).thenReturn(tmp.resolve("runs"));
        when(harness.run(anyLong(), any(), any(), any())).thenReturn(0);   // 하네스는 늘 성공한다

        Path jobs = tmp.resolve("jobs");
        photo = Files.createDirectories(jobs.resolve("job-1")).resolve("photo1.png");
        Files.write(photo, new byte[]{1, 2, 3});

        job = mock(WebtoonJob.class);
        when(job.getId()).thenReturn(1L);
        when(job.getStatus()).thenReturn(JobStatus.RUNNING);
        when(job.getPublicId()).thenReturn("job-1");
        when(job.getRunId()).thenReturn("run-1");
        when(job.getPicked()).thenReturn(1);
        when(store.byId(1L)).thenReturn(job);
        when(store.running(any(), any())).thenReturn(job);

        runner = new JobRunner(harness, mock(JobProgress.class), store,
                mock(StoryStore.class), mock(AfterRun.class), mock(WorkLedger.class),
                mock(CreditGate.class), mock(GuestGate.class), mock(JobNotice.class),
                1, 1, jobs.toString());
    }

    @Test
    @DisplayName("시트 확인을 기다리는 동안은 사진을 둔다 — 다시 만들기가 사진을 다시 읽는다")
    void 확인_중에는_남는다() {
        when(job.isCheckpoints()).thenReturn(true);

        /* 확인하고 만들기 길은 시트를 다 그리면 시트 확인에서 멈춘다(#604). 다시 만들기가 사진부터
           사양을 다시 쓰므로 그동안 사진은 남아 있어야 한다. */
        runner.resumeAfterPick(1L);
        verify(store, timeout(2000)).awaiting(1L, JobStatus.AWAITING_SHEET, JobStage.SHEET);
        assertThat(photo).exists();

        when(job.getStatus()).thenReturn(JobStatus.AWAITING_SHEET);
        runner.redrawSheet(1L, "머리를 더 길게", JobStatus.AWAITING_SHEET);
        verify(store, timeout(2000).times(2)).awaiting(1L, JobStatus.AWAITING_SHEET, JobStage.SHEET);
        assertThat(photo).exists();
    }

    @Test
    @DisplayName("확인하고 만들기에서 시트를 확정해도 사진은 둔다 — 장면 확인에서도 시트를 다시 만들 수 있다(#604)")
    void 확정해도_장면_확인까지_남는다() {
        when(job.isCheckpoints()).thenReturn(true);
        when(job.getStatus()).thenReturn(JobStatus.AWAITING_SHEET);

        runner.resumeAfterSheet(1L);

        verify(store, timeout(2000)).awaiting(1L, JobStatus.AWAITING_SCENES, JobStage.PAGES);
        assertThat(photo).exists();
    }

    @Test
    @DisplayName("장면 확인을 끝내고 그림으로 가면 지운다 (#548)")
    void 장면_확인_끝내면_지운다() {
        when(job.getStatus()).thenReturn(JobStatus.AWAITING_SCENES);

        runner.resumeAfterScenes(1L);

        assertThat(photo).doesNotExist();
    }

    @Test
    @DisplayName("시트를 확정하면 지운다")
    void 확정하면_지운다() {
        when(job.getStatus()).thenReturn(JobStatus.AWAITING_SHEET);

        runner.resumeAfterSheet(1L);

        assertThat(photo).doesNotExist();
    }

    @Test
    @DisplayName("시트 확인 중에 그만두면 지운다")
    void 그만두면_지운다() {
        when(job.getStatus()).thenReturn(JobStatus.AWAITING_SHEET);

        runner.cancel(1L);

        verify(store, times(1)).failed(any(), any(), any(), any());
        assertThat(photo).doesNotExist();
    }

    @Test
    @DisplayName("시트 확인 없이 가는 작업은 시트가 나오면 바로 지운다 — 다시 만들 자리가 없다")
    void 확인_없으면_바로_지운다() throws Exception {
        when(job.isCheckpoints()).thenReturn(false);

        runner.resumeAfterPick(1L);

        for (int i = 0; i < 40 && Files.exists(photo); i++) {
            Thread.sleep(50);
        }
        assertThat(photo).doesNotExist();
    }
}
