package com.lore.zzal.admin.retry;

import com.lore.common.s3.S3Service;
import com.lore.zzal.admin.AdminCaller;
import com.lore.zzal.admin.AdminGuard;
import com.lore.zzal.admin.AdminLayerService;
import com.lore.zzal.admin.failures.AdminFailureQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 자동 CLI(결정 8) — DB 없이 보는 판정: 꺼짐이면 아무것도 안 하고, 통과 후보가 없으면 반영하지 않는다. */
@DisplayName("관리자 자동 CLI — 꺼짐 · 통과 없음")
class AdminAutoRetryTest {

    private final Instant now = Instant.parse("2026-10-28T12:00:00Z");

    @Test
    @DisplayName("꺼져 있으면 부화 실패 훅도 맞춤 스캔도 DB 를 건드리지 않는다")
    void disabled_touchesNothing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AdminFailureQuery query = mock(AdminFailureQuery.class);
        AdminRetryRepository retries = mock(AdminRetryRepository.class);
        AdminAutoRetry auto = new AdminAutoRetry(false, "", query, retries, jdbc);

        auto.onHatchFinallyFailed(1L, now);
        assertThat(auto.scanCustom(now)).isZero();
        verifyNoInteractions(jdbc, query, retries);
    }

    @Test
    @DisplayName("since 를 비우면 기동 시각, 주면 그 시각")
    void sinceDefaultsToStartup() {
        Instant before = Instant.now();
        AdminAutoRetry blank = new AdminAutoRetry(true, " ", mock(AdminFailureQuery.class),
                mock(AdminRetryRepository.class), mock(JdbcTemplate.class));
        assertThat(blank.since()).isAfterOrEqualTo(before);
        AdminAutoRetry fixed = new AdminAutoRetry(true, "2026-10-01T00:00:00Z", mock(AdminFailureQuery.class),
                mock(AdminRetryRepository.class), mock(JdbcTemplate.class));
        assertThat(fixed.since()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
    }

    @Test
    @DisplayName("★ 자동 행 — 통과 후보가 0장이면 NONE 으로 닫고 고르지 않는다(알은 FAILED 그대로)")
    void candidatesAllRejected_resultNone_noPick() {
        AdminRetryRepository retries = mock(AdminRetryRepository.class);
        AdminLayerService layer = mock(AdminLayerService.class);
        AdminRetry row = AdminRetry.auto(AdminRetryKind.HATCH, 7L, 7L, now);
        row.claim(now);
        when(retries.findById(anyLong())).thenReturn(Optional.of(row));
        when(retries.findByIdForUpdate(anyLong())).thenReturn(Optional.of(row));
        when(layer.candidates(anyLong(), anyLong(), anyInt(), any(), any())).thenReturn(List.of(
                new AdminLayerService.Candidate("c-1", "g1", "REJECTED", "격자 구조 이상", null, Map.of()),
                new AdminLayerService.Candidate("c-2", "g2", "CRASHED", "후처리 예외", null, Map.of())));
        var applier = new AdminHatchRetryApplier(mock(AdminGuard.class), mock(AdminCaller.class), retries, layer,
                mock(S3Service.class), new TransactionTemplate(mock(PlatformTransactionManager.class)));

        var result = applier.hatchCandidates(9L, 1L, new AdminHatchRetryApplier.RetryCandidatesRequest(
                List.of("images/zzal/g1", "images/zzal/g2"), null, null, null, null), now);

        assertThat(result.result()).isEqualTo("NONE");
        assertThat(result.autoApplied()).isFalse();
        assertThat(row.getResult()).isEqualTo(AdminRetryResult.NONE);
        verify(layer, never()).pick(anyLong(), anyLong(), anyInt(), anyString(), any(), any());
    }
}
