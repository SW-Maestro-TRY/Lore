package com.lore.zzal.admin.failures;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.admin.failures.AdminFailureResponses.FailurePage;
import com.lore.zzal.admin.failures.AdminFailureResponses.FailureRow;
import com.lore.zzal.admin.failures.AdminFailureResponses.HourBucket;
import com.lore.zzal.admin.retry.AdminRetry;
import com.lore.zzal.admin.retry.AdminRetryKind;
import com.lore.zzal.admin.retry.AdminRetryRepository;
import com.lore.zzal.admin.retry.AdminRetryResult;
import com.lore.zzal.generation.HatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 관리자 실패 목록 — "사용자가 결과를 못 받은" 부화·맞춤 8종을 한 목록으로(결정 7).
 *
 * <h3>부화</h3>
 * phase FAILED 펫, 그리고 DRAFT·HATCHING 인데 <b>마지막 HATCH 잡 활동 시각</b>(started/running/finished 중 최신)이
 * 유예보다 오래된 알. {@code hatch_started_at} 기준이 아니다 — 3회 재시도는 12분을 넘길 수 있어 진행 중인 부화가
 * 실패로 보인다(리뷰 초점 1). 잡이 0개인 DRAFT 와 단계를 다 끝내고 이름을 기다리는 DRAFT 는 실패가 아니다
 * ({@code StuckHatchRecovery} 와 같은 규칙).
 *
 * <h3>맞춤</h3>
 * 출석 보상 motion 슬롯이 ELIGIBLE 인데 자격 시각(eligible_day 번째 방문) 뒤 유예가 지나도록 GRANTED 가 아닌 펫.
 * 잡 상태는 걸러 내는 조건이 아니라 그 줄의 "이유" 다. 단 잡이 GENERATED 이고 8개가 다 게시·PASS 면
 * 다음 방문 때 받으므로 뺀다(리뷰 초점 2). 배경 슬롯은 보지 않는다.
 *
 * ★ 읽기만 한다. 어떤 표도 쓰지 않는다.
 */
@Component
public class AdminFailureQuery {

    private static final Logger log = LoggerFactory.getLogger(AdminFailureQuery.class);

    static final Set<Integer> ALLOWED_DAYS = Set.of(1, 7, 30);
    static final String PROGRAM = "first-v1";
    static final String STUCK = "STUCK";
    static final String NAMELESS = "이름 짓기 전 실패라 1층 복구로 살릴 수 없음 — 다시 부화(rehatch)는 가능";
    static final String NO_JOB_BLOCKED = "잡이 없음";

    private final NamedParameterJdbcTemplate db;
    private final HatchService hatchService;
    private final AdminRetryRepository retries;
    private final int hatchGraceMinutes;
    private final int customGraceMinutes;

    public AdminFailureQuery(NamedParameterJdbcTemplate db, HatchService hatchService, AdminRetryRepository retries,
                             @Value("${app.zzal.admin.failures.hatch-grace-minutes:${app.zzal.recovery.grace-minutes:12}}")
                             int hatchGraceMinutes,
                             @Value("${app.zzal.admin.failures.custom-grace-minutes:30}") int customGraceMinutes) {
        this.db = db;
        this.hatchService = hatchService;
        this.retries = retries;
        this.hatchGraceMinutes = hatchGraceMinutes;
        this.customGraceMinutes = customGraceMinutes;
    }

    // ── 부화 ──────────────────────────────────────────────────────────────

