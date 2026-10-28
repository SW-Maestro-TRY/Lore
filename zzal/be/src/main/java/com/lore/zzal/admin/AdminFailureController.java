package com.lore.zzal.admin;

import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.response.ApiResponse;
import com.lore.zzal.admin.failures.AdminFailureDetailService;
import com.lore.zzal.admin.failures.AdminFailureQuery;
import com.lore.zzal.admin.failures.AdminFailureResponses.FailureDetail;
import com.lore.zzal.admin.failures.AdminFailureResponses.FailurePage;
import com.lore.zzal.admin.retry.AdminCustomRetryApplier;
import com.lore.zzal.admin.retry.AdminHatchRetryApplier;
import com.lore.zzal.admin.retry.AdminHatchRetryApplier.RetryCandidatesRequest;
import com.lore.zzal.admin.retry.AdminHatchRetryApplier.CandidatesResult;
import com.lore.zzal.admin.retry.AdminRetry;
import com.lore.zzal.admin.retry.AdminRetryKind;
import com.lore.zzal.admin.retry.AdminRetryRepository;
import com.lore.zzal.admin.retry.AdminRetryService;
import com.lore.zzal.admin.retry.AdminRetryService.PendingItem;
import com.lore.zzal.admin.retry.AdminRetryService.RetryView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 관리자 실패 추적 · 재시도(설계 spec-zzal-admin-failures-retry-20261027 2절).
 *
 * <pre>
 *   GET  /api/zzal/v1/admin/failures?days=7                 실패 목록 + 머리 숫자 + 시간대 칸(쿠키 · 봇)
 *   GET  /api/zzal/v1/admin/failures/{kind}/{id}            상세(쿠키 · 봇)
 *   POST /api/zzal/v1/admin/failures/{kind}/{id}/retry      재시도 요청 {memo}(쿠키만)
 *   POST /api/zzal/v1/admin/retry/{id}/cancel               취소(쿠키만)
 *   GET  /api/zzal/v1/admin/retry/pending                   러너 대기 목록 — 러너 확인 시각 기록(봇 · 쿠키)
 *   POST /api/zzal/v1/admin/retry/{id}/claim                러너가 집음 + 재료(봇 · 쿠키)
 *   POST /api/zzal/v1/admin/retry/{id}/candidates           러너 결과(봇 · 쿠키)
 *   POST /api/zzal/v1/admin/retry/{id}/pick                 후보 반영 {choice}(쿠키만 — 봇 403)
 *   GET  /api/zzal/v1/admin/runner                          러너 마지막 확인(쿠키 · 봇)
 * </pre>
 *
 * ★ 봇 토큰이 할 수 있는 일은 목록 읽기 · 집기 · 후보 올리기뿐이다. 요청 · 취소 · 고르기는 사람만(설계 6절).
 * ★ 재시도는 맥미니 러너 길 하나다 — 서버에서 이미지 API 를 부르는 길은 없다(과금 없음).
 */
@Tag(name = "관리자", description = "밤에 구운 움짤 검수. 운영에서는 꺼져 있어 존재하지 않는다")
@RestController
@RequestMapping("/api/zzal/v1/admin")
@ConditionalOnProperty(name = "app.zzal.admin.enabled", havingValue = "true")
public class AdminFailureController {

    /** 러너가 이보다 오래 안 보이면 멈춘 것으로 본다(맥미니 주기 2분 — 여러 번 거른 뒤에야 꺼짐으로 본다). */
    static final Duration RUNNER_STALE = Duration.ofMinutes(10);

    private final AdminGuard adminGuard;
    private final AdminCaller caller;
    private final AdminFailureQuery query;
    private final AdminFailureDetailService details;
    private final AdminRetryService retryService;
    private final AdminRetryRepository retries;
    private final AdminHatchRetryApplier hatchApplier;
    private final AdminCustomRetryApplier customApplier;

    public AdminFailureController(AdminGuard adminGuard, AdminCaller caller, AdminFailureQuery query,
                                  AdminFailureDetailService details, AdminRetryService retryService,
                                  AdminRetryRepository retries, AdminHatchRetryApplier hatchApplier,
                                  AdminCustomRetryApplier customApplier) {
        this.adminGuard = adminGuard;
        this.caller = caller;
        this.query = query;
        this.details = details;
        this.retryService = retryService;
        this.retries = retries;
        this.hatchApplier = hatchApplier;
        this.customApplier = customApplier;
    }

    public record RetryMemoRequest(@Size(max = 500) String memo) {
    }

    public record RetryPickRequest(@Size(max = 100) String choice) {
    }

    public record RetryRunnerView(Instant lastSeenAt, Long minutesAgo, boolean stale) {
    }

    @Operation(summary = "실패 목록", description = """
            사용자가 결과를 못 받은 부화(FAILED · 멈춘 알)와 맞춤 8종(출석 자격 뒤 30분 넘게 미지급). days 는 1 · 7 · 30.""")
    @GetMapping("/failures")
    public ApiResponse<FailurePage> failures(@LoginUser Long userId, @RequestParam(defaultValue = "7") int days) {
        adminGuard.require(userId);
        return ApiResponse.ok(query.page(days, Instant.now()));
    }

