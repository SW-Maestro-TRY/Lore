package com.lore.webtoon.art;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 주인 없는 그림 점검.
 *
 * <b>이 검사가 지키는 것</b> — DB 어딘가에 주소가 있거나, 막 올라왔거나, 이미 대기열에 있는
 * 그림은 대기열에 안 넣는가. 그리고 세기만 할 때는 아무것도 안 바꾸는가.
 */
class ArtOrphanScanTest {

    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
    private static final Instant OLD = NOW.minus(Duration.ofDays(10));

    private ArtInventory inventory;
    private ArtReferences references;
    private ArtDiscardRepository queue;
    private ArtDiscards discards;
    private ArtOrphanScan scan;

    @BeforeEach
    void setUp() {
        inventory = mock(ArtInventory.class);
        references = mock(ArtReferences.class);
        queue = mock(ArtDiscardRepository.class);
        discards = mock(ArtDiscards.class);
        when(inventory.ready()).thenReturn(true);
        when(inventory.list("images/webtoon/")).thenReturn(List.of(
                new ArtInventory.Item("images/webtoon/used", 100, OLD),
                new ArtInventory.Item("images/webtoon/orphan1", 200, OLD),
                new ArtInventory.Item("images/webtoon/fresh", 300, NOW.minus(Duration.ofHours(3)))));
        when(inventory.list("private/webtoon/")).thenReturn(List.of(
                new ArtInventory.Item("private/webtoon/queued", 400, OLD),
                new ArtInventory.Item("private/webtoon/char/orphan2.png", 500, OLD.minus(Duration.ofDays(30)))));
        when(references.everywhere()).thenReturn(Set.of("images/webtoon/used"));
        when(queue.allKeys()).thenReturn(List.of("private/webtoon/queued"));
        scan = new ArtOrphanScan(inventory, references, queue, discards);
    }

    @Test
    @DisplayName("세기만 하면 아무것도 안 적는다")
    void countOnly() {
        ArtOrphanScan.Report r = scan.run(false, NOW);

        assertThat(r.files()).isEqualTo(5);
        assertThat(r.referenced()).isEqualTo(1);
        assertThat(r.recent()).isEqualTo(1);     // 막 올라와 아직 DB 에 안 적혔을 수 있다
        assertThat(r.queued()).isEqualTo(1);
        assertThat(r.orphans()).isEqualTo(2);
        assertThat(r.orphanBytes()).isEqualTo(700);
        assertThat(r.added()).isZero();
        assertThat(r.byMonth()).containsEntry("2026-10", 1).containsEntry("2026-09", 1);
        assertThat(r.sample()).extracting(m -> m.get("key"))
                .containsExactly("images/webtoon/orphan1", "private/webtoon/char/orphan2.png");
        verify(discards, never()).discardAll(anyCollection(), anyString(), any());
    }

    @Test
    @DisplayName("넣기를 하면 주인 없는 것만 대기열에 적는다")
    void applyQueuesOnlyOrphans() {
        when(discards.discardAll(anyCollection(), anyString(), any())).thenReturn(2);

        ArtOrphanScan.Report r = scan.run(true, NOW);

        verify(discards).discardAll(List.of("images/webtoon/orphan1", "private/webtoon/char/orphan2.png"),
                ArtDiscards.ORPHAN, NOW);
        assertThat(r.added()).isEqualTo(2);
    }

    @Test
    @DisplayName("DB 를 끝까지 못 훑으면 아무것도 안 적는다 — 못 본 칸이 가리키는 그림이 지워진다")
    void failsClosed() {
        when(references.everywhere()).thenThrow(new RuntimeException("한 표를 못 읽었습니다"));

        assertThatThrownBy(() -> scan.run(true, NOW)).isInstanceOf(RuntimeException.class);
        verify(discards, never()).discardAll(anyCollection(), anyString(), any());
    }

    @Test
    @DisplayName("창고(버킷)가 없는 환경에서는 막는다")
    void needsBucket() {
        when(inventory.ready()).thenReturn(false);

        assertThatThrownBy(() -> scan.run(false, NOW)).isInstanceOf(IllegalStateException.class);
    }
}