    private static final String HATCH_SQL = """
            WITH j AS (
              SELECT pet_id,
                     count(*)                                              AS attempts,
                     coalesce(sum(total_cost_usd), 0)                      AS cost,
                     max(greatest(coalesce(finished_at, 'epoch'), coalesce(running_at, 'epoch'), started_at)) AS last_activity,
                     max(finished_at) FILTER (WHERE status = 'FAILED')     AS last_failed_at,
                     max(id)                                               AS last_job_id,
                     (array_agg(error_code ORDER BY id DESC))[1]           AS last_error,
                     (array_agg(pipeline_version ORDER BY id DESC))[1]     AS version
              FROM zzal_gen_job WHERE kind = 'HATCH' GROUP BY pet_id
            )
            SELECT p.id, p.name, p.user_id, p.phase, j.attempts, j.cost, j.last_activity, j.last_failed_at,
                   j.last_error, j.version,
                   (SELECT s.name FROM zzal_gen_step s
                     WHERE s.job_id = j.last_job_id AND s.status = 'FAILED'
                     ORDER BY s.seq DESC, s.id DESC LIMIT 1)               AS failed_step
            FROM zzal_pet p JOIN j ON j.pet_id = p.id
            WHERE (:target < 0 OR p.id = :target)
              AND ((p.phase = 'FAILED' AND coalesce(j.last_failed_at, j.last_activity) >= :from)
               OR (p.phase IN ('DRAFT', 'HATCHING')
                   AND j.last_activity < :cutoff
                   AND j.last_activity >= :from))
            ORDER BY coalesce(j.last_failed_at, j.last_activity) DESC, p.id DESC
            """;

    private record HatchRaw(long petId, String name, long userId, String phase, int attempts, BigDecimal cost,
                            Instant lastActivity, Instant lastFailedAt, String lastError, String version,
                            String failedStep) {
    }

    public List<FailureRow> hatchFailures(Instant from, Instant now) {
        return hatchFailures(from, now, -1);
    }

    /** 그 펫이 지금 부화 실패 목록 조건에 드는가(기간 제한 없음). 재시도 요청·상세가 쓴다. */
    public java.util.Optional<FailureRow> hatchRow(long petId, Instant now) {
        return hatchFailures(Instant.EPOCH, now, petId).stream().findFirst();
    }

    private List<FailureRow> hatchFailures(Instant from, Instant now, long target) {
        var params = new MapSqlParameterSource()
                .addValue("target", target)
                .addValue("from", Timestamp.from(from))
                .addValue("cutoff", Timestamp.from(now.minus(Duration.ofMinutes(hatchGraceMinutes))));
        List<HatchRaw> raws = db.query(HATCH_SQL, params, (rs, n) -> new HatchRaw(
                rs.getLong("id"), rs.getString("name"), rs.getLong("user_id"), rs.getString("phase"),
                rs.getInt("attempts"), rs.getBigDecimal("cost"), instant(rs, "last_activity"),
                instant(rs, "last_failed_at"), rs.getString("last_error"), rs.getString("version"),
                rs.getString("failed_step")));

        List<HatchRaw> kept = new ArrayList<>();
        for (HatchRaw r : raws) {
            if (!"FAILED".equals(r.phase()) && waitingForName(r)) {
                continue;
            }
            kept.add(r);
        }
        Map<Long, List<AdminRetry>> history = history(AdminRetryKind.HATCH,
                kept.stream().map(HatchRaw::petId).toList());

        List<FailureRow> rows = new ArrayList<>(kept.size());
        for (HatchRaw r : kept) {
            boolean failed = "FAILED".equals(r.phase());
            String code = failed ? (r.lastError() != null ? r.lastError() : "UNKNOWN") : STUCK;
            // ★ 멈춘 알은 실패로 닫지 않고 서버에서 이어서 굽는다(결정 2) — 이름이 없어도 된다.
            //   FAILED 알은 1층 복구(맥미니)로 살리므로 이름이 있어야 한다.
            boolean revivable = !failed || named(r.name());
            String stage = r.failedStep() != null ? r.failedStep() : (failed ? null : "멈춤(서버 재시작 추정)");
            boolean named = named(r.name());
            List<AdminRetry> h = history.getOrDefault(r.petId(), List.of());
            rows.add(new FailureRow("HATCH", r.petId(), r.petId(), named ? r.name() : null, r.userId(),
                    failed && r.lastFailedAt() != null ? r.lastFailedAt() : r.lastActivity(),
                    code, stage, AdminFailureResponses.reasonText(code),
                    r.attempts(), r.cost(), autoCli(h), request(h), openId(h),
                    revivable, revivable ? null : NAMELESS));
        }
        return rows;
    }

    private static boolean named(String name) {
        return name != null && !name.isBlank();
    }

