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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 서버가 다시 뜰 때 끊긴 작업을 멈춘 걸음부터 잇는다(2026-10-26).
 *
 * <b>이 파일이 지키는 것은 「받은 요청은 끝까지 만들고, 이미 만든 것은 다시 안 만든다」다.</b>
 * 그린 장을 다시 그리면 그림 값이 두 번 나가고, 사람이 확인할 자리를 건너뛰면 확인 안 한 장면으로 그린다.
 * 모델은 부르지 않는다 — 하네스는 가짜다.
 */
class JobResumeTest {

    @TempDir
    Path tmp;

    private JobStore store;
    private CreditGate credits;
    private HarnessProcess harness;
    private JobRunner runner;
    private Path runs;
    private Path jobs;

    @BeforeEach
    void 세운다() throws Exception {
        runs = Files.createDirectories(tmp.resolve("runs"));
        jobs = Files.createDirectories(tmp.resolve("jobs"));
        store = mock(JobStore.class);
        credits = mock(CreditGate.class);
        harness = mock(HarnessProcess.class);
        when(harness.dir()).thenReturn(Path.of("webtoon/ai/new_harness"));
        when(harness.runsDir()).thenReturn(runs);
        runner = new JobRunner(harness, mock(JobProgress.class), store,
                mock(StoryStore.class), mock(AfterRun.class), mock(WorkLedger.class),
                credits, mock(GuestGate.class), mock(JobNotice.class),
                1, 1, jobs.toString());
    }

    private WebtoonJob 끊긴(String runId, JobStage stage, boolean own, boolean checkpoints) {
        WebtoonJob job = mock(WebtoonJob.class);
        when(job.getId()).thenReturn(1L);
        when(job.getPublicId()).thenReturn("job-1");
        when(job.getRunId()).thenReturn(runId);
        when(job.getStage()).thenReturn(stage);
        when(job.getStatus()).thenReturn(JobStatus.RUNNING);
        when(job.isOwn()).thenReturn(own);
        when(job.isCheckpoints()).thenReturn(checkpoints);
        return job;
    }

    private Path 작품(String runId) throws Exception {
        return Files.createDirectories(runs.resolve(runId));
    }

    private void 장면(Path run) throws Exception {
        Files.writeString(run.resolve("scenes.json"), "{\"scenes\":[{\"n\":1},{\"n\":2}]}");
    }

    private void 그린_장(Path run, int page) throws Exception {
        Files.createDirectories(run.resolve("pages"));
        Files.write(run.resolve("pages").resolve("page%02d.png".formatted(page)), new byte[]{1});
    }

    @Test
    @DisplayName("이야기를 짓기 전에 끊겼으면 이야기부터 다시 줄에 선다")
    void 이야기_전() throws Exception {
        Files.createDirectories(jobs.resolve("job-1"));
        WebtoonJob job = 끊긴(null, JobStage.STORY, true, false);

        assertThat(runner.resumeInterrupted(job)).isTrue();

        verify(store).queued(1L, JobStage.STORY);
    }

    @Test
    @DisplayName("이야기를 다시 짓다 끊겼으면 있던 이야기로 확인 자리에 돌아간다")
    void 이야기_다시_짓기() throws Exception {
        Path run = 작품("run-1");
        Files.writeString(run.resolve("directions.json"),
                "[{\"n\":1,\"title\":\"제목\",\"body\":\"본문\"}]");
        WebtoonJob job = 끊긴("run-1", JobStage.STORY, true, false);

        assertThat(runner.resumeInterrupted(job)).isTrue();

        verify(store).awaiting(1L, JobStatus.AWAITING_PICK, JobStage.STORY);
    }

    @Test
    @DisplayName("장면을 다 나눈 뒤 확인 전에 끊겼으면 장면 확인 자리로 — 확인 안 한 장면으로 그리지 않는다")
    void 장면_확인_전() throws Exception {
        장면(작품("run-1"));
        WebtoonJob job = 끊긴("run-1", JobStage.PAGES, true, false);

        assertThat(runner.resumeInterrupted(job)).isTrue();

        verify(store).awaiting(1L, JobStatus.AWAITING_SCENES, JobStage.PAGES);
        verify(store, never()).queued(anyLong(), any());
    }

    @Test
    @DisplayName("장면을 나누다 끊겼으면 장면을 다시 나눈다")
    void 장면_나누는_중() throws Exception {
        작품("run-1");
        WebtoonJob job = 끊긴("run-1", JobStage.PAGES, true, false);

        assertThat(runner.resumeInterrupted(job)).isTrue();

        verify(store).queued(1L, JobStage.PAGES);
        verify(store, never()).awaiting(anyLong(), any(), any());
    }

    @Test
    @DisplayName("그리다 끊겼으면 그림 걸음으로 다시 줄에 선다")
    void 그리는_중() throws Exception {
        Path run = 작품("run-1");
        장면(run);
        그린_장(run, 1);
        WebtoonJob job = 끊긴("run-1", JobStage.PAGES, true, false);

        assertThat(runner.resumeInterrupted(job)).isTrue();

        verify(store).queued(1L, JobStage.PAGES);
        verify(store, never()).awaiting(anyLong(), any(), any());
    }

    @Test
    @DisplayName("검수·이어 붙이기에서 끊겼어도 그림 걸음부터 — 그린 장은 건너뛰고 검수로 간다")
    void 검수_중() throws Exception {
        Path run = 작품("run-1");
        장면(run);
        그린_장(run, 1);
        WebtoonJob job = 끊긴("run-1", JobStage.BIND, false, false);

        assertThat(runner.resumeInterrupted(job)).isTrue();

        verify(store).queued(1L, JobStage.PAGES);
    }

    @Test
    @DisplayName("이미 그린 장은 다시 안 그린다 — 안전 검사에 걸려 비워 둔 장은 다시 그린다")
    void 그린_장은_건너뛴다() throws Exception {
        Path run = 작품("run-1");
        그린_장(run, 1);
        그린_장(run, 2);
        Files.writeString(run.resolve("pages").resolve("page02.unsafe.json"), "{}");

        assertThat(runner.pageDrawn("run-1", 1)).isTrue();
        assertThat(runner.pageDrawn("run-1", 2)).isFalse();
        assertThat(runner.pageDrawn("run-1", 3)).isFalse();
    }

    @Test
    @DisplayName("폴더가 사라졌으면 이어 갈 수 없다 — 부르는 쪽이 끊긴 것으로 적는다")
    void 폴더가_없으면_못_잇는다() {
        WebtoonJob job = 끊긴("run-gone", JobStage.PAGES, true, false);

        assertThat(runner.resumeInterrupted(job)).isFalse();
    }

    @Test
    @DisplayName("이어 갈 때 값을 돌려주지 않는다")
    void 값을_안_돌려준다() throws Exception {
        Path run = 작품("run-1");
        장면(run);
        그린_장(run, 1);
        WebtoonJob job = 끊긴("run-1", JobStage.PAGES, false, false);

        runner.resumeInterrupted(job);

        verify(credits, timeout(500).times(0)).refund(any(), anyString());
    }
}
