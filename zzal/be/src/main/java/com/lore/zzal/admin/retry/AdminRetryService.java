package com.lore.zzal.admin.retry;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.admin.AdminCaller;
import com.lore.zzal.admin.AdminGuard;
import com.lore.zzal.admin.AdminLayerService;
import com.lore.zzal.admin.failures.AdminFailureQuery;
import com.lore.zzal.admin.failures.AdminFailureResponses.FailureRow;
import com.lore.zzal.generation.GenJob;
import com.lore.zzal.generation.GenJobRepository;
import com.lore.zzal.generation.GenKind;
import com.lore.zzal.generation.GenStepRecord;
import com.lore.zzal.generation.GenStepRecordRepository;
import com.lore.zzal.generation.HatchService;
import com.lore.zzal.generation.steps.IdentityStep;
import com.lore.zzal.generation.steps.SheetStep;
import com.lore.zzal.motion.MotionImageKeys;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalPetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 관리자 재시도 요청 · 취소 · 러너 집기 · 대기 목록 · 러너 시각(결정 3·4·5, 설계 1·2절).
 *
 * <h3>재시도는 맥미니 러너 길 하나</h3>
 * 부화 FAILED 알 · 맞춤 잡은 요청 행을 열어 두면 맥미니 러너가 집어 간다(과금 없음). 부화는 기존 1층 LOCAL_REQUESTED
 * 표시도 같이 건다. 서버에서 이미지 API 를 부르는 길(이어서 굽기 · 재굽기)은 두지 않는다(2026-10-28 상훈님 결정).
 *
 * ★ 봇 토큰은 요청·취소를 못 한다(읽기 · 집기 · 후보 올리기만, 설계 6절).
 */
@Service
public class AdminRetryService {

    private static final Logger log = LoggerFactory.getLogger(AdminRetryService.class);

    static final String RECOVERED_NOTE = "다른 길로 복구됨";

    private final AdminGuard adminGuard;
    private final AdminCaller caller;
    private final AdminFailureQuery query;
    private final AdminRetryRepository retries;
    private final AdminRunnerClock clock;
    private final AdminLayerService layerService;
    private final ZzalPetRepository petRepository;
    private final GenJobRepository jobRepository;
    private final GenStepRecordRepository stepRepository;
    private final HatchService hatchService;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lore.zzal.generation.GenerationRecorder recorder;
    @org.springframework.beans.factory.annotation.Value("${app.zzal.admin.failures.hatch-grace-minutes:${app.zzal.recovery.grace-minutes:12}}")
    private int hatchGraceMinutes;

