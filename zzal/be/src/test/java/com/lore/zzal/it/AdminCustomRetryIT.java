package com.lore.zzal.it;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.s3.S3Storage;
import com.lore.zzal.admin.AdminBotToken;
import com.lore.zzal.admin.failures.AdminFailureQuery;
import com.lore.zzal.admin.retry.AdminCustomRetryApplier;
import com.lore.zzal.admin.retry.AdminHatchRetryApplier.RetryCandidatesRequest;
import com.lore.zzal.admin.retry.AdminRetry;
import com.lore.zzal.admin.retry.AdminRetryRepository;
import com.lore.zzal.admin.retry.AdminRetryResult;
import com.lore.zzal.admin.retry.AdminRetryService;
import com.lore.zzal.attendance.AttendanceService;
import com.lore.zzal.attendance.CustomBundlePublicationService;
import com.lore.zzal.attendance.CustomGridPrompt;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/**
 * 맞춤 재시도 반영(작업 1.7) — 진짜 DB · 메모리 S3 로.
 *
 * <h3>★ 게시 검증은 대역이다</h3>
 * {@link CustomBundlePublicationService#publish} 는 계획 · 앵커 · 파이썬 캔버스 검사까지 하는 무거운 검증이라 시험 자료로
 * 통과시키기 어렵다(같은 검증을 쓰는 CustomPublicationClosureTest 는 main 에서도 시험 환경 후처리기 탓에 실패한다).
 * 여기서는 그 앞뒤 — 파일 세트 검사 · 백업 · 복사 · DB 쓰기 · 되돌리기 — 를 진짜로 보고, 게시는
 * "8개 published=true + 진짜 grantReady" 로 흉내 내거나 예외를 던지게 한다. 진짜 검증은 dev 에서 잡 1개로 끝까지 돌려 본다.
 */
@ZzalIntegrationTest
@DisplayName("관리자 맞춤 재시도 반영")
class AdminCustomRetryIT extends ZzalItSupport {

    @MockitoBean CustomBundlePublicationService publication;
    @Autowired AdminCustomRetryApplier applier;
    @Autowired AdminRetryService retryService;
    @Autowired AdminRetryRepository retries;
    @Autowired AttendanceService attendance;
    @Autowired S3Storage storage;
    @Autowired ZzalItConfig.RecordingCdnInvalidator cdn;

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final String PLAN = "{\"motionPlan\":{\"version\":\"v1\"}}";
    private Long admin;

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private Instant ago(int minutes) {
        return now.minusSeconds(minutes * 60L);
    }

    private void setUpAdmin() {
        // ★ 메모리 S3 는 시험 사이에 안 비워지고, 표는 번호까지 처음으로 돌아간다 — 같은 잡 번호 · 같은 requestId 가
        //   앞 시험의 객체를 보게 된다. 그래서 여기서 비운다.
        ((ZzalItConfig.InMemoryS3Storage) storage).clear();
        cdn.paths.clear();
        admin = newUserId();
        jdbc.update("update users set role = 'ADMIN' where id = ?", admin);
    }

    /** 3일차 출석했는데 못 받은 펫 + 그 잡. */
    private long[] orphan(String state, String error, String plan) {
        ZzalPet pet = aliveLayerTwoPet(newUserId());
        for (int i = 1; i <= 3; i++) {
            jdbc.update("""
                    insert into zzal_awake_visit (pet_id, program_version, cycle_key, event_id, observed_at)
                    values (?, 'first-v1', ?, ?, ?)
                    """, pet.getId(), "c" + i, "ev-" + pet.getId() + "-" + i,
                    Timestamp.from(ago(120).minus(Duration.ofDays(3 - i))));
        }
        for (int order = 1; order <= 8; order++) {
            jdbc.update("""
                    insert into zzal_attendance_reward_slot (pet_id, program_version, slot_key, eligible_day)
                    values (?, 'first-v1', ?, 3)
                    """, pet.getId(), "motion-" + order);
        }
        Long job = jdbc.queryForObject("""
                insert into zzal_custom_bundle_job (pet_id, program_version, bundle_version, snapshot_json, input_hash,
                                                    state, error_code, claim_token, source_plan_json, source_plan_hash,
                                                    created_at)
                values (?, 'first-v1', 'bundle-1', '{}', 'h', ?, ?, ?, ?, ?, ?) returning id
                """, Long.class, pet.getId(), state, error, "CLAIMED".equals(state) ? "tok-1" : null, plan,
                plan == null ? null : AdminFailureQuery.sha256(plan), Timestamp.from(ago(60 * 24)));
        return new long[]{pet.getId(), job};
    }

