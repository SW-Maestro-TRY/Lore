package com.lore.zzal.admin.retry;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.s3.S3Service;
import com.lore.zzal.admin.AdminCaller;
import com.lore.zzal.admin.AdminGuard;
import com.lore.zzal.admin.AdminLayerService;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 부화 재시도 — 러너 결과 등록과 사람이 고른 후보 반영(설계 2절, 리뷰 초점 4).
 *
 * <h3>길</h3>
 * 러너가 집은 행(claim)에 격자 1~3장 + (시트부터 실패했으면) 시트·문단을 올린다 → 기존 1층 후보 길
 * ({@link AdminLayerService#candidates})로 게이트를 태운다 → 사람이 하나 고르면 기존 1층 pick 으로 살린다.
 * 러너가 만든 시트·문단은 {@link AdminLayerService.ReviveMaterial} 로 단계 기록 대신 쓴다.
 *
 * ★ 취소된 행에 늦게 온 결과는 409 로 거부하고 아무것도 바꾸지 않는다 — 층 후보도 올리지 않는다.
 * ★ 고르기는 사람만(봇 토큰이면 403). 자동 반영(1.9)은 별도 길이다.
 */
@Component
public class AdminHatchRetryApplier {

    private static final Logger log = LoggerFactory.getLogger(AdminHatchRetryApplier.class);

    /**
     * 러너 결과.
     *
     * @param gridKeys     HATCH: 격자 png 키(presign 으로 올린 것). 0개면 "후보 없음"(NONE)
     * @param sheetKey     HATCH: 러너가 새로 만든 시트 키(있을 때만, presign 키)
     * @param identityText HATCH: 러너가 새로 만든 정체성 문단(있을 때만)
     * @param files        CUSTOM: 논리 경로 → 업로드 키(작업 1.7)
     * @param note         러너 메모
     */
    public record RetryCandidatesRequest(@Size(max = AdminLayerService.MAX_CANDIDATES) List<String> gridKeys,
                                    String sheetKey, @Size(max = 1000) String identityText,
                                    Map<String, String> files,
                                    @Size(max = 2000) String note) {
    }

    public record CandidatesResult(long retryId, String result, List<AdminLayerService.Candidate> hatch,
                                   String customCheck, boolean autoApplied) {
    }

    private final AdminGuard adminGuard;
    private final AdminCaller caller;
    private final AdminRetryRepository retries;
    private final AdminLayerService layerService;
    private final S3Service s3Service;
    private final TransactionTemplate tx;

    public AdminHatchRetryApplier(AdminGuard adminGuard, AdminCaller caller, AdminRetryRepository retries,
                                  AdminLayerService layerService, S3Service s3Service, TransactionTemplate tx) {
        this.adminGuard = adminGuard;
        this.caller = caller;
        this.retries = retries;
        this.layerService = layerService;
        this.s3Service = s3Service;
        this.tx = tx;
    }

    // ── 러너 결과 ─────────────────────────────────────────────────────────

    public CandidatesResult hatchCandidates(Long callerId, long retryId, RetryCandidatesRequest body, Instant now) {
        adminGuard.require(callerId);
        AdminRetry r = retries.findById(retryId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "재시도 요청이 없습니다: " + retryId));
        requireDeliverable(r);
        if (r.getKind() != AdminRetryKind.HATCH) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "부화 재시도 요청이 아닙니다: " + retryId);
        }
        List<String> grids = body == null || body.gridKeys() == null ? List.of() : body.gridKeys();
        String note = body == null ? null : body.note();

        if (grids.isEmpty()) {
            AdminRetry closed = deliver(retryId, AdminRetryResult.NONE, note, null, now);
            // 1층 다시 만들기 표시도 거둔다 — 남아 있으면 옛 러너가 같은 알을 또 굽는다.
            layerService.cancelRegen(callerId, closed.getTargetId(), 1);
            log.info("부화 재시도 결과 없음 — retry={} pet={}", retryId, closed.getTargetId());
            return new CandidatesResult(retryId, AdminRetryResult.NONE.name(), List.of(), null, false);
        }
        if (grids.size() > AdminLayerService.MAX_CANDIDATES) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "후보는 1~%d장입니다".formatted(AdminLayerService.MAX_CANDIDATES));
        }
        String sheetKey = blankToNull(body.sheetKey());
        String identity = blankToNull(body.identityText());
        // ★ 취소와 경합하지 않게(수정 7) — 굽기 전에 행을 잠가 "등록 중" 표식을 올린다. 취소 · 두 번째 등록은 409.
        startDelivery(retryId);
        List<AdminLayerService.Candidate> baked;
        try {
            if (sheetKey != null) {
                // 같은 호출자가 발급받은 안 쓴 키만 — 남의 키·이미 쓴 키는 거부.
                tx.executeWithoutResult(s -> s3Service.consume(callerId, sheetKey, now));
            }
            baked = layerService.candidates(callerId, r.getTargetId(), 1, grids, now);
        } catch (RuntimeException e) {
            endDelivery(retryId);
            throw e;
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        for (AdminLayerService.Candidate c : baked) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("candidateId", c.candidateId());
            e.put("gate", c.gate());
            e.put("previewKey", c.previewKey());
            e.put("previewKeys", c.previewKeys()); // 화면이 후보 8종을 움직이는 채로 나란히 보이게
            entries.add(e);
        }
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("grids", entries);
        keys.put("sheetKey", sheetKey);
        keys.put("identityText", identity);

        if (r.isAuto()) {
            return autoApply(callerId, retryId, r.getTargetId(), baked, keys, note, now);
        }
        deliver(retryId, AdminRetryResult.CANDIDATES, note, keys, now);
        log.info("부화 재시도 후보 도착 — retry={} pet={} 후보 {}장", retryId, r.getTargetId(), baked.size());
        return new CandidatesResult(retryId, AdminRetryResult.CANDIDATES.name(), baked, null, false);
    }

    /**
     * 자동 행(결정 7·8) — 게이트를 통과한 첫 후보로 사람 없이 반영한다. 통과가 0장이면 NONE, 알은 FAILED 그대로.
     * 호출자는 봇 사용자(관리자)라 Guard 를 통과한다. 행은 서버만 만들므로 봇이 자동 반영 길을 열 수는 없다.
     */
    private CandidatesResult autoApply(Long callerId, long retryId, long petId, List<AdminLayerService.Candidate> baked,
                                       Map<String, Object> keys, String note, Instant now) {
        AdminLayerService.Candidate pass = baked.stream().filter(c -> "PASS".equals(c.gate())).findFirst().orElse(null);
        if (pass == null) {
            deliver(retryId, AdminRetryResult.NONE, note == null ? "자동: 게이트 통과 후보 없음" : note, keys, now);
            layerService.cancelRegen(callerId, petId, 1); // 옛 러너가 또 굽지 않게
            log.info("자동 CLI 결과 — 통과 후보 없음 retry={} pet={}", retryId, petId);
            return new CandidatesResult(retryId, AdminRetryResult.NONE.name(), baked, null, false);
        }
        deliver(retryId, AdminRetryResult.CANDIDATES, note, keys, now);
        var material = new AdminLayerService.ReviveMaterial((String) keys.get("sheetKey"), (String) keys.get("identityText"));
        try {
            layerService.pick(callerId, petId, 1, pass.candidateId(), now, material);
        } catch (RuntimeException e) {
            String reason = e instanceof BusinessException be ? be.getMessage() : String.valueOf(e);
            tx.executeWithoutResult(s -> retries.findByIdForUpdate(retryId).ifPresent(row -> {
                if (row.isOpen()) {
                    row.applyFailed(reason, now);
                }
            }));
            log.warn("자동 CLI 반영 실패 — retry={} pet={} : {}", retryId, petId, reason);
            return new CandidatesResult(retryId, AdminRetryResult.APPLY_FAILED.name(), baked, null, false);
        }
        tx.executeWithoutResult(s -> retries.findByIdForUpdate(retryId).ifPresent(row -> {
            if (row.isOpen()) {
                row.applied(null, pass.candidateId(), now);
            }
        }));
        log.info("자동 CLI 반영 — retry={} pet={} 후보={}", retryId, petId, pass.candidateId());
        return new CandidatesResult(retryId, AdminRetryResult.APPLIED.name(), baked, null, true);
    }

    // ── 고르기 ────────────────────────────────────────────────────────────

    public AdminLayerService.Picked hatchPick(Long adminId, long retryId, String candidateId, Instant now) {
        caller.requireHuman();
        adminGuard.require(adminId);
        AdminRetry r = retries.findById(retryId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "재시도 요청이 없습니다: " + retryId));
        if (r.getKind() != AdminRetryKind.HATCH) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "부화 재시도 요청이 아닙니다: " + retryId);
        }
        if (r.getResult() != AdminRetryResult.CANDIDATES) {
            throw new BusinessException(ErrorCode.ADMIN_RETRY_CLOSED);
        }
        Map<String, Object> keys = r.getCandidateKeys() == null ? Map.of() : r.getCandidateKeys();
        Map<?, ?> chosen = grids(keys).stream()
                .filter(g -> candidateId != null && candidateId.equals(g.get("candidateId")))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "그 후보가 없습니다: " + candidateId));
        if (!"PASS".equals(chosen.get("gate"))) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "게이트를 통과한 후보만 고를 수 있습니다(%s)".formatted(chosen.get("gate")));
        }
        var material = new AdminLayerService.ReviveMaterial((String) keys.get("sheetKey"),
                (String) keys.get("identityText"));
        AdminLayerService.Picked picked;
        try {
            picked = layerService.pick(adminId, r.getTargetId(), 1, candidateId, now, material);
        } catch (RuntimeException e) {
            String reason = e instanceof BusinessException be ? be.getMessage() : String.valueOf(e);
            tx.executeWithoutResult(s -> retries.findByIdForUpdate(retryId).ifPresent(row -> {
                if (row.isOpen()) {
                    row.applyFailed(reason, now);
                }
            }));
            log.warn("부화 재시도 반영 실패 — retry={} pet={} : {}", retryId, r.getTargetId(), reason);
            throw e;
        }
        tx.executeWithoutResult(s -> retries.findByIdForUpdate(retryId).ifPresent(row -> {
            if (row.isOpen()) {
                row.applied(adminId, candidateId, now);
            }
        }));
        log.info("부화 재시도 반영 — retry={} pet={} 후보={} (admin={})", retryId, r.getTargetId(), candidateId, adminId);
        return picked;
    }

    // ── 잔손 ──────────────────────────────────────────────────────────────

    private void startDelivery(long retryId) {
        tx.executeWithoutResult(s -> {
            AdminRetry row = retries.findByIdForUpdate(retryId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "재시도 요청이 없습니다: " + retryId));
            requireDeliverable(row);
            if (row.getAppliedChoice() != null) {
                throw new BusinessException(ErrorCode.ADMIN_RETRY_CLOSED);
            }
            row.markDelivering();
        });
    }

    private void endDelivery(long retryId) {
        tx.executeWithoutResult(s -> retries.findByIdForUpdate(retryId).ifPresent(AdminRetry::clearDelivering));
    }

    /** 러너가 집었고 아직 결과가 없는 행만 결과를 받는다. 취소·이미 결과 있음은 409, 안 집음은 400. */
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
            // ★ 굽는 사이 취소됐으면 여기서 다시 막는다.
            requireDeliverable(row);
            row.deliver(result, note, keys, now);
            return row;
        });
    }

    private static List<Map<?, ?>> grids(Map<String, Object> keys) {
        Object g = keys.get("grids");
        if (!(g instanceof List<?> list)) {
            return List.of();
        }
        List<Map<?, ?>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                out.add(m);
            }
        }
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
