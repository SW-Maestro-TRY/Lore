package com.lore.webtoon.job;

import com.lore.common.s3.S3Service;
import com.lore.webtoon.art.PrivateArt;
import com.lore.common.s3.S3Storage;
import com.lore.webtoon.character.CharacterOwner;
import com.lore.webtoon.character.CharacterService;
import com.lore.webtoon.push.JobPush;
import com.lore.webtoon.safety.SafetyGuard;
import com.lore.webtoon.story.StoryStore;
import com.lore.webtoon.work.WorkLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 진행 화면의 걸음 3 「장면 나누기」(#601) — <b>장면 확인을 지난 뒤에도 장면이 응답에 실리는가.</b>
 *
 * 전에는 장면 확인 대기({@code AWAITING_SCENES})일 때만 실어서, 「이대로 웹툰 만들기」를 누르고
 * 나면 걸음 3 이 눌리지 않았다. 「바로 만들기」 작품은 한 번도 눌리지 않았다.
 */
class JobServiceScenesTest {

    private static final List<Map<String, Object>> SCENES = List.of(Map.of("n", 1, "text", "첫 장면"));

    private JobStore store;
    private JobRunner runner;
    private JobService service;

    @BeforeEach
    void 세운다() {
        store = mock(JobStore.class);
        runner = mock(JobRunner.class);
        JobProgress progress = mock(JobProgress.class);
        when(progress.of(any())).thenReturn(new JobProgress.Snapshot(List.of(), "", 0, 0, 0));
        JobQueue queue = mock(JobQueue.class);
        when(queue.spotOf(any())).thenReturn(null);
        when(runner.scenesOf(any(), anyString())).thenReturn(SCENES);
        when(runner.scenesOf(any(), Mockito.isNull())).thenReturn(List.of());
        service = new JobService(mock(WebtoonJobRepository.class), store, queue, runner, progress,
                mock(StoryStore.class), mock(WorkLedger.class), mock(JobNotice.class),
                mock(CharacterService.class), mock(CharacterOwner.class), mock(PrivateArt.class),
                mock(S3Service.class), mock(S3Storage.class), mock(SafetyGuard.class),
                mock(WebtoonCastSheetRepository.class), mock(RunArt.class), mock(JobPush.class), "");
    }

    private void 작업이(JobStatus status, JobStage stage, boolean checkpoints) {
        WebtoonJob job = WebtoonJob.queued("job-1", 7L, "uid-a", null, "romance_fantasy",
                WebtoonQuality.DEFAULT_QUALITY, "ko", checkpoints, "{}", Instant.now().minusSeconds(60));
        job.learnRun("run-1", Instant.now());
        job.moveTo(status, stage, Instant.now());
        when(store.byPublicId("job-1")).thenReturn(job);
    }

    @Test
    @DisplayName("장면 확인 대기: 장면이 실린다(고치는 칸용)")
    void 확인_대기() {
        작업이(JobStatus.AWAITING_SCENES, JobStage.PAGES, true);
        assertThat(service.view("job-1").scenes()).isEqualTo(SCENES);
    }

    @Test
    @DisplayName("확인한 뒤 그리는 중: 장면이 읽기 전용으로 실린다")
    void 그리는_중() {
        작업이(JobStatus.RUNNING, JobStage.PAGES, true);
        assertThat(service.view("job-1").scenes()).isEqualTo(SCENES);
    }

    @Test
    @DisplayName("완성 뒤: 장면이 실린다")
    void 완성_뒤() {
        작업이(JobStatus.DONE, JobStage.BIND, true);
        assertThat(service.view("job-1").scenes()).isEqualTo(SCENES);
    }

    @Test
    @DisplayName("바로 만들기(확인 안 함) 작품도 그리는 중에 장면이 실린다")
    void 바로_만들기() {
        작업이(JobStatus.RUNNING, JobStage.PAGES, false);
        assertThat(service.view("job-1").scenes()).isEqualTo(SCENES);
    }

    @Test
    @DisplayName("장면 파일이 아직 없거나 치워졌으면 빈 목록 — 단추는 꺼진 채로 둔다")
    void 파일이_없으면_빈_목록() {
        작업이(JobStatus.RUNNING, JobStage.STORY, true);
        when(runner.scenesOf(any(), anyString())).thenReturn(List.of());
        assertThat(service.view("job-1").scenes()).isEmpty();
    }
}
