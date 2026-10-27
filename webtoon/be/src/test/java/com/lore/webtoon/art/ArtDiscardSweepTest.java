package com.lore.webtoon.art;

import com.lore.common.s3.S3Storage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 버린 그림을 지우는 자리.
 *
 * <b>이 검사가 지키는 것</b> — 아직 쓰는 그림을 지우지 않는가, 그리고 지우다 실패하면
 * 대기열을 남겨 다음 날 다시 지우는가.
 */
class ArtDiscardSweepTest {

    private static final Instant NOW = Instant.parse("2026-11-05T20:30:00Z");
    private static final Instant CUT = NOW.minus(Duration.ofDays(7));

    private ArtDiscardRepository repo;
    private S3Storage storage;

    @BeforeEach
    void setUp() {
        repo = mock(ArtDiscardRepository.class);
        storage = mock(S3Storage.class);
    }

    private ArtDiscardSweep onServer() {
        return new ArtDiscardSweep(repo, storage, "lore-prod-contents", "", "");
    }

    private static ArtDiscard d(String key) {
        return new ArtDiscard(key, ArtDiscards.REPLACED, CUT.minusSeconds(60));
    }

    @Test
    @DisplayName("7일 지난 것 가운데 아무 표도 안 쓰는 것만 지우고, 대기열에서는 다 뺀다")
    void deletesOnlyUnused() {
        when(repo.findByDiscardedAtBeforeOrderByDiscardedAtAsc(eq(CUT), any(Pageable.class)))
                .thenReturn(List.of(d("a"), d("b"), d("c")));
        when(repo.pagesUsing(anyCollection())).thenReturn(List.of("b"));

        ArtDiscardSweep.Result r = onServer().runOnce(NOW);

        // b 는 그새 다시 쓰이게 됐다 — 지우면 그 장이 깨진다.
        verify(storage).delete(List.of("a", "c"));
        verify(repo).deleteAllByIdInBatch(List.of("a", "b", "c"));
        assertThat(r.deleted()).isEqualTo(2);
        assertThat(r.stillUsed()).isEqualTo(1);
    }

    @Test
    @DisplayName("웹툰 표 다섯 곳(장·구운 장·캐릭터·조연 시트·올린 사진)을 모두 본다")
    void checksEveryTable() {
        when(repo.findByDiscardedAtBeforeOrderByDiscardedAtAsc(eq(CUT), any(Pageable.class)))
                .thenReturn(List.of(d("p"), d("b"), d("c"), d("s"), d("u"), d("x")));
        when(repo.pagesUsing(anyCollection())).thenReturn(List.of("p"));
        when(repo.bakedUsing(anyCollection())).thenReturn(List.of("b"));
        when(repo.charactersUsing(anyCollection())).thenReturn(List.of("c"));
        when(repo.castSheetsUsing(anyCollection())).thenReturn(List.of("s"));
        when(repo.uploadsUsing(anyCollection())).thenReturn(List.of("u"));

        onServer().runOnce(NOW);

        verify(storage).delete(List.of("x"));
    }

    @Test
    @DisplayName("S3 를 못 지우면 대기열을 그대로 둔다 — 다음 날 다시 지운다")
    void keepsQueueWhenS3Fails() {
        when(repo.findByDiscardedAtBeforeOrderByDiscardedAtAsc(eq(CUT), any(Pageable.class)))
                .thenReturn(List.of(d("a")));
        doThrow(new RuntimeException("S3 가 잠깐 안 됩니다")).when(storage).delete(anyList());

        ArtDiscardSweep.Result r = onServer().runOnce(NOW);

        verify(repo, never()).deleteAllByIdInBatch(any());
        assertThat(r.deleted()).isZero();
    }

    @Test
    @DisplayName("다 쓰이고 있으면 S3 는 안 부르고 대기열에서만 뺀다")
    void allUsedNoS3() {
        when(repo.findByDiscardedAtBeforeOrderByDiscardedAtAsc(eq(CUT), any(Pageable.class)))
                .thenReturn(List.of(d("a")));
        when(repo.uploadsUsing(anyCollection())).thenReturn(List.of("a"));

        onServer().runOnce(NOW);

        verify(storage, never()).delete(anyList());
        verify(repo).deleteAllByIdInBatch(List.of("a"));
    }

    @Test
    @DisplayName("1000개씩 나눠 끝까지 지운다")
    void batches() {
        List<ArtDiscard> full = new ArrayList<>();
        for (int i = 0; i < ArtDiscardSweep.BATCH; i++) {
            full.add(d("k" + i));
        }
        when(repo.findByDiscardedAtBeforeOrderByDiscardedAtAsc(eq(CUT), any(Pageable.class)))
                .thenReturn(full, List.of(d("last")));

        ArtDiscardSweep.Result r = onServer().runOnce(NOW);

        verify(storage, times(2)).delete(anyList());
        assertThat(r.deleted()).isEqualTo(ArtDiscardSweep.BATCH + 1);
    }

    @Test
    @DisplayName("로컬(버킷 없음 · 창고가 이 기계)이면 아무것도 안 한다")
    void localDoesNothing() {
        new ArtDiscardSweep(repo, storage, "", "", "").runOnce(NOW);
        new ArtDiscardSweep(repo, storage, "lore-dev-contents", "http://localhost:9000", "").runOnce(NOW);

        verifyNoInteractions(repo, storage);
    }

    @Test
    @DisplayName("keep 을 적으면 그 값을 따른다")
    void keepOverrides() {
        when(repo.findByDiscardedAtBeforeOrderByDiscardedAtAsc(eq(CUT), any(Pageable.class)))
                .thenReturn(List.of(d("a")));

        new ArtDiscardSweep(repo, storage, "lore-prod-contents", "", "true").runOnce(NOW);
        verifyNoInteractions(repo, storage);

        new ArtDiscardSweep(repo, storage, "lore-dev-contents", "http://localhost:9000", "false").runOnce(NOW);
        verify(storage).delete(List.of("a"));
    }

    @Test
    @DisplayName("매일 05:30(한국 시각)에 돈다 — 다른 새벽 정리와 시각이 안 겹친다")
    void runsAtFiveThirtyKst() {
        ZoneId kst = ZoneId.of(ArtDiscardSweep.ZONE);
        ZonedDateTime next = CronExpression.parse(ArtDiscardSweep.CRON)
                .next(ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, kst));

        assertThat(next.toLocalTime()).isEqualTo(LocalTime.of(5, 30));
        // 04:30 작품 폴더 · 04:50 탈퇴 파기 · 05:10 휴지통 · 05:20 캐릭터 휴지통 · 05:40~05:50 기간 파기
        assertThat(next.toLocalTime()).isNotIn(LocalTime.of(4, 30), LocalTime.of(4, 50), LocalTime.of(5, 10),
                LocalTime.of(5, 20), LocalTime.of(5, 40), LocalTime.of(5, 45), LocalTime.of(5, 50));
    }
}
