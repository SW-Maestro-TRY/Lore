package com.lore.webtoon.job;

import com.lore.common.exception.BusinessException;
import com.lore.common.s3.S3Service;
import com.lore.common.s3.S3Storage;
import com.lore.webtoon.art.PrivateArt;
import com.lore.webtoon.character.CharacterOwner;
import com.lore.webtoon.character.CharacterService;
import com.lore.webtoon.push.JobPush;
import com.lore.webtoon.safety.SafetyGuard;
import com.lore.webtoon.story.StoryStore;
import com.lore.webtoon.work.WorkLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 게스트의 「확인하고 만들기」(#608) — <b>열어 주되, 다시 뽑기에는 작업당 횟수를 둔다.</b>
 *
 * <ul>
 *   <li>확인하고 만들기(quick)는 게스트도 받는다. 「만들고 싶은 내용이 있어요」(own)는 로그인 전용이다.</li>
 *   <li>게스트의 이야기 후보 다시 만들기 · 장면 다시 나누기는 작업당 한도(기본 2)까지만 된다.
 *       로그인한 사람은 제한이 없다.</li>
 * </ul>
 */
class JobServiceGuestTest {

    private JobStore store;
    private JobRunner runner;
    private JobService service;

    @BeforeEach
    void 세운다() {
        store = mock(JobStore.class);
        runner = mock(JobRunner.class);
        service = new JobService(mock(WebtoonJobRepository.class), store, mock(JobQueue.class), runner,
                mock(JobProgress.class), mock(StoryStore.class), mock(WorkLedger.class), mock(JobNotice.class),
                mock(CharacterService.class), mock(CharacterOwner.class), mock(PrivateArt.class),
                mock(S3Service.class), mock(S3Storage.class), mock(SafetyGuard.class),
                mock(WebtoonCastSheetRepository.class), mock(RunArt.class), mock(JobPush.class), 2, 2, "");
    }

    private static JobService.CreateRequest 요청(String mode, Boolean checkpoints) {
        // 캐릭터를 알 수 있는 것이 하나도 없다 — 관문을 지나면 이 검사에서 걸려 다른 말이 나온다.
        return new JobService.CreateRequest("", "", "", "내용", "frost", null, null, null, true, checkpoints,
                "uid", null, null, null, "ko", mode, "", "", "");
    }

    private void 작업(Long userId, JobStatus status) {
        WebtoonJob job = WebtoonJob.queued("job-1", userId, "uid", null, "frost", WebtoonQuality.DEFAULT_QUALITY,
                "ko", true, "{}", Instant.now());
        job.learnRun("run-1", Instant.now());
        job.moveTo(status, JobStage.STORY, Instant.now());
        when(store.byPublicId("job-1")).thenReturn(job);
    }

    @Test
    @DisplayName("게스트도 확인하고 만들기를 받는다 — 로그인 거절이 아니라 다음 검사까지 간다")
    void 게스트도_확인하고_만든다() {
        assertThatThrownBy(() -> service.create(요청("quick", true), null, "uid", "guest-key"))
                .isInstanceOf(BusinessException.class)
                .hasMessageNotContaining("로그인");
    }

    @Test
    @DisplayName("내 내용 길(own)은 게스트에게 여전히 로그인을 요구한다")
    void own은_로그인_전용이다() {
        assertThatThrownBy(() -> service.create(요청("own", true), null, "uid", "guest-key"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("로그인");
    }

    @Test
    @DisplayName("게스트가 이야기를 한도만큼 다시 만들었으면 더는 못 한다")
    void 게스트_이야기_다시_만들기는_한도가_있다() {
        작업(null, JobStatus.AWAITING_PICK);
        when(runner.redrawCount("run-1", "restory")).thenReturn(2);

        assertThatThrownBy(() -> service.retryPick("job-1", "메모"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("2번까지");
        verify(runner, never()).retryDirections(any(), anyString(), any());
        verify(runner, never()).countRedraw(anyString(), anyString());
    }

    @Test
    @DisplayName("한도 안이면 하고 한 번으로 센다")
    void 한도_안이면_하고_센다() {
        작업(null, JobStatus.AWAITING_PICK);
        when(runner.redrawCount("run-1", "restory")).thenReturn(1);

        service.retryPick("job-1", "메모");

        verify(runner).countRedraw("run-1", "restory");
        verify(runner).retryDirections(any(), eq("메모"), any());
    }

    @Test
    @DisplayName("게스트의 장면 다시 나누기도 한도가 있다")
    void 게스트_장면_다시_나누기는_한도가_있다() {
        작업(null, JobStatus.AWAITING_SCENES);
        when(runner.redrawCount("run-1", "rescenes")).thenReturn(2);

        assertThatThrownBy(() -> service.retryScenes("job-1", "메모"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("장면을 다시 나누는 것");
        verify(runner, never()).rescenes(any(), anyString());
    }

    @Test
    @DisplayName("로그인한 사람은 제한이 없다 — 세지도 않는다")
    void 로그인한_사람은_제한이_없다() {
        작업(7L, JobStatus.AWAITING_PICK);
        when(runner.redrawCount("run-1", "restory")).thenReturn(99);

        service.retryPick("job-1", "메모");

        verify(runner).retryDirections(any(), eq("메모"), any());
        verify(runner, never()).countRedraw(anyString(), anyString());
        assertThat(List.of()).isEmpty();
    }
}
