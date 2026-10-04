package com.lore.webtoon.work;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.user.UserRepository;
import com.lore.webtoon.art.PageStore;
import com.lore.webtoon.job.JobNotice;
import com.lore.webtoon.story.StoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 관리자 작품 처리(#638) — 비공개 · 다시 공개 · 삭제 · 되살리기 · 경고.
 */
class WorkModerationTest {

    private WebtoonWorkRepository works;
    private ModerationLogRepository logs;
    private PageStore pages;
    private JobNotice notice;
    private WorkModeration moderation;

    @BeforeEach
    void 세운다() {
        works = mock(WebtoonWorkRepository.class);
        logs = mock(ModerationLogRepository.class);
        pages = mock(PageStore.class);
        notice = mock(JobNotice.class);
        StoryStore stories = mock(StoryStore.class);
        when(stories.chosenOf(anyString())).thenReturn(Optional.empty());
        RunTrash trash = mock(RunTrash.class);
        when(trash.keepDays()).thenReturn(30);
        when(logs.save(any())).thenAnswer(a -> a.getArgument(0));
        when(logs.findFirstByRunIdAndActionOrderByCreatedAtDescIdDesc(anyString(), anyString())).thenReturn(Optional.empty());
        moderation = new WorkModeration(works, logs, new ModerationNotes(works, logs), pages, notice, stories,
                mock(UserRepository.class), trash);
    }

    private WebtoonWork 작품(String runId, boolean isPublic) {
        WebtoonWork w = WebtoonWork.started("job-" + runId, 7L, "uid", Instant.now());
        w.learnRun(runId);
        w.setPublic(isPublic);
        when(works.findFirstByRunId(runId)).thenReturn(Optional.of(w));
        return w;
    }

    private static ErrorCode 코드(Throwable e) {
        return ((BusinessException) e).getErrorCode();
    }

    @Test
    @DisplayName("비공개 처리: 공개를 내리고 그림을 옮기고, 사유를 남기고, 작가에게 알린다")
    void 비공개_처리() {
        WebtoonWork w = 작품("r1", true);
        when(notice.moderated(7L, "r1", "HIDE", "성인물", 30)).thenReturn(true);

        moderation.hide(1L, "r1", "  성인물 ");

        assertThat(w.isPublic()).isFalse();
        assertThat(w.isHiddenByAdmin()).isTrue();
        assertThat(w.getModerationReason()).isEqualTo("성인물");
        verify(pages).moveAll("r1", false);
        verify(notice).moderated(7L, "r1", "HIDE", "성인물", 30);
    }

    @Test
    @DisplayName("사유가 없으면 아무것도 안 한다 — 작가에게 그대로 보이는 글이라서")
    void 사유_없으면_거절() {
        WebtoonWork w = 작품("r1", true);

        assertThatThrownBy(() -> moderation.hide(1L, "r1", "  ")).extracting(WorkModerationTest::코드).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThatThrownBy(() -> moderation.remove(1L, "r1", null)).extracting(WorkModerationTest::코드).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThatThrownBy(() -> moderation.warn(1L, "r1", "")).extracting(WorkModerationTest::코드).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(w.isPublic()).isTrue();
        verify(logs, never()).save(any());
        verify(notice, never()).moderated(any(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("다시 공개는 처리 전 상태로 — 원래 비공개였던 작품은 비공개로 남는다")
    void 다시_공개는_원래대로() {
        WebtoonWork open = 작품("r1", true);
        moderation.hide(1L, "r1", "사유");
        moderation.unhide(1L, "r1", null);
        assertThat(open.isPublic()).isTrue();
        assertThat(open.getModeration()).isNull();
        verify(pages).moveAll("r1", true);

        WebtoonWork closed = 작품("r2", false);
        moderation.hide(1L, "r2", "사유");
        moderation.unhide(1L, "r2", null);
        assertThat(closed.isPublic()).isFalse();
        verify(pages, never()).moveAll("r2", true);
    }

    @Test
    @DisplayName("삭제 처리: 휴지통에 넣고 관리자 삭제로 표시, 되살리면 처음 공개 여부로 — 비공개 뒤 삭제해도")
    void 삭제와_되살리기() {
        WebtoonWork w = 작품("r1", true);
        moderation.hide(1L, "r1", "먼저 숨김");
        moderation.remove(1L, "r1", "삭제 사유");

        assertThat(w.isTrashed()).isTrue();
        assertThat(w.isRemovedByAdmin()).isTrue();
        verify(notice).moderated(7L, "r1", "REMOVE", "삭제 사유", 30);

        moderation.restore(1L, "r1", null);
        assertThat(w.isTrashed()).isFalse();
        assertThat(w.getModeration()).isNull();
        assertThat(w.isPublic()).isTrue();
    }

    @Test
    @DisplayName("경고는 작품을 그대로 두고 기록 · 알림만")
    void 경고() {
        WebtoonWork w = 작품("r1", true);

        moderation.warn(1L, "r1", "제목이 선정적");

        assertThat(w.isPublic()).isTrue();
        assertThat(w.getModeration()).isNull();
        verify(pages, never()).moveAll(anyString(), anyBoolean());
        verify(logs).save(any(ModerationLog.class));
        verify(notice).moderated(7L, "r1", "WARN", "제목이 선정적", 30);
    }

    @Test
    @DisplayName("예시 작품은 여기서 처리하지 않는다")
    void 예시는_거절() {
        WebtoonWork w = 작품("r1", true);
        w.markExample(true, 1);

        assertThatThrownBy(() -> moderation.hide(1L, "r1", "사유")).extracting(WorkModerationTest::코드).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThatThrownBy(() -> moderation.remove(1L, "r1", "사유")).extracting(WorkModerationTest::코드).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(w.isPublic()).isTrue();
    }

    @Test
    @DisplayName("게스트 작품은 알릴 곳이 없어도 처리는 된다")
    void 게스트_작품() {
        WebtoonWork w = WebtoonWork.started("job-g", null, "uid", Instant.now());
        w.learnRun("g1");
        when(works.findFirstByRunId("g1")).thenReturn(Optional.of(w));
        when(notice.moderated(eq(null), anyString(), anyString(), anyString(), anyInt())).thenReturn(false);

        moderation.hide(1L, "g1", "사유");

        assertThat(w.isHiddenByAdmin()).isTrue();
    }
}
