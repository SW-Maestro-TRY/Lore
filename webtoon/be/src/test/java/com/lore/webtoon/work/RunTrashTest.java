package com.lore.webtoon.work;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.webtoon.art.PageStore;
import com.lore.webtoon.runs.RunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 휴지통(#157) — 지우면 표시만 하고, 되살릴 수 있고, 기간이 지나면 영구 삭제한다.
 */
class RunTrashTest {

    private WebtoonWorkRepository works;
    private RunDeleteService deleter;
    private WorkLedger ledger;
    private PageStore pages;
    private RunTrash trash;

    @BeforeEach
    void setUp() {
        works = mock(WebtoonWorkRepository.class);
        deleter = mock(RunDeleteService.class);
        ledger = mock(WorkLedger.class);
        pages = mock(PageStore.class);
        trash = new RunTrash(works, deleter, ledger, pages, mock(RunService.class), 30);
    }

    private WebtoonWork 작품(String runId) {
        WebtoonWork w = WebtoonWork.started("job-" + runId, 7L, "uid", Instant.now());
        w.learnRun(runId);
        return w;
    }

    @Test
    @DisplayName("지우면 영구 삭제하지 않고 휴지통에 넣고, 그림을 비공개 자리로 옮긴다")
    void 휴지통에_넣는다() {
        WebtoonWork w = 작품("r1");
        when(deleter.checkMayDelete(7L, "r1")).thenReturn(w);

        RunTrash.Trashed got = trash.trash(7L, "r1");

        assertThat(w.isTrashed()).isTrue();
        assertThat(got.keepDays()).isEqualTo(30);
        assertThat(Duration.between(got.deletedAt(), got.purgeAt())).isEqualTo(Duration.ofDays(30));
        verify(works).save(w);
        verify(pages).moveAll("r1", false);
        verify(deleter, never()).purge(anyString());
    }

    @Test
    @DisplayName("지울 수 없는 작품이면(남의 것·예시·만드는 중) 아무것도 안 바꾼다")
    void 권한이_없으면_그대로() {
        when(deleter.checkMayDelete(9L, "r1"))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "내가 만든 작품만 지울 수 있습니다"));

        assertThatThrownBy(() -> trash.trash(9L, "r1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
        verify(works, never()).save(any());
        verify(pages, never()).moveAll(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("이미 휴지통에 있으면 지운 시각을 바꾸지 않는다 — 두 번 눌러도 기간이 늘지 않는다")
    void 두_번_지워도_그대로() {
        WebtoonWork w = 작품("r1");
        Instant first = Instant.now().minus(Duration.ofDays(3));
        w.trash(first);
        when(deleter.checkMayDelete(7L, "r1")).thenReturn(w);

        assertThat(trash.trash(7L, "r1").deletedAt()).isEqualTo(first);
        verify(works, never()).save(any());
    }

    @Test
    @DisplayName("되살리면 목록으로 돌아가고, 공개 작품은 그림도 공개 자리로 돌린다")
    void 되살린다() {
        WebtoonWork w = 작품("r1");
        w.trash(Instant.now());
        when(works.findFirstByRunId("r1")).thenReturn(Optional.of(w));
        when(ledger.mayChange("r1", 7L)).thenReturn(true);

        assertThat(trash.restore(7L, "r1")).isTrue();
        assertThat(w.isTrashed()).isFalse();
        verify(pages).moveAll("r1", true);
    }

    @Test
    @DisplayName("비공개였던 작품은 되살려도 그림을 공개 자리로 안 옮긴다")
    void 비공개는_비공개로() {
        WebtoonWork w = 작품("r1");
        w.setPublic(false);
        w.trash(Instant.now());
        when(works.findFirstByRunId("r1")).thenReturn(Optional.of(w));
        when(ledger.mayChange("r1", 7L)).thenReturn(true);

        trash.restore(7L, "r1");

        verify(pages, never()).moveAll(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("남의 휴지통 작품은 못 되살린다(403)")
    void 남의_것은_못_되살린다() {
        WebtoonWork w = 작품("r1");
        w.trash(Instant.now());
        when(works.findFirstByRunId("r1")).thenReturn(Optional.of(w));
        when(ledger.mayChange("r1", 9L)).thenReturn(false);

        assertThatThrownBy(() -> trash.restore(9L, "r1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(w.isTrashed()).isTrue();
    }

    @Test
    @DisplayName("기간이 지난 것은 예전 영구 삭제로 지우고, 한 편이 실패해도 나머지는 지운다")
    void 기간이_지나면_영구_삭제() {
        when(works.trashedBefore(any())).thenReturn(List.of(작품("r1"), 작품("r2"), 작품("r3")));
        doThrow(new RuntimeException("S3 가 잠깐 안 됨")).when(deleter).purge("r2");

        trash.purgeExpired();

        verify(deleter).purge("r1");
        verify(deleter).purge("r2");
        verify(deleter).purge("r3");
    }
}
