package com.lore.webtoon.work;

import com.lore.common.exception.BusinessException;
import com.lore.webtoon.runs.RunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 찜은 (작품, 계정)당 한 줄이다 — 두 번 눌러도 한 번, 없는 것을 취소해도 조용히.
 */
class RunLikeServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-26T03:00:00Z");

    private RunLikeRepository likes;
    private WebtoonWorkRepository works;
    private RunService runs;
    private RunLikeService service;

    @BeforeEach
    void setUp() {
        likes = mock(RunLikeRepository.class);
        works = mock(WebtoonWorkRepository.class);
        runs = mock(RunService.class);
        service = new RunLikeService(likes, works, runs, Clock.fixed(NOW, ZoneOffset.UTC));
        when(works.findFirstByRunId("r1")).thenReturn(Optional.of(mock(WebtoonWork.class)));
    }

    @Test
    @DisplayName("처음 찜하면 한 줄 저장하고 찜 수를 돌려준다")
    void firstLikeSaves() {
        when(likes.findByRunIdAndUserId("r1", 7L)).thenReturn(Optional.empty());
        when(likes.countByRunId("r1")).thenReturn(1L);

        assertThat(service.like(7L, "r1")).isEqualTo(1L);
        verify(likes).save(any(RunLike.class));
    }

    @Test
    @DisplayName("이미 찜한 작품을 또 찜해도 줄이 늘지 않는다")
    void secondLikeIsIdempotent() {
        when(likes.findByRunIdAndUserId("r1", 7L)).thenReturn(Optional.of(new RunLike("r1", 7L, NOW)));
        when(likes.countByRunId("r1")).thenReturn(1L);

        assertThat(service.like(7L, "r1")).isEqualTo(1L);
        verify(likes, never()).save(any());
    }

    @Test
    @DisplayName("없는 작품은 찜할 수 없다")
    void unknownRunRejected() {
        when(works.findFirstByRunId("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.like(7L, "nope")).isInstanceOf(BusinessException.class);
        verify(likes, never()).save(any());
    }

    @Test
    @DisplayName("찜 취소는 있으면 지우고, 없으면 조용히 넘어간다")
    void unlike() {
        RunLike row = new RunLike("r1", 7L, NOW);
        when(likes.findByRunIdAndUserId("r1", 7L)).thenReturn(Optional.of(row), Optional.empty());
        when(likes.countByRunId("r1")).thenReturn(0L);

        assertThat(service.unlike(7L, "r1")).isZero();
        verify(likes).delete(row);
        assertThat(service.unlike(7L, "r1")).isZero();   // 두 번째는 지울 것이 없다
    }

    @Test
    @DisplayName("찜 목록은 카드로 돌려주고, 그림이 없어진 작품은 뺀다")
    void likedCardsSkipGone() {
        when(likes.runIdsLikedBy(7L)).thenReturn(List.of("r1", "gone"));
        Map<String, Object> card = new LinkedHashMap<>(Map.of("run_id", "r1"));
        when(runs.cardOf("r1")).thenReturn(card);
        when(runs.cardOf("gone")).thenReturn(null);

        List<Map<String, Object>> out = service.likedCards(7L);
        assertThat(out).hasSize(1);
        assertThat(out.get(0)).containsEntry("liked", true);
    }

    @Test
    @DisplayName("로그인 안 했으면 「내가 찜한 것」은 빈 목록이다")
    void likedAmongAnonymous() {
        assertThat(service.likedAmong(null, List.of("r1"))).isEmpty();
        verify(likes, never()).likedAmong(any(), any());
    }

    @Test
    @DisplayName("휴지통에 든 작품은 찜할 수 없다(404) — 목록에서 안 보이는 작품이다(#157)")
    void 휴지통_작품은_찜_불가() {
        WebtoonWork trashed = mock(WebtoonWork.class);
        when(trashed.isTrashed()).thenReturn(true);
        when(works.findFirstByRunId("t1")).thenReturn(Optional.of(trashed));

        assertThatThrownBy(() -> service.like(7L, "t1"))
                .isInstanceOf(com.lore.common.exception.BusinessException.class);
    }
}
