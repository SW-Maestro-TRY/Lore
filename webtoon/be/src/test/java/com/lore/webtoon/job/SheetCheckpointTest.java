package com.lore.webtoon.job;

import com.lore.webtoon.credit.CreditGate;
import com.lore.webtoon.credit.GuestGate;
import com.lore.webtoon.story.StoryStore;
import com.lore.webtoon.work.WorkLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「확인하고 만들기」의 캐릭터 시트 확인(#604) — <b>시트를 다 그리면 멈추고, 확정하면 장면 확인으로 간다.</b>
 *
 * #548 에서 시트 확인을 이야기 고르기 화면에 합쳤더니 시트를 확인하라는 말도 확정 단추도 없이 넘어갔다.
 * 지금은 시트를 그린 뒤 {@code AWAITING_SHEET} 에서 멈추고, 확정({@code resumeAfterSheet})하면 장면을
 * 나눠 {@code AWAITING_SCENES} 로 간다. 내 내용 길(own)은 그대로다.
 */
class SheetCheckpointTest {

    @TempDir
    Path tmp;

    private JobStore store;
    private HarnessProcess harness;
    private JobRunner runner;

    @BeforeEach
    void 세운다() throws Exception {
        harness = mock(HarnessProcess.class);
        when(harness.dir()).thenReturn(Path.of("webtoon/ai/new_harness"));
        when(harness.runsDir()).thenReturn(tmp.resolve("runs"));
        when(harness.run(anyLong(), anyList(), any(), any())).thenReturn(0);
        store = mock(JobStore.class);
        runner = new JobRunner(harness, mock(JobProgress.class), store,
                mock(StoryStore.class), mock(AfterRun.class), mock(WorkLedger.class),
                mock(CreditGate.class), mock(GuestGate.class), mock(JobNotice.class),
                1, 1, tmp.resolve("jobs").toString());
        Path run = Files.createDirectories(tmp.resolve("runs").resolve("run-1"));
        Files.writeString(run.resolve("scenes.json"), "{\"scenes\": [{\"n\": 1, \"where\": \"곳\"}]}");
    }

    /** id 는 저장해야 생기므로 목 대신 실제 작업을 만들고 번호만 꽂는다. */
    private WebtoonJob 작업(boolean checkpoints, boolean own) throws Exception {
        WebtoonJob job = own
                ? WebtoonJob.queued("job-1", 7L, "uid", null, "romance_fantasy", WebtoonQuality.DEFAULT_QUALITY,
                        "ko", checkpoints, "own", "{}", Instant.now())
                : WebtoonJob.queued("job-1", 7L, "uid", null, "romance_fantasy", WebtoonQuality.DEFAULT_QUALITY,
                        "ko", checkpoints, "{}", Instant.now());
        job.learnRun("run-1", Instant.now());
        job.moveTo(JobStatus.RUNNING, JobStage.SHEET, Instant.now());
        java.lang.reflect.Field id = WebtoonJob.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(job, 1L);
        when(store.byId(1L)).thenReturn(job);
        when(store.running(eq(1L), any())).thenReturn(job);
        return job;
    }

    private void 시트를_그린다() throws Exception {
        Method sheet = JobRunner.class.getDeclaredMethod("sheet", Long.class);
        sheet.setAccessible(true);
        sheet.invoke(runner, 1L);
    }

    @Test
    @DisplayName("확인하고 만들기: 시트를 다 그리면 시트 확인에서 멈춘다")
    void 시트에서_멈춘다() throws Exception {
        작업(true, false);
        시트를_그린다();
        verify(store).awaiting(1L, JobStatus.AWAITING_SHEET, JobStage.SHEET);
        verify(store, never()).awaiting(anyLong(), eq(JobStatus.AWAITING_SCENES), any());
    }

    @Test
    @DisplayName("내 내용 길(own)은 그대로 — 시트 확인에서 멈추지 않고 장면 확인으로 간다")
    void own은_그대로다() throws Exception {
        작업(true, true);
        시트를_그린다();
        verify(store, never()).awaiting(anyLong(), eq(JobStatus.AWAITING_SHEET), any());
        verify(store).awaiting(1L, JobStatus.AWAITING_SCENES, JobStage.PAGES);
    }

    @Test
    @DisplayName("시트를 확정하면 장면 확인으로 간다 — 그림 단계로 건너뛰지 않는다")
    void 확정하면_장면_확인이다() throws Exception {
        작업(true, false);
        runner.resumeAfterSheet(1L);
        verify(store).queued(1L, JobStage.PAGES);
        verify(store, timeout(3000)).awaiting(1L, JobStatus.AWAITING_SCENES, JobStage.PAGES);
    }
}