    /** 단계를 다 끝낸 알은 멈춘 것이 아니라 이름을 기다리는 중이다. 모르는 버전이면 판정할 수 없어 남긴다. */
    private boolean waitingForName(HatchRaw r) {
        String v = r.version() != null ? r.version() : hatchService.currentVersion();
        try {
            return hatchService.stepsDone(r.petId(), v) >= hatchService.stepsTotal(v);
        } catch (IllegalArgumentException e) {
            log.warn("모르는 파이프라인 버전이라 이름 대기 여부를 못 봅니다 — petId={} version={}", r.petId(), v);
            return false;
        }
    }

    // ── 맞춤 ──────────────────────────────────────────────────────────────

    private static final String CUSTOM_SQL = """
            WITH v AS (
              SELECT pet_id, observed_at,
                     row_number() OVER (PARTITION BY pet_id ORDER BY observed_at, id) AS day
              FROM zzal_awake_visit WHERE program_version = :program
            ), due AS (
              SELECT s.pet_id,
                     CASE WHEN s.slot_key ~ '^bundle-[0-9]+:' THEN split_part(s.slot_key, ':', 1) ELSE 'bundle-1' END
                       AS bundle_version,
                     v.observed_at AS eligible_at
              FROM zzal_attendance_reward_slot s
              JOIN v ON v.pet_id = s.pet_id AND v.day = s.eligible_day
              WHERE s.program_version = :program AND s.state = 'ELIGIBLE'
                AND (s.slot_key ~ '^motion-[1-8]$' OR s.slot_key ~ '^bundle-[0-9]+:motion-[1-8]$')
                AND v.observed_at < :cutoff
            )
            SELECT d.pet_id, d.bundle_version, min(d.eligible_at) AS first_due_at, count(*) AS missing,
                   p.name, p.user_id, j.id AS job_id, j.state, j.error_code, j.claim_token IS NOT NULL AS dispatched,
                   j.manifest_hash,
                   (SELECT count(*) FROM zzal_custom_bundle_asset a
                     WHERE a.job_id = j.id AND a.published AND a.auto_check = 'PASS'
                       AND a.human_review <> 'REJECTED') AS published_pass
            FROM due d
            JOIN zzal_pet p ON p.id = d.pet_id AND p.phase = 'ALIVE'
            LEFT JOIN zzal_custom_bundle_job j
              ON j.pet_id = d.pet_id AND j.program_version = :program AND j.bundle_version = d.bundle_version
            WHERE (:target < 0 OR j.id = :target)
            GROUP BY d.pet_id, d.bundle_version, p.name, p.user_id, j.id
            HAVING min(d.eligible_at) >= :from
            ORDER BY min(d.eligible_at) DESC, d.pet_id DESC
            """;

    private record CustomRaw(long petId, String bundle, Instant firstDueAt, String name, long userId, Long jobId,
                             String state, String error, boolean dispatched, String manifest, int publishedPass) {
    }

    public List<FailureRow> customFailures(Instant from, Instant now) {
        return customFailures(from, now, -1);
    }

    /** 그 맞춤 잡이 지금 맞춤 실패 목록 조건에 드는가(기간 제한 없음). */
    public java.util.Optional<FailureRow> customRow(long jobId, Instant now) {
        return customFailures(Instant.EPOCH, now, jobId).stream().findFirst();
    }

