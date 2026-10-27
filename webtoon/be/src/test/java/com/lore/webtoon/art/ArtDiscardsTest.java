package com.lore.webtoon.art;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 대기열에 적는 자리 — 같은 그림을 두 번 적어 지울 날을 미루지 않는가. */
class ArtDiscardsTest {

    private static final Instant T = Instant.parse("2026-10-27T12:00:00Z");

    @Test
    @DisplayName("이미 적힌 것 · 빈 키 · 겹친 키는 빼고 새것만 적는다")
    @SuppressWarnings("unchecked")
    void onlyFresh() {
        ArtDiscardRepository repo = mock(ArtDiscardRepository.class);
        when(repo.findAllById(any())).thenReturn(List.of(new ArtDiscard("a", ArtDiscards.REPLACED, T.minusSeconds(99))));
        ArtDiscards discards = new ArtDiscards(repo);

        int n = discards.discardAll(Arrays.asList("a", "b", "", null, "b", "c"), ArtDiscards.ORPHAN, T);

        assertThat(n).isEqualTo(2);
        ArgumentCaptor<List<ArtDiscard>> saved = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(ArtDiscard::getS3Key).containsExactly("b", "c");
        assertThat(saved.getValue()).allSatisfy(d -> {
            assertThat(d.getReason()).isEqualTo(ArtDiscards.ORPHAN);
            assertThat(d.getDiscardedAt()).isEqualTo(T);
            assertThat(d.isNew()).isTrue();      // 새로 만든 것은 먼저 읽어 보지 않고 바로 넣는다
        });
    }

    @Test
    @DisplayName("한 장짜리도 같은 규칙이다 — null 이면 아무것도 안 적는다")
    void single() {
        ArtDiscardRepository repo = mock(ArtDiscardRepository.class);
        ArtDiscards discards = new ArtDiscards(repo);

        discards.discard(null, ArtDiscards.REPLACED, T);

        verify(repo).saveAll(List.of());
    }
}
