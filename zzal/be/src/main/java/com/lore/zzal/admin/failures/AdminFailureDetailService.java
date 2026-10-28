package com.lore.zzal.admin.failures;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.admin.AdminGuard;
import com.lore.zzal.admin.AdminLayerService;
import com.lore.zzal.admin.failures.AdminFailureResponses.FailureDetail;
import com.lore.zzal.admin.failures.AdminFailureResponses.FailureRow;
import com.lore.zzal.admin.failures.AdminFailureResponses.Images;
import com.lore.zzal.admin.failures.AdminFailureResponses.RetryHistory;
import com.lore.zzal.admin.failures.AdminFailureResponses.Step;
import com.lore.zzal.admin.retry.AdminRetry;
import com.lore.zzal.admin.retry.AdminRetryKind;
import com.lore.zzal.admin.retry.AdminRetryRepository;
import com.lore.zzal.generation.GenJob;
import com.lore.zzal.generation.GenJobRepository;
import com.lore.zzal.generation.GenKind;
import com.lore.zzal.generation.GenStatus;
import com.lore.zzal.generation.GenStepRecord;
import com.lore.zzal.generation.GenStepRecordRepository;
import com.lore.zzal.generation.steps.GridStep;
import com.lore.zzal.generation.steps.IdentityStep;
import com.lore.zzal.generation.steps.PostProcessStep;
import com.lore.zzal.generation.steps.SheetStep;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.MotionImageKeys;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalPetRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 실패 한 건의 상세 — 입력 · 시트 · 격자 · 결과 · 단계 타임라인 · 재시도 이력(결정 2).
 *
 * ★ 읽기만 한다. S3 목록(보존 격자)을 못 읽으면 빈 목록으로 내보낸다(AdminLayerService 와 같은 규칙).
 */
@Service
public class AdminFailureDetailService {

    private static final int ERROR_DETAIL_MAX = 2000;

    private final AdminGuard adminGuard;
    private final AdminFailureQuery query;
    private final ZzalPetRepository petRepository;
    private final GenJobRepository jobRepository;
    private final GenStepRecordRepository stepRepository;
    private final AdminLayerService layerService;
    private final MotionCatalog catalog;
    private final AdminRetryRepository retries;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public AdminFailureDetailService(AdminGuard adminGuard, AdminFailureQuery query, ZzalPetRepository petRepository,
                                     GenJobRepository jobRepository, GenStepRecordRepository stepRepository,
                                     AdminLayerService layerService, MotionCatalog catalog,
                                     AdminRetryRepository retries, JdbcTemplate jdbc) {
        this.adminGuard = adminGuard;
        this.query = query;
        this.petRepository = petRepository;
        this.jobRepository = jobRepository;
        this.stepRepository = stepRepository;
        this.layerService = layerService;
        this.catalog = catalog;
        this.retries = retries;
        this.jdbc = jdbc;
    }

    public FailureDetail detail(Long adminId, String kind, long id, Instant now) {
        adminGuard.require(adminId);
        AdminRetryKind k = AdminFailureQuery.parseKind(kind);
        return k == AdminRetryKind.HATCH ? hatch(id, now) : custom(id, now);
    }

    // ── 부화 ──────────────────────────────────────────────────────────────

    private FailureDetail hatch(long petId, Instant now) {
        ZzalPet pet = petRepository.findById(petId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND));
        FailureRow row = query.hatchRow(petId, now).orElse(null);

        String sheet = null;
        String identity = null;
        List<String> grids = new ArrayList<>();
        for (GenStepRecord r : stepRepository.findSucceededByPet(petId, GenKind.HATCH)) {
            if (SheetStep.NAME.equals(r.getName()) && sheet == null) {
                sheet = r.getOutputKey();
            } else if (IdentityStep.NAME.equals(r.getName()) && identity == null) {
                identity = r.getOutputText();
            } else if ((GridStep.NAME.equals(r.getName()) || PostProcessStep.GRID2.equals(r.getName()))
                    && r.getOutputKey() != null) {
                grids.add(r.getOutputKey());
            }
        }
        List<String> rejected = new ArrayList<>(layerService.rejectedKeys(petId, 1));
        rejected.addAll(layerService.rejectedKeys(petId, 2));

        Map<String, String> results = new LinkedHashMap<>();
        if (pet.getBasicRound() > 0) {
            for (String key : catalog.basicKeys()) {
                results.put(key, MotionImageKeys.basic(petId, pet.getBasicRound(), key));
            }
        }

        List<Step> steps = new ArrayList<>();
        String errorDetail = null;
        for (GenJob job : jobRepository.findByPetIdOrderByIdAsc(petId)) {
            if (job.getKind() != GenKind.HATCH) {
                continue;
            }
            for (GenStepRecord r : stepRepository.findByJobIdOrderBySeqAsc(job.getId())) {
                Long ms = r.getStartedAt() == null || r.getFinishedAt() == null ? null
                        : Duration.between(r.getStartedAt(), r.getFinishedAt()).toMillis();
                steps.add(new Step(job.getId(), job.getAttempt(), r.getName(), r.getStatus().name(), r.getModel(),
                        r.getErrorCode() == null ? null : r.getErrorCode().name(),
                        r.getStartedAt(), r.getFinishedAt(), ms, r.getCostUsd()));
                if (r.getStatus() == GenStatus.FAILED && r.getOutputText() != null && !r.getOutputText().isBlank()) {
                    errorDetail = clip(r.getOutputText());
                }
            }
        }