    public AdminRetryService(AdminGuard adminGuard, AdminCaller caller, AdminFailureQuery query,
                             AdminRetryRepository retries, AdminRunnerClock clock, AdminLayerService layerService,
                             ZzalPetRepository petRepository,
                             GenJobRepository jobRepository, GenStepRecordRepository stepRepository,
                             HatchService hatchService, JdbcTemplate jdbc,
                             TransactionTemplate tx) {
        this.adminGuard = adminGuard;
        this.caller = caller;
        this.query = query;
        this.retries = retries;
        this.clock = clock;
        this.layerService = layerService;
        this.petRepository = petRepository;
        this.jobRepository = jobRepository;
        this.stepRepository = stepRepository;
        this.hatchService = hatchService;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    // ── 응답 ──────────────────────────────────────────────────────────────

    public record RetryView(long id, String kind, long targetId, boolean auto, String memo, Instant requestedAt,
                            Instant pickedAt, String result) {
        static RetryView of(AdminRetry r) {
            return new RetryView(r.getId(), r.getKind().name(), r.getTargetId(), r.isAuto(), r.getMemo(),
                    r.getRequestedAt(), r.getPickedAt(), r.getResult() == null ? null : r.getResult().name());
        }
    }

    public record PendingItem(long retryId, String kind, long targetId, long petId, String petName, boolean auto,
                              String memo, HatchMaterial hatch, CustomMaterial custom) {
    }

    public record HatchMaterial(String sourceImageKey, String sheetKey, String identityText, String failedStage,
                                String pipelineVersion, String anchorsKey, int basicRound) {
    }

    public record CustomMaterial(long jobId, String snapshotJson, String sourcePlanJson, String sourcePlanHash,
                                 String requestId, String state, String errorCode) {
    }

    // ── 요청 ──────────────────────────────────────────────────────────────

    public RetryView request(Long adminId, String kind, long id, String memo, Instant now) {
        adminGuard.require(adminId);
        caller.requireHuman();
        AdminRetryKind k = AdminFailureQuery.parseKind(kind);
        FailureRow row = (k == AdminRetryKind.HATCH ? query.hatchRow(id, now) : query.customRow(id, now))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT, "실패 목록에 없는 대상입니다"));
        if (!row.revivable()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, row.blockedReason());
        }
        if (retries.findOpen(k, id).isPresent()) {
            throw new BusinessException(ErrorCode.ADMIN_RETRY_OPEN);
        }
        AdminRetry draft = manual(k, id, row.petId(), memo, adminId, now);

        if (k == AdminRetryKind.HATCH && "STUCK".equals(row.reasonCode())) {
            return stuck(adminId, draft, now);
        }
        if (k == AdminRetryKind.HATCH) {
            // 기존 1층 LOCAL_REQUESTED 길을 그대로 쓴다 — 지금 러너도 이 표시를 본다(설계 1절).
            layerService.requestRegen(adminId, id, 1, now);
        }
        AdminRetry saved = save(draft);
        if (k == AdminRetryKind.CUSTOM && AdminCustomJobMarker.mark(jdbc, id, saved.getId())) {
            log.info("PENDING 맞춤 잡에 재시도 표식 — job={} retry={}", id, saved.getId());
        }
        log.info("관리자 재시도 요청 — {} {} retry={} (admin={})", k, id, saved.getId(), adminId);
        return RetryView.of(saved);
    }

    /**
     * 멈춘 알(수정 9) — 서버에서 이어서 굽지 않는다. 잠금 안에서 아직도 멈춰 있는지(잡 · 단계 활동이 유예보다 오래됨)
     * 다시 보고, 끝나지 않은 마지막 잡을 실패로 닫고, 알을 조건부로 FAILED 로 바꾼 뒤(1행일 때만 진행 — 동시 두 번 방지)
     * FAILED 알과 같은 러너 길(요청 행 + LOCAL_REQUESTED)로 보낸다. 사망 사유 · 자동 CLI 훅은 markPetFailed 가 맡는다.
     */
    private RetryView stuck(Long adminId, AdminRetry draft, Instant now) {
        long petId = draft.getTargetId();
        AdminRetry saved = tx.execute(s -> {
            jdbc.queryForList("SELECT id FROM zzal_pet WHERE id = ? FOR UPDATE", petId);
            if (retries.findOpen(AdminRetryKind.HATCH, petId).isPresent()) {
                throw new BusinessException(ErrorCode.ADMIN_RETRY_OPEN);
            }
            java.sql.Timestamp last = jdbc.queryForObject("""
                    SELECT greatest(
                             (SELECT max(greatest(coalesce(finished_at, 'epoch'), coalesce(running_at, 'epoch'), started_at))
                                FROM zzal_gen_job WHERE pet_id = ? AND kind = 'HATCH'),
                             (SELECT max(greatest(coalesce(st.finished_at, 'epoch'), st.started_at))
                                FROM zzal_gen_step st JOIN zzal_gen_job g ON g.id = st.job_id
                               WHERE g.pet_id = ? AND g.kind = 'HATCH'))
                    """, java.sql.Timestamp.class, petId, petId);
            if (last == null || !last.toInstant().isBefore(now.minus(java.time.Duration.ofMinutes(hatchGraceMinutes)))) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "아직 굽는 중입니다(마지막 활동이 유예 안)");
            }
            // 끝나지 않은 마지막 잡을 닫는다(이미 끝났으면 0행 — 그대로 진행).
            int closedJobs = jdbc.update("""
                    UPDATE zzal_gen_job SET status = 'FAILED', error_code = coalesce(error_code, 'UNKNOWN'), finished_at = ?
                    WHERE id = (SELECT max(id) FROM zzal_gen_job WHERE pet_id = ? AND kind = 'HATCH')
                      AND status IN ('QUEUED', 'RUNNING')
                    """, java.sql.Timestamp.from(now), petId);
            int closed = jdbc.update("UPDATE zzal_pet SET phase = 'FAILED' WHERE id = ? AND phase IN ('DRAFT', 'HATCHING')", petId);
            if (closed != 1) {
                throw new BusinessException(ErrorCode.ADMIN_RETRY_CLOSED);
            }
            log.info("멈춘 알을 실패로 닫음 — petId={} 닫은 잡 {}건 (admin={})", petId, closedJobs, adminId);
            return save(draft);
        });
        // 사망 사유 · 실패 훅(자동 CLI 는 열린 요청이 있어 만들지 않는다)
        recorder.markPetFailed(petId);
        layerService.requestRegen(adminId, petId, 1, now);
        log.info("관리자 재시도 요청(멈춘 알) — petId={} retry={} (admin={})", petId, saved.getId(), adminId);
        return RetryView.of(saved);
    }

    // ── 취소 ──────────────────────────────────────────────────────────────

    public RetryView cancel(Long adminId, long retryId, Instant now) {
        adminGuard.require(adminId);
        caller.requireHuman();
        AdminRetry r = tx.execute(s -> {
            AdminRetry row = retries.findByIdForUpdate(retryId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "재시도 요청이 없습니다: " + retryId));
            if (!row.isOpen() || row.isApplying()) {
                // 반영 중이면 취소하지 않는다 — 반영이 끝나거나 실패로 닫힌 뒤에 본다.
                throw new BusinessException(ErrorCode.ADMIN_RETRY_CLOSED);
            }
            row.cancel(now);
            return row;
        });
        if (r.getKind() == AdminRetryKind.HATCH) {
            layerService.cancelRegen(adminId, r.getTargetId(), 1);
        } else {
            AdminCustomJobMarker.release(jdbc, r.getTargetId(), r.getId());
        }
        log.info("관리자 재시도 취소 — retry={} (admin={})", retryId, adminId);
        return RetryView.of(r);
    }

    // ── 러너 ──────────────────────────────────────────────────────────────

    /** 러너가 집을 차례. 부를 때마다 러너 확인 시각을 남긴다. 대상이 이미 복구돼 있으면 그 자리에서 닫는다. */
    public List<PendingItem> pending(Long callerId, Instant now) {
        adminGuard.require(callerId);
        clock.touch(now);
        List<PendingItem> out = new ArrayList<>();
        for (AdminRetry r : retries.findPending()) {
            if (recovered(r)) {
                tx.executeWithoutResult(s -> retries.findByIdForUpdate(r.getId()).ifPresent(row -> {
                    if (row.getResult() == null) {
                        row.applied(null, null, RECOVERED_NOTE, now);
                    }
                }));
                log.info("재시도 요청을 닫음(다른 길로 복구됨) — retry={} {} {}", r.getId(), r.getKind(), r.getTargetId());
                continue;
            }
            out.add(item(r, now));
        }
        return out;
    }

    /** 러너가 집었다. 이미 집혔거나 닫혔으면 409. */
    public PendingItem claim(Long callerId, long retryId, Instant now) {
        adminGuard.require(callerId);
        clock.touch(now);
        AdminRetry r = tx.execute(s -> {
            AdminRetry row = retries.findByIdForUpdate(retryId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "재시도 요청이 없습니다: " + retryId));
            if (row.getPickedAt() != null || row.getResult() != null) {
                throw new BusinessException(ErrorCode.ADMIN_RETRY_CLOSED);
            }
            row.claim(now);
            return row;
        });
        return item(r, now);
    }

    public Optional<Instant> runnerLastSeen() {
        return clock.lastSeen();
    }

    // ── 잔손 ──────────────────────────────────────────────────────────────

    private static AdminRetry manual(AdminRetryKind k, long id, long petId, String memo, Long adminId, Instant now) {
        try {
            return AdminRetry.manual(k, id, petId, memo, adminId, now);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, e.getMessage());
        }
    }

    private AdminRetry save(AdminRetry r) {
        try {
            return retries.saveAndFlush(r);
        } catch (DataIntegrityViolationException e) {
            // 동시에 두 번 누른 경우 — 부분 유일 색인이 막았다.
            throw new BusinessException(ErrorCode.ADMIN_RETRY_OPEN);
        }
    }

    /** 대상이 다른 길(층 탭 · 세션 · 재굽기)로 이미 살아났나(리뷰 초점 5). */
    private boolean recovered(AdminRetry r) {
        if (r.getKind() == AdminRetryKind.HATCH) {
            return petRepository.findById(r.getTargetId()).map(ZzalPet::isAlive).orElse(false);
        }
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM zzal_custom_bundle_job j
                WHERE j.id = ? AND j.state = 'GENERATED' AND j.manifest_hash IS NOT NULL
                  AND (SELECT count(*) FROM zzal_custom_bundle_asset a
                        WHERE a.job_id = j.id AND a.published AND a.auto_check = 'PASS'
                          AND a.human_review <> 'REJECTED') >= 8
                """, Integer.class, r.getTargetId());
        return n != null && n > 0;
    }

    private PendingItem item(AdminRetry r, Instant now) {
        if (r.getKind() == AdminRetryKind.HATCH) {
            ZzalPet pet = petRepository.findById(r.getTargetId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND));
            String sheet = null;
            String identity = null;
            for (GenStepRecord s : stepRepository.findSucceededByPet(pet.getId(), GenKind.HATCH)) {
                if (sheet == null && SheetStep.NAME.equals(s.getName())) {
                    sheet = s.getOutputKey();
                }
                if (identity == null && IdentityStep.NAME.equals(s.getName())) {
                    identity = s.getOutputText();
                }
            }
            String failedStage = query.hatchRow(pet.getId(), now).map(FailureRow::stage).orElse(null);
            String v = pet.getHatchPipelineVersion() != null ? pet.getHatchPipelineVersion()
                    : jobRepository.findFirstByPetIdOrderByIdDesc(pet.getId()).map(GenJob::getPipelineVersion)
                    .orElse(hatchService.currentVersion());
            int round = pet.getBasicRound();
            String anchors = MotionImageKeys.hasAnchors(v, round) ? MotionImageKeys.anchors(pet.getId(), round) : null;
            HatchMaterial m = new HatchMaterial(pet.getSourceImageKey(), sheet != null ? sheet : pet.getSheetImageKey(),
                    identity != null ? identity : pet.getIdentityText(), failedStage, v, anchors, round);
            return new PendingItem(r.getId(), r.getKind().name(), r.getTargetId(), r.getPetId(), blankToNull(pet.getName()),
                    r.isAuto(), r.getMemo(), m, null);
        }
        Map<String, Object> job = jdbc.queryForList("""
                SELECT j.id, j.snapshot_json, j.source_plan_json, j.source_plan_hash, j.state, j.error_code, p.name
                FROM zzal_custom_bundle_job j JOIN zzal_pet p ON p.id = j.pet_id WHERE j.id = ?
                """, r.getTargetId()).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "맞춤 잡이 없습니다: " + r.getTargetId()));
        String plan = (String) job.get("source_plan_json");
        CustomMaterial m = new CustomMaterial(r.getTargetId(), (String) job.get("snapshot_json"), plan,
                (String) job.get("source_plan_hash"), AdminFailureQuery.customRequestId(r.getTargetId(), plan),
                (String) job.get("state"), (String) job.get("error_code"));
        return new PendingItem(r.getId(), r.getKind().name(), r.getTargetId(), r.getPetId(),
                blankToNull((String) job.get("name")), r.isAuto(), r.getMemo(), null, m);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
