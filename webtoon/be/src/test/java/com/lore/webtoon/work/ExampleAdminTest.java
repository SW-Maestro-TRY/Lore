package com.lore.webtoon.work;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.webtoon.art.PageStore;
import com.lore.webtoon.story.StoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 예시 작품 관리(#614) — 지정 · 해제 · 공개 · 순서 · 내리기. 비공개로 돌리면 <b>그림도 옮긴다.</b>
 */
class ExampleAdminTest {

    private WorkLedger ledger;
    private WebtoonWorkRepository works;
    private PageStore pages;
    private ExampleAdmin admin;
    private WebtoonWork work;

    @BeforeEach
    void 세운다() {
        ledger = mock(WorkLedger.class);
        works = mock(WebtoonWorkRepository.class);
        pages = mock(PageStore.class);
        StoryStore stories = mock(StoryStore.class);
        when(stories.chosenOf(anyString())).thenReturn(Optional.empty());
        work = mock(WebtoonWork.class);
        when(work.getRunId()).thenReturn("run-1");
        when(work.isPublic()).thenReturn(true);
        when(work.isTrashed()).thenReturn(false);
        when(works.findFirstByRunId("run-1")).thenReturn(Optional.of(work));
        admin = new ExampleAdmin(ledger, works, pages, stories);
    }

    @Test
    @DisplayName("없는 작품이면 404")
    void 없는_작품은_404() {
        assertThatThrownBy(() -> admin.update("없음", true, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("예시로 지정하고 순서를 준다")
    void 지정하고_순서를_준다() {
        admin.update("run-1", true, null, 3);
        verify(ledger).markExample("run-1", true, 3);
    }

    @Test
    @DisplayName("예시가 아닌 작품에 순서만 주면 거절한다")
    void 예시가_아니면_순서를_못_준다() {
        when(work.isExample()).thenReturn(false);
        assertThatThrownBy(() -> admin.update("run-1", null, null, 1)).isInstanceOf(BusinessException.class);
        verify(ledger, never()).markExample(anyString(), anyBoolean(), any());
    }

    @Test
    @DisplayName("해제하면 순서도 비운다")
    void 해제하면_순서를_비운다() {
        admin.update("run-1", false, null, null);
        verify(ledger).markExample("run-1", false, null);
    }

    @Test
    @DisplayName("비공개로 돌리면 DB 만이 아니라 그림도 비공개 자리로 옮긴다")
    void 비공개는_그림도_옮긴다() {
        admin.update("run-1", null, false, null);
        verify(ledger).setPublic("run-1", false);
        verify(pages).moveAll("run-1", false);
    }

    @Test
    @DisplayName("비공개인 작품을 예시로 지정하면서 공개 여부를 안 정하면 공개로 둔다")
    void 비공개를_지정하면_공개로() {
        when(work.isPublic()).thenReturn(false);
        admin.update("run-1", true, null, null);
        verify(ledger).setPublic("run-1", true);
        verify(pages).moveAll("run-1", true);
    }

    @Test
    @DisplayName("내리기는 예시를 해제하고 비공개로 돌린다 — 작품을 지우지는 않는다")
    void 내리기() {
        admin.takeDown("run-1");
        verify(ledger).markExample("run-1", false, null);
        verify(ledger).setPublic("run-1", false);
        verify(pages).moveAll("run-1", false);
    }

    @Test
    @DisplayName("그림을 못 옮겨도 던지지 않는다 — DB 는 이미 바뀌었고 크게 남긴다")
    void 옮기기가_실패해도_던지지_않는다() {
        org.mockito.Mockito.doThrow(new RuntimeException("s3")).when(pages).moveAll(anyString(), anyBoolean());
        assertThat(admin.update("run-1", null, false, null)).isNotNull();
    }

    @Test
    @DisplayName("목록은 작품 번호가 있는 예시만 낸다")
    void 목록() {
        when(ledger.examples()).thenReturn(List.of(work));
        assertThat(admin.list()).hasSize(1);
        assertThat(admin.list().get(0).runId()).isEqualTo("run-1");
    }
}