        Images images = new Images(pet.getSourceImageKey(), sheet != null ? sheet : pet.getSheetImageKey(),
                grids, rejected, results, identity != null ? identity : pet.getIdentityText());
        boolean identityMissing = identity == null || identity.isBlank();
        return new FailureDetail(row, images, steps, history(AdminRetryKind.HATCH, petId), errorDetail,
                identityMissing);
    }

    // ── 맞춤 ──────────────────────────────────────────────────────────────

    private record Job(long id, long petId, String snapshot, String plan, String state, String error,
                       Instant createdAt, Instant cliStartedAt, String manifest) {
    }

    private FailureDetail custom(long jobId, Instant now) {
        Job job = jdbc.query("""
                SELECT id, pet_id, snapshot_json, source_plan_json, state, error_code, created_at, cli_started_at,
                       manifest_hash
                FROM zzal_custom_bundle_job WHERE id = ?
                """, (rs, n) -> new Job(rs.getLong("id"), rs.getLong("pet_id"), rs.getString("snapshot_json"),
                rs.getString("source_plan_json"), rs.getString("state"), rs.getString("error_code"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("cli_started_at")),
                rs.getString("manifest_hash")), jobId).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "맞춤 잡이 없습니다: " + jobId));
        FailureRow row = query.customRow(jobId, now).orElse(null);

        JsonNode snap = readTree(job.snapshot());
        String requestId = AdminFailureQuery.customRequestId(job.id(), job.plan());
        List<String> grids = requestId == null ? List.of()
                : List.of("images/zzal/custom/" + requestId + "/grid.png");

        Map<String, String> results = new LinkedHashMap<>();
        int published = 0;
        for (Map<String, Object> a : jdbc.queryForList("""
                SELECT motion_order, asset_key, published FROM zzal_custom_bundle_asset WHERE job_id = ?
                ORDER BY motion_order
                """, jobId)) {
            results.put(String.valueOf(a.get("motion_order")), (String) a.get("asset_key"));
            if (Boolean.TRUE.equals(a.get("published"))) {
                published++;
            }
        }

        String identity = text(snap, "identity");
        Images images = new Images(text(snap, "sourceImageKey"), text(snap, "sheetImageKey"), grids, List.of(),
                results, identity);

        boolean planned = job.plan() != null;
        boolean planning = "PLANNING_IN_PROGRESS".equals(job.error());
        boolean generated = "GENERATED".equals(job.state());
        boolean failed = "FAILED".equals(job.state());
        List<Step> steps = List.of(
                new Step(job.id(), 0, "접수", "SUCCEEDED", null, null, job.createdAt(), job.createdAt(), 0L, null),
                new Step(job.id(), 0, "계획", planned ? "SUCCEEDED" : (planning ? "RUNNING" : "QUEUED"), null,
                        planning ? job.error() : null, null, null, null, null),
                new Step(job.id(), 0, "그림", job.cliStartedAt() == null ? "QUEUED"
                        : (generated || job.manifest() != null ? "SUCCEEDED" : (failed ? "FAILED" : "RUNNING")),
                        null, failed || "CLAIMED".equals(job.state()) ? job.error() : null,
                        job.cliStartedAt(), null, null, null),
                new Step(job.id(), 0, "후처리·게시", published >= 8 ? "SUCCEEDED"
                        : (failed ? "FAILED" : (generated ? "RUNNING" : "QUEUED")),
                        null, failed ? job.error() : null, null, null, null, null));

        String errorDetail = job.error() == null ? null : AdminFailureResponses.reasonText(job.error());
        return new FailureDetail(row, images, steps, history(AdminRetryKind.CUSTOM, jobId), errorDetail,
                identity == null || identity.isBlank());
    }

    // ── 공용 ──────────────────────────────────────────────────────────────

    private List<RetryHistory> history(AdminRetryKind kind, long targetId) {
        return retries.findByKindAndTargetIdInOrderByRequestedAtDescIdDesc(kind, List.of(targetId)).stream()
                .map(AdminFailureDetailService::toHistory).toList();
    }

    static RetryHistory toHistory(AdminRetry r) {
        return new RetryHistory(r.getId(), r.isAuto(), r.getMemo(), r.getRequestedAt(), r.getPickedAt(),
                r.getFinishedAt(), r.getResult() == null ? null : r.getResult().name(), r.getNote(),
                r.getCandidateKeys(), r.getAppliedAt(), r.getAppliedChoice());
    }

    private JsonNode readTree(String raw) {
        try {
            return raw == null ? json.createObjectNode() : json.readTree(raw);
        } catch (Exception e) {
            return json.createObjectNode();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static String clip(String s) {
        return s.length() <= ERROR_DETAIL_MAX ? s : s.substring(0, ERROR_DETAIL_MAX);
    }
}
