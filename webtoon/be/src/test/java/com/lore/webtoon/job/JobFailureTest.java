package com.lore.webtoon.job;

import com.lore.webtoon.credit.CreditGate;
import com.lore.webtoon.credit.GuestGate;
import com.lore.webtoon.story.StoryStore;
import com.lore.webtoon.work.WorkLedger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 실패한 이유를 사람에게 설명하고 DB 에 남기는가(#531).
 *
 * 전에는 그림이 안전 검사에 걸려도 화면에 「캐릭터 시트를 만들지 못했습니다」만 떴고,
 * 무엇이 터졌는지는 서버 로그에만 스쳐 지나갔다.
 */
class JobFailureTest {

    @TempDir
    Path tmp;

    private static final String SAFETY = """
            {"stage": "SHEET_IMAGE", "code": "image_safety", "categories": ["sexual"],
             "message": "BadRequestError: moderation_blocked"}""";

    @Test
    @DisplayName("하네스가 남긴 이유를 읽는다")
    void 읽는다() throws Exception {
        Files.writeString(tmp.resolve(JobFailure.FILE), SAFETY);

        JobFailure f = JobFailure.read(tmp).orElseThrow();

        assertThat(f.stage()).isEqualTo("SHEET_IMAGE");
        assertThat(f.code()).isEqualTo("image_safety");
        assertThat(f.categories()).containsExactly("sexual");
    }

    @Test
    @DisplayName("이유가 없거나 깨졌으면 비어 있다 — 원래 실패 문구로 간다")
    void 없으면_비어_있다() throws Exception {
        assertThat(JobFailure.read(tmp)).isEmpty();
        Files.writeString(tmp.resolve(JobFailure.FILE), "{깨짐");
        assertThat(JobFailure.read(tmp)).isEmpty();
    }

    @Test
    @DisplayName("시트가 선정성으로 막히면 옷차림을 바꾸라고 말한다")
    void 선정성_시트() {
        String said = new JobFailure("SHEET_IMAGE", "image_safety", List.of("sexual"), null)
                .humanMessage("캐릭터 시트를 만들지 못했습니다");

        assertThat(said).contains("선정성").contains("노출이 적은 옷");
        assertThat(said.length()).isLessThanOrEqualTo(300);     // error 칸 길이
    }

    @Test
    @DisplayName("알아볼 수 없는 실패는 어디서 멈췄는지만 말한다")
    void 모르는_실패() {
        String said = new JobFailure("SHEET", "error", List.of(), "boom")
                .humanMessage("캐릭터 시트를 만들지 못했습니다");

        assertThat(said).isEqualTo("캐릭터 시트를 만들지 못했습니다");
    }

    @Test
    @DisplayName("시트가 안전 검사에 걸리면 실패로 끝내지 않고 고쳐 주기를 기다린다 — 이유는 사람에게, 원문은 DB 에(#626)")
    void 실행기가_멈추고_기다린다() throws Exception {
        JobStore store = mock(JobStore.class);
        HarnessProcess harness = mock(HarnessProcess.class);
        JobProgress progress = mock(JobProgress.class);
        when(harness.dir()).thenReturn(Path.of("webtoon/ai/new_harness"));
        when(harness.runsDir()).thenReturn(tmp);
        when(harness.run(anyLong(), any(), any(), any())).thenReturn(0, 1);   // 번호 저장은 되고 시트가 실패
        when(progress.of(1L)).thenReturn(new JobProgress.Snapshot(
                List.of("[시트] 안전 검사에 걸렸습니다(sexual)"), "", 0, 0, 0));
        Files.createDirectories(tmp.resolve("run-1"));
        Files.writeString(tmp.resolve("run-1").resolve(JobFailure.FILE), SAFETY);

        WebtoonJob job = mock(WebtoonJob.class);
        when(job.getId()).thenReturn(1L);
        when(job.getStatus()).thenReturn(JobStatus.RUNNING);
        when(job.getStage()).thenReturn(JobStage.SHEET);
        when(job.getPublicId()).thenReturn("job-1");
        when(job.getRunId()).thenReturn("run-1");
        when(job.getPicked()).thenReturn(1);
        when(store.byId(1L)).thenReturn(job);
        when(store.running(any(), any())).thenReturn(job);

        JobNotice notice = mock(JobNotice.class);
        JobRunner runner = new JobRunner(harness, progress, store,
                mock(StoryStore.class), mock(AfterRun.class), mock(WorkLedger.class),
                mock(CreditGate.class), mock(GuestGate.class), notice,
                1, 1, tmp.resolve("jobs").toString());

        runner.resumeAfterPick(1L);

        ArgumentCaptor<String> why = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<JobFailure> failure = ArgumentCaptor.forClass(JobFailure.class);
        verify(store, timeout(2000)).sheetBlocked(eq(1L), why.capture(), failure.capture());
        verify(store, never()).failed(anyLong(), any(), any(), any());
        verify(notice).needsFix(eq(1L), any());
        assertThat(why.getValue()).contains("선정성").contains("이야기는 그대로");
        assertThat(failure.getValue().code()).isEqualTo("image_safety");
        assertThat(failure.getValue().stage()).isEqualTo("SHEET_IMAGE");
        assertThat(failure.getValue().detail()).contains("moderation_blocked");
    }
}
