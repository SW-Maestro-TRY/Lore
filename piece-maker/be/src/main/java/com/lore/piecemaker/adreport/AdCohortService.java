package com.lore.piecemaker.adreport;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.piecemaker.hypothesis.PieceMakerAdminGuard;
import com.lore.piecemaker.retention.MeasurementRetention;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
public class AdCohortService {
    private static final Duration WINDOW = Duration.ofHours(24);
    private static final List<String> REASONS = List.of("ADMIN", "INACTIVE", "TEST_ACCOUNT", "AMBIGUOUS_IDENTITY",
            "PRIOR_SUBMISSION", "PRIOR_NORMAL_VIEW", "HISTORY_UNVERIFIABLE");
    private final AdCohortRepository repository;
    private final PieceMakerAdminGuard adminGuard;
    private final Set<Long> excludedUserIds;
    private final boolean exclusionsReviewed;
    private final boolean collectionEnabled;
    private final MeasurementHistory history;

    public AdCohortService(AdCohortRepository repository, PieceMakerAdminGuard adminGuard,
                          @Value("${lore.piece-maker.ad-report.excluded-user-ids:}") String excludedUserIds,
                          @Value("${lore.piece-maker.ad-report.test-exclusions-reviewed:false}") boolean exclusionsReviewed,
                          @Value("${app.analytics.enabled:true}") boolean collectionEnabled,
                          MeasurementHistory history) {
        this.repository = repository;
        this.adminGuard = adminGuard;
        this.excludedUserIds = parseExcludedIds(excludedUserIds);
        this.exclusionsReviewed = exclusionsReviewed;
        this.collectionEnabled = collectionEnabled;
        this.history = history;
    }