    private long claimed(long job) {
        var v = retryService.request(admin, "CUSTOM", job, "맞춤 다시", now);
        retryService.claim(admin, v.id(), now.plusSeconds(1));
        return v.id();
    }

    /** presign 으로 올린 것처럼 — 관리자 티켓 + 메모리 S3 에 바이트. */
    private String upload(String body) throws Exception {
        String key = newUploadedImageKey(admin);
        put(key, body);
        return key;
    }

    private void put(String key, String body) throws Exception {
        Path tmp = Files.createTempFile("admin-custom-it-", ".bin");
        Files.writeString(tmp, body);
        storage.upload(key, tmp, "application/octet-stream");
        Files.deleteIfExists(tmp);
    }

    private String get(String key) throws Exception {
        Path tmp = Files.createTempFile("admin-custom-it-", ".bin");
        storage.download(key, tmp);
        String s = Files.readString(tmp);
        Files.deleteIfExists(tmp);
        return s;
    }

    private static String requestId(long job, String plan) {
        return AdminFailureQuery.customRequestId(job, plan);
    }

    /** 러너가 올릴 파일 세트 — manifest 가 이 잡의 requestId · 자산 키 · 해시를 정확히 말한다. */
    private Map<String, String> files(long job, String plan, String manifestRequestId, boolean withPlan) throws Exception {
        String rid = requestId(job, plan);
        Map<String, String> files = new LinkedHashMap<>();
        List<Map<String, Object>> assets = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String slot = CustomGridPrompt.SLOTS.get(i);
            String body = "webp-" + slot;
            files.put(slot + ".webp", upload(body));
            assets.add(Map.of("motionOrder", i + 1, "assetKey", "images/zzal/custom/%s/%s.webp".formatted(rid, slot),
                    "sha256", AdminFailureQuery.sha256(body), "humanReview", "UNREVIEWED", "published", false));
        }
        String mrid = manifestRequestId != null ? manifestRequestId : rid;
        String manifest = json.writeValueAsString(Map.of("requestId", mrid, "executionId", mrid, "assets", assets,
                "usedSources", List.of()));
        files.put("manifest.json", upload(manifest));
        files.put("resource-metadata.json", upload("{\"setId\":\"" + rid + "\"}"));
        files.put("anchors.json", upload("{}"));
        files.put("grid.png", upload("grid"));
        if (withPlan) {
            files.put("source-plan.json", upload(plan));
        }
        return files;
    }

    private void publishSucceeds() throws Exception {
        doAnswer(inv -> {
            long jobId = inv.getArgument(1);
            Instant at = inv.getArgument(2);
            jdbc.update("update zzal_custom_bundle_asset set published = true where job_id = ?", jobId);
            Long pet = jdbc.queryForObject("select pet_id from zzal_custom_bundle_job where id = ?", Long.class, jobId);
            attendance.grantReady(pet, at);
            return 8;
        }).when(publication).publish(anyLong(), anyLong(), any());
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).getErrorCode();
    }

    @Test
    @DisplayName("러너 결과 — 경로에 .. 가 있으면 400, 행은 그대로")
    void customCandidates_rejectsPathTraversal_400() throws Exception {
        setUpAdmin();
        long[] o = orphan("CLAIMED", null, PLAN);
        long id = claimed(o[1]);
        Map<String, String> files = files(o[1], PLAN, null, false);
        files.put("../x.webp", upload("x"));

        assertThatThrownBy(() -> applier.customCandidates(admin, id, new RetryCandidatesRequest(null, null, null, files, null), now))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.INVALID_INPUT));
        AdminRetry row = retries.findById(id).orElseThrow();
        assertThat(row.getResult()).isNull();
        assertThat(row.getPickedAt()).isNotNull();
    }

    @Test
    @DisplayName("러너 결과 — manifest 의 requestId 가 이 잡과 다르면 NONE(사유 기록)")
    void customCandidates_requestIdMismatch_resultNone() throws Exception {
        setUpAdmin();
        long[] o = orphan("CLAIMED", null, PLAN);
        long id = claimed(o[1]);
        var result = applier.customCandidates(admin, id,
                new RetryCandidatesRequest(null, null, null, files(o[1], PLAN, "00000000-0000-0000-0000-000000000000", false), null),
                now.plusSeconds(2));
        assertThat(result.result()).isEqualTo("NONE");
        AdminRetry row = retries.findById(id).orElseThrow();
        assertThat(row.getResult()).isEqualTo(AdminRetryResult.NONE);
        assertThat(row.getNote()).startsWith("manifest 불일치: requestId");
    }

    @Test
    @DisplayName("고르기 — 봇 토큰이면 403, 잡은 그대로")
    void customPick_byBot_403() throws Exception {
        setUpAdmin();
        long[] o = orphan("CLAIMED", null, PLAN);
        long id = claimed(o[1]);
        applier.customCandidates(admin, id, new RetryCandidatesRequest(null, null, null, files(o[1], PLAN, null, false), null), now);

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(admin, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority(AdminBotToken.BOT_AUTHORITY))));
        assertThatThrownBy(() -> applier.customPick(admin, id, now))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(jdbc.queryForObject("select state from zzal_custom_bundle_job where id = ?", String.class, o[1]))
                .isEqualTo("CLAIMED");
    }

    @Test
    @DisplayName("★ 고르기 — CLAIMED 고아 잡을 러너 세트로: 잡 GENERATED · 자산 8 게시 · 슬롯 GRANTED · 행 APPLIED")
    void customPick_claimedOrphan_grantsEligibleSlot() throws Exception {
        setUpAdmin();
        publishSucceeds();
        long[] o = orphan("CLAIMED", null, PLAN);
        long id = claimed(o[1]);
        var cand = applier.customCandidates(admin, id,
                new RetryCandidatesRequest(null, null, null, files(o[1], PLAN, null, false), "Codex"), now.plusSeconds(2));
        assertThat(cand.result()).isEqualTo("CANDIDATES");

        applier.customPick(admin, id, now.plusSeconds(5));

        String rid = requestId(o[1], PLAN);
        var job = jdbc.queryForMap("select * from zzal_custom_bundle_job where id = ?", o[1]);
        assertThat(job.get("state")).isEqualTo("GENERATED");
        assertThat(job.get("execution_id")).isEqualTo(rid);
        assertThat(job.get("manifest_hash")).isNotNull();
        assertThat(job.get("error_code")).isNull();
        assertThat(job.get("claim_token")).isEqualTo("tok-1");
        assertThat(jdbc.queryForObject("select count(*) from zzal_custom_bundle_asset where job_id = ? and published "
                + "and auto_check = 'PASS' and human_review = 'APPROVED'", Integer.class, o[1])).isEqualTo(8);
        assertThat(jdbc.queryForObject("select count(*) from zzal_attendance_reward_slot where pet_id = ? and state = 'GRANTED'",
                Integer.class, o[0])).isEqualTo(8);
        assertThat(get("images/zzal/custom/%s/%s.webp".formatted(rid, CustomGridPrompt.SLOTS.getFirst())))
                .isEqualTo("webp-" + CustomGridPrompt.SLOTS.getFirst());
        AdminRetry row = retries.findById(id).orElseThrow();
        assertThat(row.getResult()).isEqualTo(AdminRetryResult.APPLIED);
        assertThat(row.getAppliedBy()).isEqualTo(admin);
        assertThat(row.getAppliedChoice()).isEqualTo("custom");
        // ★ 같은 키를 덮어썼다 — CDN 이 옛 그림을 계속 주지 않게 그 자리를 무효화한다(수정 3)
        assertThat(cdn.paths).contains("/images/zzal/custom/" + rid + "/*");
    }

    @Test
    @DisplayName("★ 고르기 — 게시 검증이 실패하면 전부 되돌린다(잡 칸 · 자산 · 슬롯 · S3 백업) · 행 APPLY_FAILED")
    void customPick_publishFails_rollsBackEverything() throws Exception {
        setUpAdmin();
        doThrow(new IllegalStateException("Sidecar hash mismatch")).when(publication).publish(anyLong(), anyLong(), any());
        long[] o = orphan("CLAIMED", null, PLAN);
        String rid = requestId(o[1], PLAN);
        put("images/zzal/custom/" + rid + "/grid.png", "old-grid"); // 원래 서버 실행이 남긴 격자(덮어써질 자리)
        long id = claimed(o[1]);
        applier.customCandidates(admin, id, new RetryCandidatesRequest(null, null, null, files(o[1], PLAN, null, false), null), now);
        var before = jdbc.queryForMap("select state, error_code, claim_token, execution_id, manifest_hash "
                + "from zzal_custom_bundle_job where id = ?", o[1]);

        assertThatThrownBy(() -> applier.customPick(admin, id, now.plusSeconds(5)))
                .isInstanceOf(BusinessException.class);

        assertThat(jdbc.queryForMap("select state, error_code, claim_token, execution_id, manifest_hash "
                + "from zzal_custom_bundle_job where id = ?", o[1])).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from zzal_custom_bundle_asset where job_id = ?", Integer.class, o[1]))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from zzal_attendance_reward_slot where pet_id = ? and state = 'ELIGIBLE'",
                Integer.class, o[0])).isEqualTo(8);
        assertThat(get("images/zzal/custom/" + rid + "/grid.png")).as("백업 복원").isEqualTo("old-grid");
        assertThat(storage.exists("images/zzal/custom/%s/%s.webp".formatted(rid, CustomGridPrompt.SLOTS.getFirst())))
                .as("새로 생긴 객체는 지운다").isFalse();
        AdminRetry row = retries.findById(id).orElseThrow();
        assertThat(row.getResult()).isEqualTo(AdminRetryResult.APPLY_FAILED);
        assertThat(row.getNote()).contains("Sidecar hash mismatch");
    }

    @Test
    @DisplayName("고르기 — 그 잡 자산으로 이미 지급된 슬롯이 있으면 409, 아무것도 안 바뀐다")
    void customPick_refusesWhenSlotAlreadyGranted_409() throws Exception {
        setUpAdmin();
        publishSucceeds();
        long[] o = orphan("FAILED", "ASSET_CHECK_FAILED", PLAN);
        Long asset = jdbc.queryForObject("""
                insert into zzal_custom_bundle_asset (job_id, motion_order, asset_key, asset_hash, auto_check, human_review, published)
                values (?, 1, 'images/zzal/custom/old/1.webp', 'x', 'PASS', 'UNREVIEWED', true) returning id
                """, Long.class, o[1]);
        long id = claimed(o[1]);
        applier.customCandidates(admin, id, new RetryCandidatesRequest(null, null, null, files(o[1], PLAN, null, false), null), now);
        jdbc.update("update zzal_attendance_reward_slot set state = 'GRANTED', asset_id = ?, asset_key = 'k', granted_at = now() "
                + "where pet_id = ? and slot_key = 'motion-1'", asset, o[0]);

        assertThatThrownBy(() -> applier.customPick(admin, id, now.plusSeconds(5)))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.ADMIN_RETRY_ALREADY_GRANTED));
        assertThat(jdbc.queryForObject("select state from zzal_custom_bundle_job where id = ?", String.class, o[1]))
                .isEqualTo("FAILED");
        assertThat(retries.findById(id).orElseThrow().getResult()).isEqualTo(AdminRetryResult.CANDIDATES);
    }

    @Test
    @DisplayName("결정 5 — 계획 없는 잡은 러너 계획을 반영 때 써 넣고, 계획 있는 잡의 계획 교체는 400")
    void customPick_planlessJobTakesRunnerPlan_butNoReplacement() throws Exception {
        setUpAdmin();
        publishSucceeds();
        long[] planless = orphan("PENDING", "PLANNING_IN_PROGRESS", null);
        long id = claimed(planless[1]);
        applier.customCandidates(admin, id,
                new RetryCandidatesRequest(null, null, null, files(planless[1], PLAN, null, true), null), now);
        applier.customPick(admin, id, now.plusSeconds(5));
        var job = jdbc.queryForMap("select state, source_plan_json, source_plan_hash from zzal_custom_bundle_job where id = ?",
                planless[1]);
        assertThat(job.get("state")).isEqualTo("GENERATED");
        assertThat(job.get("source_plan_json")).isEqualTo(PLAN);
        assertThat(job.get("source_plan_hash")).isEqualTo(AdminFailureQuery.sha256(PLAN));

        long[] planned = orphan("CLAIMED", null, PLAN);
        long id2 = claimed(planned[1]);
        String other = "{\"motionPlan\":{\"version\":\"v2\"}}";
        Map<String, String> files = files(planned[1], PLAN, null, false);
        files.put("source-plan.json", upload(other));
        assertThatThrownBy(() -> applier.customCandidates(admin, id2, new RetryCandidatesRequest(null, null, null, files, null), now))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.INVALID_INPUT));
        assertThat(new String(other.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)).isNotEqualTo(PLAN);
    }

    @Test
    @DisplayName("러너 결과 — 그 사이 잡이 반영할 수 없는 상태가 됐으면 400, 행은 그대로")
    void customCandidates_rejectsInapplicableJobState() throws Exception {
        setUpAdmin();
        long[] o = orphan("CLAIMED", null, PLAN);
        long id = claimed(o[1]);
        jdbc.update("update zzal_custom_bundle_job set state = 'GENERATED', manifest_hash = 'x' where id = ?", o[1]);
        assertThatThrownBy(() -> applier.customCandidates(admin, id,
                new RetryCandidatesRequest(null, null, null, files(o[1], PLAN, null, false), null), now))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.INVALID_INPUT));
        assertThat(retries.findById(id).orElseThrow().getResult()).isNull();
    }

    @Test
    @DisplayName("★ 고르기 동시 두 번 — 두 번째는 409, S3 와 행 상태를 안 바꾼다(첫 번째가 끝까지 반영)")
    void customPick_concurrentSecond_409_noSideEffects() throws Exception {
        setUpAdmin();
        var inPublish = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        doAnswer(inv -> {
            inPublish.countDown();
            release.await(30, java.util.concurrent.TimeUnit.SECONDS);
            long jobId = inv.getArgument(1);
            jdbc.update("update zzal_custom_bundle_asset set published = true where job_id = ?", jobId);
            Long pet = jdbc.queryForObject("select pet_id from zzal_custom_bundle_job where id = ?", Long.class, jobId);
            attendance.grantReady(pet, inv.getArgument(2));
            return 8;
        }).when(publication).publish(anyLong(), anyLong(), any());
        long[] o = orphan("CLAIMED", null, PLAN);
        long id = claimed(o[1]);
        applier.customCandidates(admin, id, new RetryCandidatesRequest(null, null, null, files(o[1], PLAN, null, false), null), now);

        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var first = pool.submit(() -> { applier.customPick(admin, id, now.plusSeconds(5)); return null; });
            assertThat(inPublish.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var keysBefore = ((ZzalItConfig.InMemoryS3Storage) storage).keys();
            var rowBefore = jdbc.queryForMap("select result, applied_choice, note from zzal_admin_retry where id = ?", id);

            assertThatThrownBy(() -> applier.customPick(admin, id, now.plusSeconds(6)))
                    .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.ADMIN_RETRY_CLOSED));
            assertThatThrownBy(() -> retryService.cancel(admin, id, now.plusSeconds(6)))
                    .as("반영 중에는 취소도 막힌다")
                    .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.ADMIN_RETRY_CLOSED));
            assertThat(((ZzalItConfig.InMemoryS3Storage) storage).keys()).isEqualTo(keysBefore);
            assertThat(jdbc.queryForMap("select result, applied_choice, note from zzal_admin_retry where id = ?", id))
                    .isEqualTo(rowBefore);

            release.countDown();
            first.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(retries.findById(id).orElseThrow().getResult()).isEqualTo(AdminRetryResult.APPLIED);
        assertThat(jdbc.queryForObject("select count(*) from zzal_attendance_reward_slot where pet_id = ? and state = 'GRANTED'",
                Integer.class, o[0])).isEqualTo(8);
    }

    @Test
    @DisplayName("고르기 — 잡이 반영할 수 없는 상태면 S3 를 쓰기 전에 거부(새 객체 없음 · 복원도 안 돎)")
    void customPick_checksStateBeforeS3() throws Exception {
        setUpAdmin();
        publishSucceeds();
        long[] o = orphan("CLAIMED", null, PLAN);
        long id = claimed(o[1]);
        applier.customCandidates(admin, id, new RetryCandidatesRequest(null, null, null, files(o[1], PLAN, null, false), null), now);
        jdbc.update("update zzal_custom_bundle_job set state = 'FAILED', error_code = 'CUSTOM_POSTPROCESS_CRASH' where id = ?", o[1]);
        var keysBefore = ((ZzalItConfig.InMemoryS3Storage) storage).keys();

        assertThatThrownBy(() -> applier.customPick(admin, id, now.plusSeconds(5)))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.INVALID_INPUT));
        assertThat(((ZzalItConfig.InMemoryS3Storage) storage).keys()).isEqualTo(keysBefore);
        assertThat(retries.findById(id).orElseThrow().getResult()).isEqualTo(AdminRetryResult.CANDIDATES);
        assertThat(retries.findById(id).orElseThrow().getAppliedChoice()).isNull();
    }

    @Test
    @DisplayName("고르기 — 후보 등록 뒤 manifest 가 바뀌었으면 거부(등록 때 sha 와 대조)")
    void customPick_manifestChangedAfterCandidates_rejected() throws Exception {
        setUpAdmin();
        publishSucceeds();
        long[] o = orphan("CLAIMED", null, PLAN);
        long id = claimed(o[1]);
        Map<String, String> files = files(o[1], PLAN, null, false);
        applier.customCandidates(admin, id, new RetryCandidatesRequest(null, null, null, files, null), now);
        put(files.get("manifest.json"), "{\"tampered\":true}");

        assertThatThrownBy(() -> applier.customPick(admin, id, now.plusSeconds(5)))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.INVALID_INPUT));
        assertThat(jdbc.queryForObject("select state from zzal_custom_bundle_job where id = ?", String.class, o[1]))
                .isEqualTo("CLAIMED");
    }

    @Test
    @DisplayName("★ PENDING 잡에 러너 요청이 열리면 claim_token 에 표식(디스패처가 건너뜀, state 는 그대로) — 취소 · 결과 없음이면 되돌림")
    void pendingJob_markedWhileRequestOpen_releasedOnClose() throws Exception {
        setUpAdmin();
        long[] o = orphan("PENDING", "PLANNING_IN_PROGRESS", null);
        var v = retryService.request(admin, "CUSTOM", o[1], null, now);
        var job = jdbc.queryForMap("select state, claim_token from zzal_custom_bundle_job where id = ?", o[1]);
        assertThat(job.get("state")).isEqualTo("PENDING");
        assertThat(job.get("claim_token")).isEqualTo("admin-retry-" + v.id());
        // 디스패처가 집는 조건(CustomBundleDispatcher: state=PENDING AND claim_token IS NULL AND error_code IS NULL)에서 빠진다
        assertThat(jdbc.queryForObject("select count(*) from zzal_custom_bundle_job where id = ? and state = 'PENDING' "
                + "and claim_token is null", Integer.class, o[1])).isZero();
        // 목록 사유는 그대로 계획 중 멈춤(표식을 디스패치로 읽지 않는다)
        assertThat(retryService.pending(admin, now).getFirst().custom().state()).isEqualTo("PENDING");

        retryService.cancel(admin, v.id(), now.plusSeconds(1));
        assertThat(jdbc.queryForObject("select claim_token from zzal_custom_bundle_job where id = ?", String.class, o[1])).isNull();

        // 다시 요청 → 러너가 결과 없음(NONE) → 되돌림
        long id = claimed(o[1]);
        assertThat(jdbc.queryForObject("select claim_token from zzal_custom_bundle_job where id = ?", String.class, o[1]))
                .isEqualTo("admin-retry-" + id);
        applier.customCandidates(admin, id, new RetryCandidatesRequest(null, null, null, Map.of(), "못 만듦"), now.plusSeconds(3));
        assertThat(jdbc.queryForObject("select claim_token from zzal_custom_bundle_job where id = ?", String.class, o[1])).isNull();
    }

    // ── 수정 5: 계획 없는 잡은 재료 장부(reserve)를 거쳐 계획을 써 넣는다 ─────────────────

    private static final String MEMORY_SNAPSHOT = "{\"materialPolicy\":\"CUSTOM_PERSONA_IDENTITY_LONG_TERM_V2\","
            + "\"memory\":{\"candidates\":[{\"id\":5,\"revision\":1,\"subject\":\"CHARACTER\",\"status\":\"ACTIVE\","
            + "\"sourceTurnId\":10,\"sourceQuote\":\"좋아하는 공\"}]}}";

    private String memoryPlan() {
        String provenance = AdminFailureQuery.sha256("10|좋아하는 공");
        return "{\"version\":1,\"selectedSources\":[{\"factId\":5,\"revision\":1,\"provenanceKey\":\"" + provenance
                + "\"}],\"motionPlan\":{\"version\":\"v1\"}}";
    }

    /** files() 와 같되 manifest 에 usedSources 를 싣는다. */
    private Map<String, String> filesWithUsed(long job, String plan) throws Exception {
        Map<String, String> files = files(job, plan, null, true);
        String rid = requestId(job, plan);
        List<Map<String, Object>> assets = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String slot = CustomGridPrompt.SLOTS.get(i);
            assets.add(Map.of("motionOrder", i + 1, "assetKey", "images/zzal/custom/%s/%s.webp".formatted(rid, slot),
                    "sha256", AdminFailureQuery.sha256("webp-" + slot), "humanReview", "UNREVIEWED", "published", false));
        }
        files.put("manifest.json", upload(json.writeValueAsString(Map.of("requestId", rid, "executionId", rid,
                "assets", assets, "usedSources", List.of(Map.of("factId", 5, "revision", 1))))));
        return files;
    }

    @Test
    @DisplayName("★ 계획 없는 잡 — 반영 때 재료를 예약(reserve)하고 러너 계획 원문을 그대로 쓰며, usedSources 는 USED 로")
    void planlessJob_reservesSourcesAndMarksUsed() throws Exception {
        setUpAdmin();
        publishSucceeds();
        long[] o = orphan("PENDING", "PLANNING_IN_PROGRESS", null);
        jdbc.update("update zzal_custom_bundle_job set snapshot_json = ? where id = ?", MEMORY_SNAPSHOT, o[1]);
        String plan = memoryPlan();
        long id = claimed(o[1]);
        var cand = applier.customCandidates(admin, id, new RetryCandidatesRequest(null, null, null, filesWithUsed(o[1], plan), null), now);
        assertThat(cand.result()).isEqualTo("CANDIDATES");

        applier.customPick(admin, id, now.plusSeconds(5));
        var job = jdbc.queryForMap("select state, source_plan_json, source_plan_hash from zzal_custom_bundle_job where id = ?", o[1]);
        assertThat(job.get("state")).isEqualTo("GENERATED");
        assertThat(job.get("source_plan_json")).isEqualTo(plan);
        assertThat(job.get("source_plan_hash")).isEqualTo(AdminFailureQuery.sha256(plan));
        assertThat(jdbc.queryForList("select state from zzal_custom_bundle_source where job_id = ?", String.class, o[1]))
                .containsExactly("USED");
    }

    @Test
    @DisplayName("계획 없는 잡 — 게시가 실패하면 예약한 재료 행과 계획을 지운다(잡은 원래대로)")
    void planlessJob_publishFails_releasesReservation() throws Exception {
        setUpAdmin();
        doThrow(new IllegalStateException("Material receipt mismatch")).when(publication).publish(anyLong(), anyLong(), any());
        long[] o = orphan("PENDING", "PLANNING_IN_PROGRESS", null);
        jdbc.update("update zzal_custom_bundle_job set snapshot_json = ? where id = ?", MEMORY_SNAPSHOT, o[1]);
        String plan = memoryPlan();
        long id = claimed(o[1]);
        applier.customCandidates(admin, id, new RetryCandidatesRequest(null, null, null, filesWithUsed(o[1], plan), null), now);

        assertThatThrownBy(() -> applier.customPick(admin, id, now.plusSeconds(5))).isInstanceOf(BusinessException.class);
        var job = jdbc.queryForMap("select state, error_code, source_plan_json from zzal_custom_bundle_job where id = ?", o[1]);
        assertThat(job.get("state")).isEqualTo("PENDING");
        assertThat(job.get("error_code")).isEqualTo("PLANNING_IN_PROGRESS");
        assertThat(job.get("source_plan_json")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from zzal_custom_bundle_source where job_id = ?", Integer.class, o[1]))
                .isZero();
    }

    @Test
    @DisplayName("러너 결과 — 검수 묶음의 manifest(review/sequence-v1/manifest.json)도 선택 파일로 받고, 그 밖의 review 경로는 400")
    void customCandidates_acceptsReviewSequenceManifest() throws Exception {
        setUpAdmin();
        long[] o = orphan("CLAIMED", null, PLAN);
        long id = claimed(o[1]);
        Map<String, String> bad = files(o[1], PLAN, null, false);
        bad.put("review/sequence-v1/notes.json", upload("{}"));
        assertThatThrownBy(() -> applier.customCandidates(admin, id, new RetryCandidatesRequest(null, null, null, bad, null), now))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.INVALID_INPUT));

        Map<String, String> ok = files(o[1], PLAN, null, false);
        ok.put("review/sequence-v1/manifest.json", upload("{\"frames\":2}"));
        ok.put("review/sequence-v1/frame-01.png", upload("f1"));
        var result = applier.customCandidates(admin, id, new RetryCandidatesRequest(null, null, null, ok, null), now);
        assertThat(result.result()).isEqualTo("CANDIDATES");
    }
}
