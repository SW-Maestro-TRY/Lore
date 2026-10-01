package com.lore.webtoon.retention;

import com.lore.common.retention.BucketPresence;
import com.lore.common.s3.S3Storage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 탈퇴한 사람의 웹툰 데이터를 지우는 자리.
 *
 * <b>이 검사가 지키는 것</b> — 그림이 남지 않는가. S3 키는 DB 행에만 적혀
 * 있어서, 행을 먼저 지우면 어느 그림을 지워야 하는지 알 길이 사라진다.
 */
class WebtoonPurgeTest {

    private static final Long USER = 7L;

    private WebtoonPurgeRepository rows;
    private S3Storage storage;

    @BeforeEach
    void setUp() {
        rows = mock(WebtoonPurgeRepository.class);
        storage = mock(S3Storage.class);
        when(rows.jobRunIdsOf(USER)).thenReturn(List.of("run-a", "run-b"));
        when(rows.workRunIdsOf(USER)).thenReturn(List.of("run-b", "run-c"));
        when(rows.characterKeys(USER)).thenReturn(List.of("images/webtoon/char/c1.png"));
        when(rows.pageKeys(anyList())).thenReturn(List.of("images/webtoon/p1.png"));
        when(rows.bakedKeys(anyList())).thenReturn(List.of("images/webtoon/b1.png"));
    }

    private WebtoonPurge purge(boolean hasBucket) {
        return new WebtoonPurge(rows, mock(com.lore.webtoon.work.RunLikeRepository.class), storage, bucket(hasBucket));
    }

    private static BucketPresence bucket(boolean exists) {
        return new BucketPresence(exists ? "lore-contents" : "");
    }

    @Test
    @DisplayName("두 표에 흩어진 작품 번호를 겹치지 않게 합친다")
    void mergesRunIdsFromBothTables() {
        purge(true).purge(USER);

        ArgumentCaptor<List<String>> ids = ArgumentCaptor.captor();
        verify(rows).deletePages(ids.capture());
        // job 쪽 a·b 와 work 쪽 b·c — b 가 두 번 들어가면 안 된다.
        assertThat(ids.getValue()).containsExactly("run-a", "run-b", "run-c");
    }

    @Test
    @DisplayName("그림을 먼저 지우고 그 다음에 행을 지운다")
    void deletesArtBeforeRows() {
        purge(true).purge(USER);

        // 행이 먼저 사라지면 어느 그림을 지워야 하는지 알 길이 없어진다.
        InOrder order = inOrder(storage, rows);
        order.verify(storage).delete(anyList());
        order.verify(rows).deletePages(anyList());
    }

    @Test
    @DisplayName("캐릭터 그림과 장 그림을 모두 지운다")
    void deletesEveryKind() {
        purge(true).purge(USER);

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.captor();
        verify(storage).delete(keys.capture());
        assertThat(keys.getValue()).containsExactlyInAnyOrder(
                "images/webtoon/char/c1.png",
                "images/webtoon/p1.png",
                "images/webtoon/b1.png");
    }

    @Test
    @DisplayName("버킷이 없으면(로컬) 그림을 지우지 않는다")
    void localKeepsArt() {
        purge(false).purge(USER);

        // 올라간 곳이 없으므로 지우는 순간 영영 사라진다.
        verify(storage, never()).delete(anyList());
        // 행은 그래도 지운다 — DB 는 로컬에도 있고 되살릴 수 있다.
        verify(rows).deleteWorks(USER);
    }

    @Test
    @DisplayName("작품이 하나도 없어도 사람에 달린 것은 지운다")
    void noRunsStillPurgesUserRows() {
        when(rows.jobRunIdsOf(USER)).thenReturn(List.of());
        when(rows.workRunIdsOf(USER)).thenReturn(List.of());
        when(rows.characterKeys(USER)).thenReturn(List.of());

        purge(true).purge(USER);

        verify(rows, never()).deletePages(anyList());
        verify(rows).deleteWorks(USER);
        verify(rows).deleteJobs(USER);
        verify(rows).deleteCharacters(USER);
        verify(rows).deleteBrowserLinks(USER);
        verify(rows).deleteNotifySettings(USER);
        // 지울 키가 없으면 S3 를 부르지 않는다.
        verify(storage, never()).delete(anyList());
    }

    @Test
    @DisplayName("지운 행 수를 합쳐서 돌려준다")
    void reportsRowCount() {
        when(rows.deleteWorks(USER)).thenReturn(2);
        when(rows.deleteJobs(USER)).thenReturn(3);

        assertThat(purge(true).purge(USER)).isGreaterThanOrEqualTo(5);
    }
}