    /** 하나의 DB 스냅샷에서 계정 집계와 유입 기록 집계를 읽는다. 수집 중지 후에도 이력은 조회할 수 있다. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdCohortReport report(Long userId, String campaign, String fromText, String toText, String asOfText) {
        if (userId == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        adminGuard.require(userId);
        Instant now = Instant.now();
        Instant from = requireInstant(fromText);
        Instant to = requireInstant(toText);
        Instant asOf = asOfText == null ? now : requireInstant(asOfText);
        if (campaign == null || !campaign.matches("[A-Za-z0-9._-]{1,64}")
                || from.isBefore(Instant.EPOCH) || !from.isBefore(to)
                || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0
                || to.isAfter(asOf) || asOf.isAfter(now)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "campaign은 영문·숫자·점·밑줄·하이픈 1~64자, 기간은 31일 이내이며 from < to <= asOf <= 현재여야 합니다");
        }
        Instant availableAfter = MeasurementRetention.availableSince(now);
        if (!from.isAfter(availableAfter)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "보관 범위를 벗어난 기간은 복원하여 집계할 수 없습니다");
        }
        boolean historyCovered = history.covers(from, asOf);
        var landings = repository.firstLandings(campaign, from, to, asOf, availableAfter);
        Map<String, Long> exclusions = new LinkedHashMap<>();
        REASONS.forEach(reason -> exclusions.put(reason, 0L));
        Map<Creative, MutableCounts> creatives = new TreeMap<>();
        MutableCounts counts = new MutableCounts();
        long excluded = 0;
        for (var landing : landings) {
            String reason = exclusion(landing, asOf, availableAfter, historyCovered);
            if (reason != null) {
                exclusions.compute(reason, (key, n) -> n + 1);
                excluded++;
                continue;
            }
            Outcome outcome = outcome(landing, asOf);
            counts.add(outcome);
            creatives.computeIfAbsent(new Creative(landing.adId(), landing.placement()), key -> new MutableCounts()).add(outcome);
        }
        boolean observationWindowClosed = !asOf.isBefore(to.plus(WINDOW));
        return new AdCohortReport(campaign, from, to, asOf, collectionEnabled, exclusionsReviewed,
                observationWindowClosed, !exclusionsReviewed || !observationWindowClosed || !historyCovered,
                new AdCohortReport.History(now, availableAfter, history.verifiedFrom(), history.verifiedThrough(), historyCovered),
                new AdCohortReport.Basis("FIRST_KNOWN_PAID_LANDING_PER_ACCOUNT", "SERVER_RECEIPT_TIME",
                        "FIRST_NORMAL_VIEW_WITHIN_24_HOURS_INCLUSIVE", "CURRENT_ROLE_AND_STATUS",
                        List.of("UTM 기반 내부 집계이며 Meta 기여 전환 수가 아닙니다.",
                                "계정 생성부터 asOf까지 연속 수집이 검증되지 않거나 보관·선택 삭제로 이력이 불완전하면 신규 성과에서 제외합니다.",
                                "삭제 이력과 보관 범위는 실제 조회 시점 기준이며 과거 asOf로 삭제된 자료를 복원하지 않습니다.",
                                "asOf는 유입·계정 연결·모호함·열람 기록에 적용하며 계정 역할·상태와 제외 설정은 현재 값입니다.",
                                "서버 수신·저장 시각을 사용하므로 실제 클릭·화면 노출 시각과 차이가 있을 수 있습니다.",
                                "시험 계정·연속 수집 검증이 끝나지 않았거나 조회 종료(to) 후 24시간이 지나기 전이면 잠정 집계입니다.")),
                new AdCohortReport.AccountCounts(landings.size(), excluded, counts.eligible, counts.completed,
                        counts.pending, counts.noCompletion),
                exclusions.entrySet().stream().map(e -> new AdCohortReport.ExclusionCount(e.getKey(), e.getValue())).toList(),
                creatives.entrySet().stream().map(e -> new AdCohortReport.CreativeCounts(e.getKey().adId, e.getKey().placement,
                        e.getValue().eligible, e.getValue().completed, e.getValue().pending, e.getValue().noCompletion)).toList(),
                repository.receiptCounts(campaign, from, to, asOf));
    }

    private String exclusion(AdCohortRepository.AccountLanding landing, Instant asOf, Instant availableAfter,
                             boolean historyCovered) {
        if ("ADMIN".equals(landing.role())) return "ADMIN";
        if (!"ACTIVE".equals(landing.status()) || landing.deleted()) return "INACTIVE";
        if (excludedUserIds.contains(landing.userId())) return "TEST_ACCOUNT";
        if (landing.ambiguous()) return "AMBIGUOUS_IDENTITY";
        if (landing.priorSubmission()) return "PRIOR_SUBMISSION";
        if (landing.viewedAt() != null && landing.viewedAt().isBefore(landing.landedAt())) return "PRIOR_NORMAL_VIEW";
        if (!historyCovered || landing.historyGap() || !landing.accountCreatedAt().isAfter(availableAfter)
                || landing.accountCreatedAt().isAfter(asOf) || !history.covers(landing.accountCreatedAt(), asOf)) {
            return "HISTORY_UNVERIFIABLE";
        }
        return null;
    }

    private static Outcome outcome(AdCohortRepository.AccountLanding landing, Instant asOf) {
        Instant deadline = landing.landedAt().plus(WINDOW);
        if (landing.viewedAt() != null && !landing.viewedAt().isAfter(deadline) && !landing.viewedAt().isAfter(asOf)) {
            return Outcome.COMPLETED;
        }
        return asOf.isBefore(deadline) ? Outcome.PENDING : Outcome.NO_COMPLETION;
    }

    private static Instant requireInstant(String text) {
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException | NullPointerException ex) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "시간은 ISO-8601 형식이어야 합니다");
        }
    }

    private static Set<Long> parseExcludedIds(String text) {
        try {
            Set<Long> ids = Arrays.stream(text.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                    .map(Long::parseLong).collect(Collectors.toUnmodifiableSet());
            if (ids.stream().anyMatch(id -> id <= 0)) throw new NumberFormatException();
            return ids;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("lore.piece-maker.ad-report.excluded-user-ids는 양의 계정 번호 목록이어야 합니다", ex);
        }
    }

    private enum Outcome { COMPLETED, PENDING, NO_COMPLETION }

    private record Creative(String adId, String placement) implements Comparable<Creative> {
        @Override public int compareTo(Creative other) {
            int ad = adId.compareTo(other.adId);
            return ad != 0 ? ad : placement.compareTo(other.placement);
        }
    }

    private static final class MutableCounts {
        long eligible;
        long completed;
        long pending;
        long noCompletion;

        void add(Outcome outcome) {
            eligible++;
            switch (outcome) {
                case COMPLETED -> completed++;
                case PENDING -> pending++;
                case NO_COMPLETION -> noCompletion++;
            }
        }
    }
}