    private List<FailureRow> customFailures(Instant from, Instant now, long target) {
        var params = new MapSqlParameterSource()
                .addValue("target", target)
                .addValue("program", PROGRAM)
                .addValue("from", Timestamp.from(from))
                .addValue("cutoff", Timestamp.from(now.minus(Duration.ofMinutes(customGraceMinutes))));
        List<CustomRaw> raws = db.query(CUSTOM_SQL, params, (rs, n) -> new CustomRaw(
                rs.getLong("pet_id"), rs.getString("bundle_version"), instant(rs, "first_due_at"),
                rs.getString("name"), rs.getLong("user_id"), (Long) rs.getObject("job_id", Long.class),
                rs.getString("state"), rs.getString("error_code"), rs.getBoolean("dispatched"),
                rs.getString("manifest_hash"), rs.getInt("published_pass")));

        List<CustomRaw> kept = raws.stream().filter(r -> !awaitingVisit(r)).toList();
        Map<Long, List<AdminRetry>> history = history(AdminRetryKind.CUSTOM,
                kept.stream().map(CustomRaw::jobId).filter(java.util.Objects::nonNull).toList());

        List<FailureRow> rows = new ArrayList<>(kept.size());
        for (CustomRaw r : kept) {
            String code = customReason(r);
            List<AdminRetry> h = r.jobId() == null ? List.of() : history.getOrDefault(r.jobId(), List.of());
            boolean hasJob = r.jobId() != null;
            rows.add(new FailureRow("CUSTOM", hasJob ? r.jobId() : 0L, r.petId(), blankToNull(r.name()), r.userId(),
                    r.firstDueAt(), code, r.state(), AdminFailureResponses.reasonText(code),
                    h.size() + 1, null, autoCli(h), request(h), openId(h),
                    hasJob, hasJob ? null : NO_JOB_BLOCKED));
        }
        return rows;
    }

    /** 게시까지 끝난 세트 — 사용자가 다음에 오면 grantReady 가 준다. */
    private static boolean awaitingVisit(CustomRaw r) {
        return "GENERATED".equals(r.state()) && r.manifest() != null && r.publishedPass() >= 8;
    }

    static String customReason(CustomRaw r) {
        if (r.jobId() == null) {
            return "NO_JOB";
        }
        String state = r.state();
        String error = r.error();
        return switch (state) {
            case "PENDING" -> {
                if ("PLANNING_IN_PROGRESS".equals(error)) {
                    yield "PLANNING_STUCK";
                }
                if (error != null) {
                    yield error;
                }
                yield r.dispatched() ? "DISPATCH_STUCK" : "NOT_DISPATCHED";
            }
            case "CLAIMED" -> error != null ? error : "CLAIMED_ORPHAN";
            case "FAILED" -> error != null ? error : "FAILED";
            case "GENERATED" -> "NOT_PUBLISHED";
            default -> state;
        };
    }

    // ── 한 장 ─────────────────────────────────────────────────────────────

    public FailurePage page(int days, Instant now) {
        if (!ALLOWED_DAYS.contains(days)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "기간은 1·7·30일만 됩니다: " + days);
        }
        Instant from = now.minus(Duration.ofDays(days));
        List<FailureRow> hatch = hatchFailures(from, now);
        List<FailureRow> custom = customFailures(from, now);

        var params = new MapSqlParameterSource()
                .addValue("program", PROGRAM)
                .addValue("from", Timestamp.from(from))
                .addValue("now", Timestamp.from(now));
        Long hatched = db.queryForObject(
                "SELECT count(*) FROM zzal_pet WHERE hatched_at >= :from AND hatched_at <= :now", params, Long.class);
        Long granted = db.queryForObject("""
                SELECT count(*) FROM (
                  SELECT DISTINCT pet_id,
                         CASE WHEN slot_key ~ '^bundle-[0-9]+:' THEN split_part(slot_key, ':', 1) ELSE 'bundle-1' END
                  FROM zzal_attendance_reward_slot
                  WHERE program_version = :program AND state = 'GRANTED'
                    AND granted_at >= :from AND granted_at <= :now
                    AND (slot_key ~ '^motion-[1-8]$' OR slot_key ~ '^bundle-[0-9]+:motion-[1-8]$')
                ) g
                """, params, Long.class);

        List<FailureRow> rows = new ArrayList<>(hatch.size() + custom.size());
        rows.addAll(hatch);
        rows.addAll(custom);
        rows.sort(Comparator.comparing(FailureRow::failedAt, Comparator.nullsLast(Comparator.reverseOrder())));