    @Operation(summary = "실패 상세", description = "입력 · 시트 · 격자 · 결과 · 단계 타임라인 · 재시도 이력. kind = HATCH | CUSTOM")
    @GetMapping("/failures/{kind}/{id}")
    public ApiResponse<FailureDetail> detail(@LoginUser Long userId, @PathVariable String kind, @PathVariable long id) {
        return ApiResponse.ok(details.detail(userId, kind, id, Instant.now()));
    }

    @Operation(summary = "재시도 요청", description = """
            맥미니 러너 몫으로 요청을 연다(과금 없음). 같은 대상에 열린 요청이 있으면 409(ADMIN_RETRY_OPEN). 봇 토큰은 403.""")
    @PostMapping("/failures/{kind}/{id}/retry")
    public ApiResponse<RetryView> retry(@LoginUser Long userId, @PathVariable String kind, @PathVariable long id,
                                        @Valid @RequestBody(required = false) RetryMemoRequest body) {
        return ApiResponse.ok(retryService.request(userId, kind, id, body == null ? null : body.memo(), Instant.now()));
    }

    @Operation(summary = "재시도 취소", description = "열린 요청만. 닫힌 요청이면 409(ADMIN_RETRY_CLOSED). 봇 토큰은 403.")
    @PostMapping("/retry/{id}/cancel")
    public ApiResponse<RetryView> cancel(@LoginUser Long userId, @PathVariable long id) {
        return ApiResponse.ok(retryService.cancel(userId, id, Instant.now()));
    }

    @Operation(summary = "러너 대기 목록", description = "안 집힌 요청과 재료. 부를 때마다 러너 확인 시각을 남긴다.")
    @GetMapping("/retry/pending")
    public ApiResponse<List<PendingItem>> pending(@LoginUser Long userId) {
        return ApiResponse.ok(retryService.pending(userId, Instant.now()));
    }

    @Operation(summary = "러너가 집음", description = "집은 시각을 남기고 재료를 준다. 이미 집혔거나 닫혔으면 409.")
    @PostMapping("/retry/{id}/claim")
    public ApiResponse<PendingItem> claim(@LoginUser Long userId, @PathVariable long id) {
        return ApiResponse.ok(retryService.claim(userId, id, Instant.now()));
    }

    @Operation(summary = "러너 결과 등록", description = """
            부화: gridKeys(0개면 후보 없음) · sheetKey · identityText. 맞춤: files(논리 경로 → 업로드 키).
            취소됐거나 결과가 이미 있으면 409.""")
    @PostMapping("/retry/{id}/candidates")
    public ApiResponse<CandidatesResult> candidates(@LoginUser Long userId, @PathVariable long id,
                                                    @Valid @RequestBody RetryCandidatesRequest body) {
        adminGuard.require(userId);
        Instant now = Instant.now();
        AdminRetry r = row(id);
        return ApiResponse.ok(r.getKind() == AdminRetryKind.HATCH
                ? hatchApplier.hatchCandidates(userId, id, body, now)
                : customApplier.customCandidates(userId, id, body, now));
    }

    @Operation(summary = "후보 반영", description = """
            부화: choice = 후보 id → 기존 1층 pick 으로 살린다. 맞춤: 8종을 게시하고 지급한다. 봇 토큰은 403.""")
    @PostMapping("/retry/{id}/pick")
    public ApiResponse<RetryView> pick(@LoginUser Long userId, @PathVariable long id,
                                       @Valid @RequestBody(required = false) RetryPickRequest body) {
        caller.requireHuman();
        adminGuard.require(userId);
        Instant now = Instant.now();
        AdminRetry r = row(id);
        if (r.getKind() == AdminRetryKind.HATCH) {
            hatchApplier.hatchPick(userId, id, body == null ? null : body.choice(), now);
        } else {
            customApplier.customPick(userId, id, now);
        }
        AdminRetry after = row(id);
        return ApiResponse.ok(new RetryView(after.getId(), after.getKind().name(), after.getTargetId(), after.isAuto(),
                after.getMemo(), after.getRequestedAt(), after.getPickedAt(),
                after.getResult() == null ? null : after.getResult().name()));
    }

    @Operation(summary = "러너 마지막 확인", description = "10분 넘게 안 보이면 stale=true.")
    @GetMapping("/runner")
    public ApiResponse<RetryRunnerView> runner(@LoginUser Long userId) {
        adminGuard.require(userId);
        return ApiResponse.ok(runnerView(retryService.runnerLastSeen().orElse(null), Instant.now()));
    }

    static RetryRunnerView runnerView(Instant lastSeen, Instant now) {
        if (lastSeen == null) {
            return new RetryRunnerView(null, null, true);
        }
        Duration ago = Duration.between(lastSeen, now);
        return new RetryRunnerView(lastSeen, Math.max(0, ago.toMinutes()), ago.compareTo(RUNNER_STALE) > 0);
    }

    private AdminRetry row(long id) {
        return retries.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "재시도 요청이 없습니다: " + id));
    }
}
