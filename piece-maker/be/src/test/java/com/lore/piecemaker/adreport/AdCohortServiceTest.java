package com.lore.piecemaker.adreport;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.piecemaker.hypothesis.PieceMakerAdminGuard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdCohortServiceTest {
    private final AdCohortRepository repository = mock(AdCohortRepository.class);
    private final PieceMakerAdminGuard guard = mock(PieceMakerAdminGuard.class);
    private final MeasurementHistory history = new MeasurementHistory("2025-12-01T00:00:00Z", "2026-02-01T00:00:00Z");

    @Test
    void nullAuthenticationDoesNotReachGuardOrRepository() {
        var service = new AdCohortService(repository, guard, "", false, true, history);
        assertThatThrownBy(() -> service.report(null, "c", "bad", "bad", null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        verifyNoInteractions(repository, guard);
    }

    @Test
    void disabledCollectionCanReadAndReviewedExclusionsRemoveProvisionalFlag() {
        var service = new AdCohortService(repository, guard, "17, 18", true, false, history);
        when(repository.firstLandings(eq("c"), any(), any(), any(), any())).thenReturn(List.of());
        when(repository.receiptCounts(eq("c"), any(), any(), any())).thenReturn(new AdCohortReport.ReceiptCounts(0, 0));
        var report = service.report(1L, "c", "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", "2026-01-03T00:00:00Z");
        assertThat(report.collectionEnabled()).isFalse();
        assertThat(report.testExclusionsReviewed()).isTrue();
        assertThat(report.provisional()).isFalse();
        assertThat(report.accounts().eligible()).isZero();
    }

    @Test
    void invalidTestExclusionConfigurationFailsClosedAtStartup() {
        for (String value : List.of("abc", "0", "-1", "1,2,x")) {
            assertThatThrownBy(() -> new AdCohortService(repository, guard, value, true, true, history))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void unelapsedObservationWindowRemainsProvisionalEvenAfterTestExclusionReview() {
        var service = new AdCohortService(repository, guard, "", true, true, history);
        when(repository.firstLandings(eq("campaign.with-dot"), any(), any(), any(), any())).thenReturn(List.of());
        when(repository.receiptCounts(eq("campaign.with-dot"), any(), any(), any())).thenReturn(new AdCohortReport.ReceiptCounts(0, 0));
        var pending = service.report(1L, "campaign.with-dot", "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", "2026-01-02T23:59:59Z");
        assertThat(pending.observationWindowClosed()).isFalse();
        assertThat(pending.provisional()).isTrue();
        var matured = service.report(1L, "campaign.with-dot", "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", "2026-01-03T00:00:00Z");
        assertThat(matured.observationWindowClosed()).isTrue();
        assertThat(matured.provisional()).isFalse();
    }
    @Test
    void unknownCollectionHistoryExcludesEvenAnOtherwiseCompletedAccount() {
        var service = new AdCohortService(repository, guard, "", true, true, new MeasurementHistory("", ""));
        var at = java.time.Instant.parse("2026-01-01T00:00:00Z");
        when(repository.firstLandings(eq("c"), any(), any(), any(), any())).thenReturn(List.of(
                new AdCohortRepository.AccountLanding(8, at, "1", "feed", "USER", "ACTIVE", false, false,
                        false, at.plusSeconds(1), at.minusSeconds(1), false)));
        when(repository.receiptCounts(eq("c"), any(), any(), any())).thenReturn(new AdCohortReport.ReceiptCounts(0, 0));
        var report = service.report(1L, "c", at.toString(), at.plusSeconds(3600).toString(), at.plusSeconds(90000).toString());
        assertThat(report.provisional()).isTrue();
        assertThat(report.history().requestedWindowCovered()).isFalse();
        assertThat(report.accounts().completed()).isZero();
        assertThat(report.exclusions()).contains(new AdCohortReport.ExclusionCount("HISTORY_UNVERIFIABLE", 1));
    }

    @Test
    void onlyPastOrderedVerificationIntervalsCanBeConfigured() {
        for (String[] range : List.of(new String[]{"bad", "bad"},
                new String[]{"2026-01-01T00:00:00Z", ""},
                new String[]{"", "2026-01-01T00:00:00Z"},
                new String[]{"2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"},
                new String[]{"2026-01-01T00:00:00Z", "9999-01-01T00:00:00Z"})) {
            assertThatThrownBy(() -> new MeasurementHistory(range[0], range[1])).isInstanceOf(IllegalArgumentException.class);
        }
    }

}