        return new FailurePage(from, now, hatched == null ? 0 : hatched, hatch.size(),
                granted == null ? 0 : granted, custom.size(), hours(from, now, hatch, custom), rows);
    }

    /** from 이 든 시간부터 now 가 든 시간까지 빈 칸 없이. 실패 시각(failedAt)의 시간으로 센다. */
    static List<HourBucket> hours(Instant from, Instant now, List<FailureRow> hatch, List<FailureRow> custom) {
        Instant first = from.truncatedTo(ChronoUnit.HOURS);
        Instant last = now.truncatedTo(ChronoUnit.HOURS);
        Map<Instant, int[]> counts = new LinkedHashMap<>();
        for (Instant h = first; !h.isAfter(last); h = h.plus(1, ChronoUnit.HOURS)) {
            counts.put(h, new int[2]);
        }
        for (FailureRow r : hatch) {
            int[] c = r.failedAt() == null ? null : counts.get(r.failedAt().truncatedTo(ChronoUnit.HOURS));
            if (c != null) {
                c[0]++;
            }
        }
        for (FailureRow r : custom) {
            int[] c = r.failedAt() == null ? null : counts.get(r.failedAt().truncatedTo(ChronoUnit.HOURS));
            if (c != null) {
                c[1]++;
            }
        }
        return counts.entrySet().stream()
                .map(e -> new HourBucket(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();
    }

    // ── 요청 이력 ─────────────────────────────────────────────────────────

    private Map<Long, List<AdminRetry>> history(AdminRetryKind kind, List<Long> targets) {
        if (targets.isEmpty()) {
            return Map.of();
        }
        return retries.findByKindAndTargetIdInOrderByRequestedAtDescIdDesc(kind, targets).stream()
                .collect(Collectors.groupingBy(AdminRetry::getTargetId, LinkedHashMap::new, Collectors.toList()));
    }

    /** 자동 CLI 의 최신 상태 — NONE | BAKING | RECOVERED | FAILED. */
    static String autoCli(List<AdminRetry> newestFirst) {
        AdminRetry a = newestFirst.stream().filter(AdminRetry::isAuto).findFirst().orElse(null);
        if (a == null) {
            return "NONE";
        }
        if (a.isOpen()) {
            return "BAKING";
        }
        return a.getResult() == AdminRetryResult.APPLIED ? "RECOVERED" : "FAILED";
    }

    /** 수동 요청의 최신 상태. */
    static String request(List<AdminRetry> newestFirst) {
        AdminRetry m = newestFirst.stream().filter(r -> !r.isAuto()).findFirst().orElse(null);
        if (m == null) {
            return "NONE";
        }
        if (m.getResult() == null) {
            return m.getPickedAt() == null ? "WAITING" : "BAKING";
        }
        return switch (m.getResult()) {
            case CANDIDATES -> "CANDIDATES";
            case NONE -> "NO_CANDIDATES";
            case CANCELLED -> "CANCELLED";
            case APPLIED -> "APPLIED";
            case APPLY_FAILED -> "APPLY_FAILED";
        };
    }

    private static Long openId(List<AdminRetry> newestFirst) {
        return newestFirst.stream().filter(AdminRetry::isOpen).map(AdminRetry::getId).findFirst().orElse(null);
    }

    // ── 공용 ──────────────────────────────────────────────────────────────

    /** 경로의 kind 문자열. HATCH · CUSTOM 말고는 INVALID_INPUT. */
    public static AdminRetryKind parseKind(String kind) {
        if ("HATCH".equals(kind)) {
            return AdminRetryKind.HATCH;
        }
        if ("CUSTOM".equals(kind)) {
            return AdminRetryKind.CUSTOM;
        }
        throw new BusinessException(ErrorCode.INVALID_INPUT, "kind 는 HATCH 또는 CUSTOM 입니다: " + kind);
    }

    /**
     * 맞춤 결과가 놓이는 자리의 requestId — {@code CustomGenerationRequest.from(jobId, snapshot, plan).requestId()}
     * 와 같은 식(잡 id + 계획 원문 sha256). from() 은 계획 모양까지 검증해 깨진 계획이면 던지므로, 상세·러너 재료는
     * 같은 식만 따로 계산한다. 계획이 없으면 null.
     */
    public static String customRequestId(long jobId, String sourcePlanJson) {
        if (sourcePlanJson == null) {
            return null;
        }
        return java.util.UUID.nameUUIDFromBytes((jobId + ":" + sha256(sourcePlanJson))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    public static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── 잔손 ──────────────────────────────────────────────────────────────

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
