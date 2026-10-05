package com.lore.piecemaker.adreport;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** 집계만 반환한다. 계정 번호·익명 식별자·이메일·가설 내용은 포함하지 않는다. */
public record AdCohortReport(
        String campaign, Instant from, Instant to, Instant asOf,
        boolean collectionEnabled, boolean testExclusionsReviewed, boolean observationWindowClosed, boolean provisional,
        History history, Basis basis, AccountCounts accounts, List<ExclusionCount> exclusions,
        List<CreativeCounts> creatives, ReceiptCounts receipts) {

    public record Basis(String attribution, String landingTime, String completion,
                        String accountState, List<String> limitations) { }

    /** asOf와 별개인 실제 조회 시점의 보관 범위. 검증 구간은 운영 증거로 설정한 값이다. */
    @Schema(name = "PieceMakerAdMeasurementHistory")
    public record History(Instant evaluatedAt, Instant recordsAvailableAfter,
                          Instant collectionVerifiedFrom, Instant collectionVerifiedThrough,
                          boolean requestedWindowCovered) { }

    public record AccountCounts(long firstAttributed, long excluded, long eligible,
                                long completed, long pending, long noCompletion) { }

    /** 사유는 우선순위 순이며, 한 계정은 한 사유에만 들어간다. */
    public record ExclusionCount(String reason, long accounts) { }

    public record CreativeCounts(String adId, String placement, long eligible,
                                 long completed, long pending, long noCompletion) { }

    /** 두 값은 계정 수가 아닌 유입 기록 수이며 서로 겹칠 수 있다. */
    public record ReceiptCounts(long unclaimedLandings, long ambiguousLandings) { }
}
