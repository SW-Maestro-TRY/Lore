package com.lore.zzal.admin.failures;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 관리자 실패 목록 응답 모음. 설계 spec-zzal-admin-failures-retry-20261027 2절.
 *
 * ★ 이메일은 넣지 않는다 — 사용자는 id 로만 보인다.
 */
public final class AdminFailureResponses {

    private AdminFailureResponses() {
    }

    /**
     * 실패 한 줄.
     *
     * @param kind          HATCH | CUSTOM
     * @param id            HATCH = 펫 id, CUSTOM = 맞춤 잡 id(잡이 없으면 0)
     * @param failedAt      부화 FAILED = 마지막 실패 잡 끝난 시각, 멈춘 알 = 마지막 잡 활동 시각,
     *                      맞춤 = 처음 받았어야 할 방문 시각(자격 시각)
     * @param reasonCode    부화 = 잡 error_code 또는 STUCK, 맞춤 = 잡 상태에서 읽은 이유 코드
     * @param stage         부화 = 실패한 단계 이름, 맞춤 = 잡 state(잡 없으면 null)
     * @param reasonText    사람 말 한 줄({@link #reasonText(String)})
     * @param costUsd       부화 = HATCH 잡 비용 합, 맞춤 = null(v1, 원천 미확정)
     * @param autoCli       NONE | BAKING | RECOVERED | FAILED — 자동 CLI 요청의 최신 상태
     * @param request       NONE | WAITING | BAKING | CANDIDATES | NO_CANDIDATES | CANCELLED | APPLIED | APPLY_FAILED
     *                      — 수동 요청의 최신 상태
     * @param openRetryId   열린 요청(수동·자동 무관)의 id
     * @param revivable     재시도를 받을 수 있는가
     * @param blockedReason 못 받으면 그 이유
     */
    public record FailureRow(String kind, long id, long petId, String petName, long userId,
                             Instant failedAt, String reasonCode, String stage, String reasonText,
                             int attempts, BigDecimal costUsd, String autoCli,
                             String request,
                             Long openRetryId, boolean revivable, String blockedReason) {
    }

    public record FailurePage(Instant from, Instant to, long hatchSucceeded, long hatchFailed,
                              long customGranted, long customFailed,
                              List<HourBucket> hours, List<FailureRow> rows) {
    }

    /** 한 시간 칸 — 그 시간에 실패한 수(failedAt 기준). */
    public record HourBucket(Instant hour, int hatch, int custom) {
    }

    // ── 상세 ──────────────────────────────────────────────────────────────

    /**
     * 그림 재료.
     *
     * @param gridKeys         부화 = 성공한 격자 단계의 키(grid · grid2), 맞춤 = custom/{requestId}/grid.png
     * @param rejectedGridKeys 부화 = 보존함(rejected/)의 격자, 맞춤 = 빈 목록
     * @param resultKeys       부화 = 기본 16종(key → webp), 맞춤 = 자산(motion_order → asset_key)
     */
    public record Images(String inputKey, String sheetKey, List<String> gridKeys, List<String> rejectedGridKeys,
                         Map<String, String> resultKeys, String identityText) {
    }

    /** 단계 하나. 맞춤은 잡 칸에서 읽은 고정 4단(접수 · 계획 · 그림 · 후처리·게시)이라 attempt·model·비용이 비어 있다. */
    public record Step(long jobId, int attempt, String name, String status, String model,
                       String errorCode, Instant startedAt, Instant finishedAt, Long durationMs, BigDecimal costUsd) {
    }

    public record RetryHistory(long id, boolean auto, String memo, Instant requestedAt, Instant pickedAt,
                               Instant finishedAt, String result, String note, Map<String, Object> candidates,
                               Instant appliedAt, String appliedChoice) {
    }

    /**
     * @param row             지금 실패 목록에 들어 있으면 그 줄, 이미 복구됐으면 null
     * @param errorDetail     부화 = 마지막 실패 단계의 출력 문장, 맞춤 = 잡 error_code 의 사람 말
     * @param identityMissing 정체성 문단이 없거나 비었다(러너가 문단부터 만들어야 한다)
     */
    public record FailureDetail(FailureRow row, Images images, List<Step> steps, List<RetryHistory> retries,
                                String errorDetail, boolean identityMissing) {
    }

    // ── 이유 코드 → 사람 말 ─────────────────────────────────────────────────

    private static final Map<String, String> REASONS = Map.ofEntries(
            // 부화
            Map.entry("TIMEOUT", "생성이 시간 안에 끝나지 않음"),
            Map.entry("MODERATION_BLOCKED", "그림 안전 검사에 걸림"),
            Map.entry("UNKNOWN", "알 수 없는 오류로 실패"),
            Map.entry("STUCK", "부화 중 멈춤 — 재시도하면 성공한 단계를 이어받아 서버에서 이어서 굽기(예상 비용 표시)"),
            // 맞춤
            Map.entry("NO_JOB", "출석 보상 자격은 생겼는데 맞춤 잡이 없음"),
            Map.entry("NOT_DISPATCHED", "잡이 만들어졌지만 아직 굽기로 보내지 않음"),
            Map.entry("PLANNING_STUCK", "동작 계획을 짜다 멈춤"),
            Map.entry("DISPATCH_STUCK", "굽기로 보냈지만 집히지 않음"),
            Map.entry("CLAIMED_ORPHAN", "굽기가 집은 뒤 결과 없이 끊김(고아)"),
            Map.entry("ASSET_CHECK_FAILED", "만든 움짤이 자동 검사를 통과하지 못함"),
            Map.entry("NOT_PUBLISHED", "다 만들었지만 게시되지 않음")
    );

    public static String reasonText(String code) {
        if (code == null) {
            return "이유 기록 없음";
        }
        String text = REASONS.get(code);
        if (text != null) {
            return text;
        }
        if (code.startsWith("CUSTOM_POSTPROCESS")) {
            return "후처리 단계에서 실패(" + code + ")";
        }
        return "실패(" + code + ")";
    }
}
