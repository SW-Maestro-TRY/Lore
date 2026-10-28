package com.lore.zzal.admin.retry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.s3.S3Service;
import com.lore.common.s3.S3Storage;
import com.lore.zzal.admin.AdminCaller;
import com.lore.zzal.admin.AdminGuard;
import com.lore.zzal.admin.failures.AdminFailureQuery;
import com.lore.zzal.admin.retry.AdminHatchRetryApplier.RetryCandidatesRequest;
import com.lore.zzal.admin.retry.AdminHatchRetryApplier.CandidatesResult;
import com.lore.zzal.attendance.BundleSourcePlan;
import com.lore.zzal.attendance.CustomBundlePublicationService;
import com.lore.zzal.attendance.CustomGridPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 맞춤 8종 재시도 — 러너가 만든 세트를 운영 게시 검증과 <b>같은 계약</b>으로 반영한다(작업 1.7).
 * 2026-10-27 수동 게시(publish-20261027: write.sql · upload-s3.sh · rollback.sql)를 서버 코드로 옮긴 것이다.
 *
 * <h3>순서</h3>
 * <ol>
 *   <li>러너 결과: 파일 세트(논리 경로 → 업로드 키)를 받아 manifest 가 이 잡의 requestId · 자산 키 · 해시와 맞는지 본다.
 *       맞으면 CANDIDATES, 아니면 NONE(사유를 note 에).</li>
 *   <li>반영(사람 pick 또는 자동): 덮어쓸 기존 객체 백업 → 업로드 키를 {@code images/zzal/custom/{requestId}/} 로 복사
 *       → 한 트랜잭션에 자산 8행 · 잡 GENERATED(결정 1, 반영 단계에서만) · 재료 장부 → 트랜잭션 밖에서 기존
 *       {@link CustomBundlePublicationService#publish} 검증 전부 + 게시 + 지급.</li>
 *   <li>게시가 실패하면 되돌린다: 이번에 지급된 슬롯 · 자산 행 · 잡 칸 · 재료 장부 · S3 백업을 원래대로.</li>
 * </ol>
 *
 * ★ 이미 사용자에게 지급된(GRANTED) 세트는 덮어쓰지 않는다(409). 계획이 없는 잡은 러너가 만든 계획을
 *   반영 때 써 넣고(결정 5), 이미 계획이 있는 잡의 계획 교체는 거부한다.
 */
@Component
public class AdminCustomRetryApplier {

    private static final Logger log = LoggerFactory.getLogger(AdminCustomRetryApplier.class);

    static final String MANIFEST = "manifest.json";
    static final String METADATA = "resource-metadata.json";
    static final String ANCHORS = "anchors.json";
    static final String GRID = "grid.png";
    /** 계획 없는 잡(계획 중 멈춤)일 때만 — 러너가 만든 계획 원문(결정 5). */
    static final String SOURCE_PLAN = "source-plan.json";
    static final Pattern FRAME = Pattern.compile("review/sequence-v1/frame-\\d{2}\\.png");
    static final String BACKUP_PREFIX = "zzal-review/custom-bundle-failures/";
    static final int NOTE_HEAD = 300;

    static final List<String> REQUIRED;
    static final Set<String> OPTIONAL;

    static {
        List<String> req = new ArrayList<>(List.of(MANIFEST, METADATA, ANCHORS, GRID));
        Set<String> opt = new LinkedHashSet<>(List.of(SOURCE_PLAN));
        for (String slot : CustomGridPrompt.SLOTS) {
            req.add(slot + ".webp");
            opt.add(slot + ".gif");
        }
        REQUIRED = List.copyOf(req);
        OPTIONAL = Set.copyOf(opt);
    }

    private final AdminGuard adminGuard;
    private final AdminCaller caller;
    private final AdminRetryRepository retries;
    private final S3Service s3Service;
    private final S3Storage storage;
    private final BundleSourcePlan plans;
    private final CustomBundlePublicationService publication;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json = new ObjectMapper();

    public AdminCustomRetryApplier(AdminGuard adminGuard, AdminCaller caller, AdminRetryRepository retries,
                                   S3Service s3Service, S3Storage storage, BundleSourcePlan plans,
                                   CustomBundlePublicationService publication, JdbcTemplate db,
                                   TransactionTemplate tx) {
        this.adminGuard = adminGuard;
        this.caller = caller;
        this.retries = retries;
        this.s3Service = s3Service;
        this.storage = storage;
        this.plans = plans;
        this.publication = publication;
        this.db = db;
        this.tx = tx;
    }

    private record Job(long id, long petId, long ownerId, String snapshot, String plan, String planHash, String state,
                       String error, String claimToken, String executionId, String manifestHash) {
    }

    // ── 러너 결과 ─────────────────────────────────────────────────────────

    public CandidatesResult customCandidates(Long callerId, long retryId, RetryCandidatesRequest body, Instant now) {
        adminGuard.require(callerId);
        AdminRetry r = retries.findById(retryId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "재시도 요청이 없습니다: " + retryId));
        if (r.getKind() != AdminRetryKind.CUSTOM) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "맞춤 재시도 요청이 아닙니다: " + retryId);
        }
        requireDeliverable(r);
        Map<String, String> files = body == null || body.files() == null ? Map.of() : body.files();
        String note = body == null ? null : body.note();
        if (files.isEmpty()) {
            deliver(retryId, AdminRetryResult.NONE, note, null, now);
            return new CandidatesResult(retryId, AdminRetryResult.NONE.name(), List.of(), "NO_FILES", false);
        }
        validatePaths(files);
        Job job = job(r.getTargetId());
        if (!AdminFailureQuery.applicable(job.state(), job.error())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "반영할 수 없는 잡 상태입니다(%s/%s)".formatted(job.state(), job.error()));
        }

        // 업로드 키는 이 호출자가 발급받은 안 쓴 키만.
        tx.executeWithoutResult(s -> files.values().forEach(k -> s3Service.consume(callerId, k, now)));

        String mismatch;
        String requestId = null;
        String manifestSha = null;
        try {
            String plan = planFor(job, files);
            requestId = AdminFailureQuery.customRequestId(job.id(), plan);
            String manifestRaw = read(files.get(MANIFEST));
            manifestSha = AdminFailureQuery.sha256(manifestRaw);
            mismatch = checkManifest(json.readTree(manifestRaw), requestId, files);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            mismatch = "manifest 를 읽지 못함 — " + e.getMessage();
        }
        if (mismatch != null) {
            deliver(retryId, AdminRetryResult.NONE, clip("manifest 불일치: " + mismatch, 2000), null, now);
            log.info("맞춤 재시도 결과 거부 — retry={} job={} : {}", retryId, job.id(), mismatch);
            return new CandidatesResult(retryId, AdminRetryResult.NONE.name(), List.of(), mismatch, false);
        }
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("files", new LinkedHashMap<>(files));
        keys.put("requestId", requestId);
        keys.put("manifestSha", manifestSha);
        AdminRetry delivered = deliver(retryId, AdminRetryResult.CANDIDATES, note, keys, now);
        log.info("맞춤 재시도 후보 도착 — retry={} job={} requestId={}", retryId, job.id(), requestId);
        if (delivered.isAuto()) {
            // 자동 행(결정 7·8) — 사람 없이 같은 반영 길. 실패는 행에 APPLY_FAILED 로 남고 러너에는 결과로 알린다.
            try {
                customAutoApply(retries.findById(retryId).orElseThrow(), now);
                return new CandidatesResult(retryId, AdminRetryResult.APPLIED.name(), List.of(), "PASS", true);
            } catch (RuntimeException e) {
                return new CandidatesResult(retryId, AdminRetryResult.APPLY_FAILED.name(), List.of(),
                        clip(e.getMessage(), NOTE_HEAD), false);
            }
        }
        return new CandidatesResult(retryId, AdminRetryResult.CANDIDATES.name(), List.of(), "PASS", false);
    }

    // ── 반영 ──────────────────────────────────────────────────────────────

    /** 사람이 "이걸로 지급". 봇 토큰이면 403. */
    public void customPick(Long adminId, long retryId, Instant now) {
        caller.requireHuman();
        adminGuard.require(adminId);
        AdminRetry r = retries.findById(retryId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "재시도 요청이 없습니다: " + retryId));
        apply(r, adminId, "APPROVED", now);
    }

    /** 자동 행(결정 7·8) — 서버가 만든 auto 행만. 사람 검수 표시는 UNREVIEWED 로 남긴다. */
    public void customAutoApply(AdminRetry r, Instant now) {
        if (!r.isAuto()) {
            throw new IllegalStateException("자동 반영은 자동 요청 행만: " + r.getId());
        }
        apply(r, null, "UNREVIEWED", now);
    }

    private void apply(AdminRetry r, Long by, String humanReview, Instant now) {
        if (r.getKind() != AdminRetryKind.CUSTOM) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "맞춤 재시도 요청이 아닙니다: " + r.getId());
        }
        if (r.getResult() != AdminRetryResult.CANDIDATES || r.getCandidateKeys() == null) {
            throw new BusinessException(ErrorCode.ADMIN_RETRY_CLOSED);
        }
        Map<String, Object> keys = r.getCandidateKeys();
        @SuppressWarnings("unchecked")
        Map<String, String> files = (Map<String, String>) keys.get("files");
        String requestId = (String) keys.get("requestId");
        String manifestRaw;
        try {
            manifestRaw = read(files.get(MANIFEST));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "manifest 를 읽지 못했습니다 — " + e.getMessage());
        }
        // ★ 후보 등록 때 본 바로 그 manifest 인가(등록 뒤 업로드 키의 내용이 바뀌었으면 거부, 수정 7).
        if (!AdminFailureQuery.sha256(manifestRaw).equals(keys.get("manifestSha"))) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "후보 등록 뒤 manifest 가 바뀌었습니다");
        }

        // ★ 1. 잠금 안에서 먼저 본다(수정 1) — 요청 행 → 펫 → 잡 순서로 잠그고, 잡 상태 · 지급 여부를 S3 쓰기 전에 확인한 뒤
        //   "반영 중" 표식을 올린다. 두 번째 반영 · 취소는 이 표식에 막힌다(409).
        Job job = tx.execute(s -> {
            AdminRetry row = retries.findByIdForUpdate(r.getId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "재시도 요청이 없습니다: " + r.getId()));
            if (row.getResult() != AdminRetryResult.CANDIDATES || row.getAppliedChoice() != null) {
                throw new BusinessException(ErrorCode.ADMIN_RETRY_CLOSED);
            }
            Job locked = job(r.getTargetId());
            db.queryForList("SELECT id FROM zzal_pet WHERE id = ? FOR UPDATE", locked.petId());
            Map<String, Object> current = db.queryForMap(
                    "SELECT state, error_code FROM zzal_custom_bundle_job WHERE id = ? FOR UPDATE", locked.id());
            if (!AdminFailureQuery.applicable((String) current.get("state"), (String) current.get("error_code"))) {
                throw new BusinessException(ErrorCode.INVALID_INPUT,
                        "반영할 수 없는 잡 상태입니다(%s/%s)".formatted(current.get("state"), current.get("error_code")));
            }
            requireNotGranted(locked.id());
            row.markApplying();
            return locked;
        });

        String prefix = "images/zzal/custom/" + requestId + "/";
        String backup = BACKUP_PREFIX + requestId + "/retry-" + r.getId() + "/";
        Map<String, Boolean> backedUp = new LinkedHashMap<>();
        boolean wrote = false;
        Snapshot before = null;
        try {
            JsonNode manifest = json.readTree(manifestRaw);
            String plan = planFor(job, files);
            // 2. 백업 → 3. 복사
            for (String path : files.keySet()) {
                if (!SOURCE_PLAN.equals(path)) {
                    backedUp.put(path, copyIfPresent(prefix + path, backup + path));
                }
            }
            wrote = true;
            for (Map.Entry<String, String> f : files.entrySet()) {
                if (!SOURCE_PLAN.equals(f.getKey())) {
                    copy(f.getValue(), prefix + f.getKey());
                }
            }
            // 4. 한 트랜잭션: 자산 · 잡 · 재료 장부(잡 상태 · 지급 여부를 한 번 더 본다)
            before = tx.execute(s -> write(job, manifest, manifestRaw, plan, requestId, humanReview, r.getId(), now));
            // 5. 트랜잭션 밖: 기존 게시 검증 전부 + 게시 + 지급
            publication.publish(job.ownerId(), job.id(), now);
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            Snapshot snap = before;
            if (snap != null) {
                tx.executeWithoutResult(s -> rollback(job.id(), snap));
            }
            // ★ 이번 호출이 실제로 덮어쓴 경우에만 되돌린다.
            if (wrote) {
                restore(prefix, backup, backedUp);
            }
            tx.executeWithoutResult(s -> retries.findByIdForUpdate(r.getId()).ifPresent(row -> {
                if (row.isOpen()) {
                    row.applyFailed(clip(reason, NOTE_HEAD), now);
                }
            }));
            log.warn("맞춤 재시도 반영 실패 — retry={} job={} : {}", r.getId(), job.id(), reason);
            if (e instanceof BusinessException be) {
                throw be;
            }
            throw new BusinessException(ErrorCode.INVALID_INPUT, "반영 실패 — " + clip(reason, NOTE_HEAD));
        }
        tx.executeWithoutResult(s -> retries.findByIdForUpdate(r.getId()).ifPresent(row -> {
            if (row.isOpen()) {
                row.applied(by, "custom", now);
            }
        }));
        log.info("맞춤 재시도 반영 — retry={} job={} requestId={} (by={})", r.getId(), job.id(), requestId, by);
    }

    /** 바꾸기 전 값 — 게시가 실패하면 이것으로 되돌린다(rollback.sql 과 같은 모양). */
    private record Snapshot(Map<String, Object> job, List<Map<String, Object>> assets,
                            List<Map<String, Object>> sources, List<Long> newAssetIds) {
    }

    private Snapshot write(Job job, JsonNode manifest, String manifestRaw, String plan, String requestId,
                           String humanReview, long retryId, Instant now) {
        db.queryForList("SELECT id FROM zzal_pet WHERE id = ? FOR UPDATE", job.petId());
        Map<String, Object> current = db.queryForMap("""
                SELECT state, error_code, claim_token, execution_id, manifest_hash, source_plan_json, source_plan_hash
                FROM zzal_custom_bundle_job WHERE id = ? FOR UPDATE
                """, job.id());
        String state = (String) current.get("state");
        String error = (String) current.get("error_code");
        boolean allowed = AdminFailureQuery.applicable(state, error);
        if (!allowed) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "반영할 수 없는 잡 상태입니다(%s/%s)".formatted(state, error));
        }
        requireNotGranted(job.id());
        List<Map<String, Object>> oldAssets = db.queryForList(
                "SELECT * FROM zzal_custom_bundle_asset WHERE job_id = ? ORDER BY motion_order FOR UPDATE", job.id());
        List<Map<String, Object>> sources = db.queryForList(
                "SELECT * FROM zzal_custom_bundle_source WHERE job_id = ? FOR UPDATE", job.id());

        db.update("DELETE FROM zzal_custom_bundle_asset WHERE job_id = ?", job.id());
        List<Long> ids = new ArrayList<>();
        for (JsonNode a : manifest.path("assets")) {
            ids.add(db.queryForObject("""
                    INSERT INTO zzal_custom_bundle_asset (job_id, motion_order, asset_key, asset_hash, auto_check,
                                                          human_review, published)
                    VALUES (?, ?, ?, ?, 'PASS', ?, false) RETURNING id
                    """, Long.class, job.id(), a.path("motionOrder").asInt(), a.path("assetKey").asText(),
                    a.path("sha256").asText(), humanReview));
        }
        String claim = current.get("claim_token") != null ? (String) current.get("claim_token") : "admin-retry-" + retryId;
        if (job.plan() == null) {
            db.update("UPDATE zzal_custom_bundle_job SET source_plan_json = ?, source_plan_hash = ? WHERE id = ?",
                    plan, AdminFailureQuery.sha256(plan), job.id());
        }
        db.update("""
                UPDATE zzal_custom_bundle_job
                SET state = 'GENERATED', execution_id = ?, manifest_hash = ?, error_code = NULL, claim_token = ?
                WHERE id = ?
                """, requestId, AdminFailureQuery.sha256(manifestRaw), claim, job.id());
        try {
            List<BundleSourcePlan.Source> used = new ArrayList<>();
            for (JsonNode u : manifest.path("usedSources")) {
                used.add(new BundleSourcePlan.Source(u.path("factId").asLong(), u.path("revision").asInt()));
            }
            plans.complete(job.id(), job.snapshot(), used, BundleSourcePlan.ExecutionOutcome.GENERATED_COMPLETE, now);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("재료 장부 기록 실패: " + e.getMessage(), e);
        }
        return new Snapshot(current, oldAssets, sources, ids);
    }

    private void rollback(long jobId, Snapshot s) {
        if (!s.newAssetIds().isEmpty()) {
            String in = String.join(",", s.newAssetIds().stream().map(String::valueOf).toList());
            db.update("UPDATE zzal_attendance_reward_slot SET state = 'ELIGIBLE', asset_id = NULL, asset_key = NULL, "
                    + "granted_at = NULL WHERE asset_id IN (" + in + ")");
        }
        db.update("DELETE FROM zzal_custom_bundle_asset WHERE job_id = ?", jobId);
        for (Map<String, Object> a : s.assets()) {
            db.update("""
                    INSERT INTO zzal_custom_bundle_asset (id, job_id, motion_order, asset_key, asset_hash, auto_check,
                                                          human_review, published)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, a.get("id"), a.get("job_id"), a.get("motion_order"), a.get("asset_key"), a.get("asset_hash"),
                    a.get("auto_check"), a.get("human_review"), a.get("published"));
        }
        Map<String, Object> j = s.job();
        db.update("""
                UPDATE zzal_custom_bundle_job
                SET state = ?, error_code = ?, claim_token = ?, execution_id = ?, manifest_hash = ?,
                    source_plan_json = ?, source_plan_hash = ?
                WHERE id = ?
                """, j.get("state"), j.get("error_code"), j.get("claim_token"), j.get("execution_id"),
                j.get("manifest_hash"), j.get("source_plan_json"), j.get("source_plan_hash"), jobId);
        for (Map<String, Object> src : s.sources()) {
            db.update("UPDATE zzal_custom_bundle_source SET state = ?, used_at = ? WHERE job_id = ? AND fact_id = ? "
                            + "AND fact_revision = ?", src.get("state"), src.get("used_at"), jobId, src.get("fact_id"),
                    src.get("fact_revision"));
        }
    }

    // ── 검사 ──────────────────────────────────────────────────────────────

    static void validatePaths(Map<String, String> files) {
        for (String path : files.keySet()) {
            boolean known = REQUIRED.contains(path) || OPTIONAL.contains(path) || FRAME.matcher(path).matches();
            if (path == null || path.contains("..") || path.startsWith("/") || !known) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "받지 않는 파일 경로입니다: " + path);
            }
            String key = files.get(path);
            if (key == null || !key.startsWith("images/zzal/") || key.contains("..")) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "업로드 키가 올바르지 않습니다: " + path);
            }
        }
        for (String need : REQUIRED) {
            if (!files.containsKey(need)) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "빠진 파일이 있습니다: " + need);
            }
        }
    }

    /** null 이면 통과, 아니면 사람이 읽을 불일치 사유. */
    private String checkManifest(JsonNode manifest, String requestId, Map<String, String> files) throws Exception {
        if (requestId == null) {
            return "계획이 없어 requestId 를 정할 수 없음";
        }
        if (!requestId.equals(manifest.path("requestId").asText())) {
            return "requestId %s ≠ %s".formatted(manifest.path("requestId").asText(), requestId);
        }
        if (!requestId.equals(manifest.path("executionId").asText())) {
            return "executionId 가 requestId 와 다름";
        }
        JsonNode assets = manifest.path("assets");
        if (!assets.isArray() || assets.size() != 8) {
            return "자산이 8개가 아님";
        }
        Set<Integer> seen = new LinkedHashSet<>();
        for (JsonNode a : assets) {
            int order = a.path("motionOrder").asInt();
            if (order < 1 || order > 8 || !seen.add(order)) {
                return "motionOrder 이상: " + order;
            }
            String slot = CustomGridPrompt.SLOTS.get(order - 1);
            String expected = "images/zzal/custom/%s/%s.webp".formatted(requestId, slot);
            if (!expected.equals(a.path("assetKey").asText())) {
                return "자산 키 %s ≠ %s".formatted(a.path("assetKey").asText(), expected);
            }
            String actual = sha256Bytes(files.get(slot + ".webp"));
            if (!actual.equals(a.path("sha256").asText())) {
                return slot + ".webp 해시가 manifest 와 다름";
            }
        }
        return null;
    }

    /** 이 잡의 계획 원문 — 있으면 그대로, 없으면 러너가 올린 계획(결정 5). 계획 교체는 거부. */
    private String planFor(Job job, Map<String, String> files) throws Exception {
        String uploaded = files.containsKey(SOURCE_PLAN) ? read(files.get(SOURCE_PLAN)) : null;
        if (job.plan() != null) {
            if (uploaded != null && !uploaded.equals(job.plan())) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "이미 계획이 있는 잡의 계획은 바꿀 수 없습니다");
            }
            return job.plan();
        }
        if (uploaded == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "계획이 없는 잡입니다 — source-plan.json 을 같이 올리세요");
        }
        json.readTree(uploaded); // 모양이 JSON 인지만 — 세부 검증은 게시 단계(CustomGenerationRequest.from)
        return uploaded;
    }

    private void requireNotGranted(long jobId) {
        Integer granted = db.queryForObject("""
                SELECT count(*) FROM zzal_attendance_reward_slot s JOIN zzal_custom_bundle_asset a ON a.id = s.asset_id
                WHERE a.job_id = ? AND s.state = 'GRANTED'
                """, Integer.class, jobId);
        if (granted != null && granted > 0) {
            throw new BusinessException(ErrorCode.ADMIN_RETRY_ALREADY_GRANTED);
        }
    }

    private static void requireDeliverable(AdminRetry r) {
        if (r.getResult() != null) {
            throw new BusinessException(ErrorCode.ADMIN_RETRY_CLOSED);
        }
        if (r.getPickedAt() == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "러너가 먼저 집어야 합니다(claim)");
        }
    }

    private AdminRetry deliver(long retryId, AdminRetryResult result, String note, Map<String, Object> keys,
                               Instant now) {
        return tx.execute(s -> {
            AdminRetry row = retries.findByIdForUpdate(retryId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "재시도 요청이 없습니다: " + retryId));
            requireDeliverable(row);
            row.deliver(result, note, keys, now);
            return row;
        });
    }

    private Job job(long jobId) {
        return db.query("""
                SELECT j.id, j.pet_id, p.user_id, j.snapshot_json, j.source_plan_json, j.source_plan_hash, j.state,
                       j.error_code, j.claim_token, j.execution_id, j.manifest_hash
                FROM zzal_custom_bundle_job j JOIN zzal_pet p ON p.id = j.pet_id WHERE j.id = ?
                """, (rs, n) -> new Job(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                rs.getString(11)), jobId).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "맞춤 잡이 없습니다: " + jobId));
    }

    // ── S3 ────────────────────────────────────────────────────────────────

    private String read(String key) throws Exception {
        Path tmp = Files.createTempFile("zzal-admin-custom-", ".bin");
        try {
            storage.download(key, tmp);
            return Files.readString(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private String sha256Bytes(String key) throws Exception {
        Path tmp = Files.createTempFile("zzal-admin-custom-", ".bin");
        try {
            storage.download(key, tmp);
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(tmp)));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** 내려받아 다시 올린다(S3Storage 에 서버 측 copy 가 없다 — AdminLayerService.copy 와 같은 방식). */
    private void copy(String from, String to) throws Exception {
        Path tmp = Files.createTempFile("zzal-admin-custom-", ".bin");
        try {
            storage.download(from, tmp);
            storage.upload(to, tmp, contentType(to));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** 있으면 백업하고 true, 없으면 false. 있는지 모르면(권한 · 네트워크) 던진다 — 덮어쓰기 전에 멈춘다. */
    private boolean copyIfPresent(String from, String to) throws Exception {
        if (!storage.exists(from)) {
            return false;
        }
        copy(from, to);
        return true;
    }

    /** 백업을 원래 자리로. 백업이 없던 자리(새로 생긴 객체)는 지운다. 실패해도 나머지는 계속한다. */
    private void restore(String prefix, String backup, Map<String, Boolean> backedUp) {
        List<String> created = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : backedUp.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue())) {
                try {
                    copy(backup + e.getKey(), prefix + e.getKey());
                } catch (Exception ex) {
                    log.error("맞춤 재시도 백업 복원 실패 — {} : {}", prefix + e.getKey(), String.valueOf(ex));
                }
            } else {
                created.add(prefix + e.getKey());
            }
        }
        if (!created.isEmpty()) {
            try {
                storage.delete(created);
            } catch (RuntimeException ex) {
                log.error("맞춤 재시도 새 객체 정리 실패 — {}개 : {}", created.size(), String.valueOf(ex));
            }
        }
    }

    static String contentType(String key) {
        if (key.endsWith(".webp")) {
            return "image/webp";
        }
        if (key.endsWith(".gif")) {
            return "image/gif";
        }
        if (key.endsWith(".png")) {
            return "image/png";
        }
        if (key.endsWith(".json")) {
            return "application/json";
        }
        return "application/octet-stream";
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
