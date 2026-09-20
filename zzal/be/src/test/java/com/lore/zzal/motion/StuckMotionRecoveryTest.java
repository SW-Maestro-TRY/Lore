package com.lore.zzal.motion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 기동 복구 — 실패 주입(verify-failure-paths).
 *
 * ★ 여기서 지키는 것은 <b>"아무도 안 보는 상태"를 없애는 것</b>이다. 밤 스위프가 집어 간 자리({@code BAKING})는
 *   다음 밤 계획도(NONE·FAILED 만 본다) 스위프의 집기도(QUEUED 만 본다) 보지 않아서, 굽기가 끊기면 영구 고착이었다
 *   (2026-09-05 리뷰 주입 INJ-B·C에서 실제로 재현됐다).
 */
@DisplayName("기동 복구 — 멈춘 모션 회수")
class StuckMotionRecoveryTest {

    private static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");
    private static final LocalDate NIGHT = LocalDate.of(2026, 9, 5);

    private final MotionCatalog catalog = new MotionCatalog("", "", "v1");
    private ZzalMotionRepository motionRepository;
    private MotionService motionService;
    private StuckMotionRecovery recovery;

    @BeforeEach
    void setUp() {
        motionRepository = mock(ZzalMotionRepository.class);
        motionService = mock(MotionService.class);
        when(motionRepository.findByStatusAndUpdatedAtBefore(any(), any())).thenReturn(List.of());
        recovery = new StuckMotionRecovery(motionRepository, motionService, 15, 60);
    }

    private ZzalMotion motion(long id, int seq, MotionStatus status) {
        ZzalMotion m = ZzalMotion.forCatalog(7L, catalog.bySeq(seq).orElseThrow(), NOW);
        m.queue(NIGHT);
        ReflectionTestUtils.setField(m, "id", id);
        ReflectionTestUtils.setField(m, "status", status);
        return m;
    }

    @Test
    @DisplayName("★★ 집힌 채 멈춘 자리(BAKING)를 큐로 되돌린다 — 여기서 안 집으면 아무도 안 본다")
    void releasesStalledBaking() {
        ZzalMotion stalled = motion(1L, 101, MotionStatus.BAKING);
        ReflectionTestUtils.setField(stalled, "claimedBy", "died-server");
        ReflectionTestUtils.setField(stalled, "claimedAt", NOW);
        when(motionRepository.findByStatusAndUpdatedAtBefore(eq(MotionStatus.BAKING), any()))
                .thenReturn(List.of(stalled));

        recovery.recover();

        assertThat(stalled.getStatus()).isEqualTo(MotionStatus.QUEUED);
        assertThat(stalled.getNightOf()).isEqualTo(NIGHT);          // 밤은 그대로 — 이월분으로 우선권을 갖는다
        assertThat(ReflectionTestUtils.getField(stalled, "claimedBy")).isNull();
        // ★ 여기서 바로 굽지 않는다 — 순서·상한(K)은 스위프가 쥐고 있다
        verify(motionService, never()).bake(anyLong());
        verify(motionService, never()).bakeNow(anyLong());
    }

    @Test
    @DisplayName("v1 잔재(PENDING)는 그 자리에서 이어 굽는다")
    void resumesLegacyPending() {
        ZzalMotion legacy = motion(2L, 1, MotionStatus.PENDING);
        when(motionRepository.findByStatusAndUpdatedAtBefore(eq(MotionStatus.PENDING), any()))
                .thenReturn(List.of(legacy));

        recovery.recover();

        verify(motionService).bake(2L);
    }

    @Test
    @DisplayName("★★ 맥미니가 안 가져간 자리(LOCAL_REQUESTED)를 큐로 되돌린다 — 안 그러면 영구 고착 (P-13)")
    void releasesAbandonedLocalRequest() {
        ZzalMotion abandoned = motion(3L, 101, MotionStatus.LOCAL_REQUESTED);
        ReflectionTestUtils.setField(abandoned, "regenRound", 2);
        when(motionRepository.findByStatusAndUpdatedAtBefore(eq(MotionStatus.LOCAL_REQUESTED), any()))
                .thenReturn(List.of(abandoned));

        recovery.recover();

        assertThat(abandoned.getStatus()).isEqualTo(MotionStatus.QUEUED);
        // ★ 라운드는 그대로 — 0 으로 되돌리면 "굽고 → 실패 → 맥미니 → 고착 → 회수" 가 끝없이 돌아 유료 호출이 계속 나간다
        assertThat(abandoned.getRegenRound()).isEqualTo(2);
        assertThat(abandoned.getNightOf()).isEqualTo(NIGHT);
        verify(motionService, never()).bake(anyLong());
        verify(motionService, never()).bakeNow(anyLong());
    }

    @Test
    @DisplayName("★ 맥미니 유예는 서버 유예와 다른 값이다 — 같은 값을 쓰면 굽는 중에 뺏어 온다")
    void localGraceIsItsOwnValue() {
        recovery.recover();
        java.time.Instant after = java.time.Instant.now();

        org.mockito.ArgumentCaptor<java.time.Instant> baking = org.mockito.ArgumentCaptor.forClass(java.time.Instant.class);
        org.mockito.ArgumentCaptor<java.time.Instant> local = org.mockito.ArgumentCaptor.forClass(java.time.Instant.class);
        verify(motionRepository).findByStatusAndUpdatedAtBefore(eq(MotionStatus.BAKING), baking.capture());
        verify(motionRepository).findByStatusAndUpdatedAtBefore(eq(MotionStatus.LOCAL_REQUESTED), local.capture());

        // 15분 · 60분 — 맥미니 쪽이 더 넉넉하다(cutoff 는 호출 시각 기준이라 그 사이에 흐른 시간만큼 여유를 둔다)
        assertThat(java.time.Duration.between(baking.getValue(), after).toSeconds())
                .isBetween(15 * 60L, 15 * 60L + 30);
        assertThat(java.time.Duration.between(local.getValue(), after).toSeconds())
                .isBetween(60 * 60L, 60 * 60L + 30);
    }

    @Test
    @DisplayName("★ 유예 안에 있는 것은 안 건드린다 — 정상적으로 굽고 있는 것을 두 번 구우면 돈이 두 배다")
    void gracePeriodIsAsked() {
        recovery.recover();
        // 조회 자체가 "유예보다 오래된 것" 으로만 나간다(cutoff 는 지금 - 15분)
        verify(motionRepository).findByStatusAndUpdatedAtBefore(eq(MotionStatus.BAKING), any());
        verify(motionRepository).findByStatusAndUpdatedAtBefore(eq(MotionStatus.PENDING), any());
        verify(motionRepository).findByStatusAndUpdatedAtBefore(eq(MotionStatus.LOCAL_REQUESTED), any());
        verify(motionService, never()).bake(anyLong());
    }
}
