package com.lore.webtoon.work;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.retention.BucketPresence;
import com.lore.common.s3.S3Storage;
import com.lore.webtoon.job.WebtoonJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 누가 지울 수 있고, 무엇을 어떤 순서로 지우는가. 표 자체는 가짜다.
 */
class RunDeleteServiceTest {

    private WebtoonWorkRepository works;
    private WebtoonJobRepository jobs;
    private RunDeleteRepository rows;
    private WorkLedger ledger;
    private S3Storage storage;
    private RunDeleteService service;

    @BeforeEach
    void setUp() {
        works = mock(WebtoonWorkRepository.class);
        jobs = mock(WebtoonJobRepository.class);
        rows = mock(RunDeleteRepository.class);
        ledger = mock(WorkLedger.class);
        storage = mock(S3Storage.class);
        BucketPresence bucket = mock(BucketPresence.class);
        when(bucket.exists()).thenReturn(true);
        service = new RunDeleteService(works, jobs, rows, ledger, storage, bucket);
    }

    private WebtoonWork 작품(String browserUid) {
        WebtoonWork w = WebtoonWork.started("job-1", 7L, browserUid, java.time.Instant.now());
        w.learnRun("r1");
        return w;
    }

    @Test
    @DisplayName("없는 작품은 404")
    void notFound() {
        when(works.findFirstByRunId("없음")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(7L, "없음"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
        verify(storage, never()).delete(anyList());
    }

    @Test
    @DisplayName("남의 작품은 403 이고 아무것도 안 지운다")
    void notOwner() {
        when(works.findFirstByRunId("r1")).thenReturn(Optional.of(작품("uidA")));
        when(ledger.mayChange("r1", 9L)).thenReturn(false);
        assertThatThrownBy(() -> service.delete(9L, "r1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
        verify(storage, never()).delete(anyList());
        verify(rows, never()).deletePages(anyString());
    }

    @Test
    @DisplayName("예시 작품은 주인 확인 전에 막는다")
    void exampleRefused() {
        when(works.findFirstByRunId("r1")).thenReturn(Optional.of(작품(ExampleWorks.SEED_UID)));
        when(ledger.mayChange(any(), any())).thenReturn(true);
        assertThatThrownBy(() -> service.delete(7L, "r1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
        verify(rows, never()).deleteWorks(anyString());
    }

    @Test
    @DisplayName("만드는 중이면 지우지 않는다")
    void inProgressRefused() {
        when(works.findFirstByRunId("r1")).thenReturn(Optional.of(작품("uidA")));
        when(ledger.mayChange("r1", 7L)).thenReturn(true);
        when(jobs.existsByRunIdAndStatusIn(eq("r1"), any())).thenReturn(true);
        assertThatThrownBy(() -> service.delete(7L, "r1")).isInstanceOf(BusinessException.class);
        verify(storage, never()).delete(anyList());
        verify(rows, never()).deletePages(anyString());
    }

    @Test
    @DisplayName("그림을 먼저 지우고 그다음 행을 지운다")
    void imagesBeforeRows() {
        when(works.findFirstByRunId("r1")).thenReturn(Optional.of(작품("uidA")));
        when(ledger.mayChange("r1", 7L)).thenReturn(true);
        when(jobs.existsByRunIdAndStatusIn(eq("r1"), any())).thenReturn(false);
        when(rows.pageKeys("r1")).thenReturn(List.of("k1", "k2"));
        when(rows.bakedKeys("r1")).thenReturn(List.of("k3"));
        when(storage.delete(anyList())).thenReturn(3);
        when(rows.deletePages("r1")).thenReturn(2);
        when(rows.deleteWorks("r1")).thenReturn(1);
        when(rows.deleteJobs("r1")).thenReturn(1);

        RunDeleteService.Deleted out = service.delete(7L, "r1");

        assertThat(out.images()).isEqualTo(3);
        assertThat(out.rows()).isEqualTo(4);
        InOrder order = inOrder(storage, rows);
        order.verify(storage).delete(List.of("k1", "k2", "k3"));
        order.verify(rows).deletePages("r1");
        order.verify(rows).deleteWorks("r1");
        order.verify(rows).deleteJobs("r1");
    }

    @Test
    @DisplayName("그림이 하나도 없으면 S3 를 부르지 않는다")
    void noImagesNoS3() {
        when(works.findFirstByRunId("r1")).thenReturn(Optional.of(작품("uidA")));
        when(ledger.mayChange("r1", 7L)).thenReturn(true);
        when(rows.pageKeys("r1")).thenReturn(List.of());
        when(rows.bakedKeys("r1")).thenReturn(List.of());

        service.delete(7L, "r1");

        verify(storage, never()).delete(anyList());
        verify(rows).deleteWorks("r1");
    }
}
