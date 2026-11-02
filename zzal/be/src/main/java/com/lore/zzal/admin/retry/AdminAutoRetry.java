package com.lore.zzal.admin.retry;

import com.lore.zzal.admin.failures.AdminFailureQuery;
import com.lore.zzal.admin.failures.AdminFailureResponses.FailureRow;
import com.lore.zzal.generation.HatchFailureListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 자동 CLI 요청 행(결정 8) — 서버 시도가 다 실패하면 맥미니 러너 몫 요청을 <b>서버가</b> 한 줄 만든다.
 *
 * <ul>
 *   <li>부화: {@code GenerationRecorder.markPetFailed} 가 실패를 커밋한 뒤 부른다(네 호출처가 다 거기로 모인다).</li>
 *   <li>맞춤: 사건이 없어 주기 스캔 — 실패 목록 조건에 든 잡 중 자동 행이 없는 것.</li>
 * </ul>
 *
 * ★ 기본 꺼짐({@code app.zzal.admin.auto-cli.enabled}). 꺼져 있으면 행을 하나도 만들지 않는다.
 * ★ {@code since} 이후에 실패한 대상만 — 켜는 순간 과거 실패가 한꺼번에 몰리지 않게(리뷰 초점 3). 실패 시각은 호출 시각이 아니라
 *   데이터의 시각(부화 = 마지막 HATCH 잡 활동, 맞춤 = 자격 시각)으로 본다 — 기동 복구가 옛 알을 닫는 순간도 막힌다.
 * ★ 대상당 1회 · 열린 요청이 있으면 생략은 DB 유일 색인이 막는다({@code ON CONFLICT DO NOTHING}).
 * ★ 행은 서버만 만든다. 봇 토큰이 자동 행을 만들 길은 없다 — 그래서 자동 반영은 봇 호출 안에서 허용한다(결정 7).
 */
@Component
@ConditionalOnProperty(name = "app.zzal.admin.enabled", havingValue = "true")
public class AdminAutoRetry implements HatchFailureListener {

    private static final Logger log = LoggerFactory.getLogger(AdminAutoRetry.class);

    private final boolean enabled;
    private final Instant since;
    private final AdminFailureQuery query;
    private final AdminRetryRepository retries;
    private final JdbcTemplate jdbc;

    public AdminAutoRetry(@Value("${app.zzal.admin.auto-cli.enabled:false}") boolean enabled,
                          @Value("${app.zzal.admin.auto-cli.since:}") String since,
                          AdminFailureQuery query, AdminRetryRepository retries, JdbcTemplate jdbc) {
        this.enabled = enabled;
        this.since = since == null || since.isBlank() ? Instant.now() : Instant.parse(since.strip());
        this.query = query;
        this.retries = retries;
        this.jdbc = jdbc;
        if (enabled) {
            log.info("관리자 자동 CLI 켜짐 — {} 이후 실패만", this.since);
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public Instant since() {
        return since;
    }

    @Override
    public void onHatchFinallyFailed(Long petId, Instant now) {
        if (!enabled) {
            return;
        }
        var rows = jdbc.queryForList("""
                SELECT p.name, p.phase,
                       (SELECT max(greatest(coalesce(j.finished_at, 'epoch'), coalesce(j.running_at, 'epoch'), j.started_at))
                          FROM zzal_gen_job j WHERE j.pet_id = p.id AND j.kind = 'HATCH') AS failed_at
                FROM zzal_pet p WHERE p.id = ?
                """, petId);
        if (rows.isEmpty()) {
            return;
        }
        var pet = rows.getFirst();
        String name = (String) pet.get("name");
        if (!"FAILED".equals(pet.get("phase")) || name == null || name.isBlank()) {
            return; // 이름 없는 알은 1층 복구로 못 살린다 — 자동 요청도 안 만든다
        }
        Instant failedAt = pet.get("failed_at") instanceof Timestamp t ? t.toInstant() : now;
        if (failedAt.isBefore(since)) {
            log.info("자동 CLI 생략(since 이전 실패) — petId={} 실패 {}", petId, failedAt);
            return;
        }
        if (insert(AdminRetryKind.HATCH, petId, petId, now)) {
            log.info("자동 CLI 요청 — 부화 petId={}", petId);
        }
    }

    @Scheduled(initialDelayString = "#{${app.zzal.admin.auto-cli.custom-scan-minutes:5}*60000}",
            fixedDelayString = "#{${app.zzal.admin.auto-cli.custom-scan-minutes:5}*60000}")
    public void scheduledScan() {
        if (!enabled) {
            return;
        }
        try {
            scanCustom(Instant.now());
        } catch (RuntimeException e) {
            log.error("자동 CLI 맞춤 스캔 실패", e);
        }
    }

    /** @return 이번에 만든 자동 행 수 */
    public int scanCustom(Instant now) {
        if (!enabled) {
            return 0;
        }
        int made = 0;
        for (FailureRow row : query.customFailures(since, now)) {
            if (!row.revivable() || retries.existsByKindAndTargetIdAndAutoTrue(AdminRetryKind.CUSTOM, row.id())) {
                continue;
            }
            if (insert(AdminRetryKind.CUSTOM, row.id(), row.petId(), now)) {
                made++;
                log.info("자동 CLI 요청 — 맞춤 job={} pet={}", row.id(), row.petId());
            }
        }
        return made;
    }

    private boolean insert(AdminRetryKind kind, long targetId, long petId, Instant now) {
        var ids = jdbc.queryForList("""
                INSERT INTO zzal_admin_retry (kind, target_id, pet_id, auto, requested_at)
                VALUES (?, ?, ?, true, ?) ON CONFLICT DO NOTHING RETURNING id
                """, Long.class, kind.name(), targetId, petId, Timestamp.from(now));
        if (ids.isEmpty()) {
            return false;
        }
        if (kind == AdminRetryKind.CUSTOM) {
            AdminCustomJobMarker.mark(jdbc, targetId, ids.getFirst()); // PENDING 이면 디스패처가 건너뛰게(수정 6)
        }
        return true;
    }
}
