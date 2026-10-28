package com.lore.zzal.it;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.admin.failures.AdminFailureDetailService;
import com.lore.zzal.admin.failures.AdminFailureQuery;
import com.lore.zzal.admin.failures.AdminFailureResponses.FailureDetail;
import com.lore.zzal.admin.failures.AdminFailureResponses.FailurePage;
import com.lore.zzal.admin.failures.AdminFailureResponses.FailureRow;
import com.lore.zzal.admin.AdminBotToken;
import com.lore.zzal.admin.retry.AdminHatchRetryApplier;
import com.lore.zzal.admin.retry.AdminHatchRetryApplier.RetryCandidatesRequest;
import com.lore.zzal.admin.retry.AdminRetry;
import com.lore.zzal.admin.retry.AdminRetryKind;
import com.lore.zzal.admin.retry.AdminRetryRepository;
import com.lore.zzal.admin.retry.AdminRetryResult;
import com.lore.zzal.admin.retry.AdminRetryService;
import com.lore.zzal.admin.retry.AdminRetryService.PendingItem;
import com.lore.zzal.admin.retry.AdminRetryService.RetryView;
import com.lore.zzal.generation.PipelineRegistry;
import com.lore.zzal.generation.GenKind;
import com.lore.zzal.generation.GenerationStep;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 관리자 실패 추적·재시도 — 진짜 DB 로. 설계 spec-zzal-admin-failures-retry-20261027.
 *
 * <h3>작업 1.1 — 요청 표가 스스로 막는 것</h3>
 * 열린 요청(결과 없음 · 후보 도착 후 고르기 전) 중복과 자동 요청 2회는 서비스 코드가 아니라 DB 가 막는다.
 * 서비스가 확인을 빠뜨리거나 두 요청이 동시에 와도 행이 두 개 생기지 않아야 한다.
 */
@ZzalIntegrationTest
@DisplayName("관리자 실패 추적·재시도")
class AdminFailuresIT extends ZzalItSupport {

    @Autowired AdminRetryRepository retries;
    @Autowired AdminFailureQuery failures;
    @Autowired AdminFailureDetailService details;
    @Autowired AdminRetryService retryService;
    @Autowired AdminHatchRetryApplier hatchApplier;
    @Autowired com.lore.zzal.generation.LayerCandidateBaker baker;
    @Autowired com.lore.common.s3.S3Storage storage;
    @Autowired PipelineRegistry registry;

    private Long admin;
    private ZzalPet pet;

    private void setUpPet() {
        admin = newUserId();
        jdbc.update("update users set role = 'ADMIN' where id = ?", admin);
        pet = aliveLayerTwoPet(newUserId());
    }

    @Test
    @DisplayName("같은 대상에 열린 수동 요청은 하나만")
    void retryTable_rejectsSecondOpenRequest() {
        setUpPet();
        Instant now = Instant.now();
        retries.saveAndFlush(AdminRetry.manual(AdminRetryKind.HATCH, pet.getId(), pet.getId(), "첫 요청", admin, now));

        assertThatThrownBy(() -> retries.saveAndFlush(
                AdminRetry.manual(AdminRetryKind.HATCH, pet.getId(), pet.getId(), "둘째 요청", admin, now.plusSeconds(1))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(retries.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("취소하면 같은 대상에 새 요청을 낼 수 있다")
    void retryTable_allowsNewRequestAfterCancel() {
        setUpPet();
        Instant now = Instant.now();
        AdminRetry first = retries.saveAndFlush(
                AdminRetry.manual(AdminRetryKind.HATCH, pet.getId(), pet.getId(), "첫 요청", admin, now));
        first.cancel(now.plusSeconds(5));
        retries.saveAndFlush(first);
        assertThat(retries.findOpen(AdminRetryKind.HATCH, pet.getId())).isEmpty();

        AdminRetry second = retries.saveAndFlush(
                AdminRetry.manual(AdminRetryKind.HATCH, pet.getId(), pet.getId(), "다시", admin, now.plusSeconds(10)));
        assertThat(retries.findOpen(AdminRetryKind.HATCH, pet.getId())).get()
                .extracting(AdminRetry::getId).isEqualTo(second.getId());
        assertThat(retries.findByPetIdOrderByRequestedAtDesc(pet.getId())).hasSize(2);
    }

    @Test
    @DisplayName("자동 요청은 대상당 한 번 — 닫힌 뒤에도 두 번째는 막힌다")
    void retryTable_autoOncePerTarget() {
        setUpPet();
        Instant now = Instant.now();
        AdminRetry auto = retries.saveAndFlush(AdminRetry.auto(AdminRetryKind.HATCH, pet.getId(), pet.getId(), now));
        auto.applyFailed("게이트 미통과", now.plusSeconds(60));
        retries.saveAndFlush(auto);
        assertThat(auto.isOpen()).isFalse();
        assertThat(retries.existsByKindAndTargetIdAndAutoTrue(AdminRetryKind.HATCH, pet.getId())).isTrue();

        assertThatThrownBy(() -> retries.saveAndFlush(
                AdminRetry.auto(AdminRetryKind.HATCH, pet.getId(), pet.getId(), now.plusSeconds(120))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(retries.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("자동 요청에는 요청자가 없다 — requested_by 를 넣으면 CHECK 위반")
    void retryTable_autoHasNoRequester() {
        setUpPet();
        Instant now = Instant.now();
        assertThatThrownBy(() -> jdbc.update("""
                insert into zzal_admin_retry (kind, target_id, pet_id, auto, requested_by, requested_at)
                values ('HATCH', ?, ?, true, ?, now())
                """, pet.getId(), pet.getId(), admin))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 수동 요청인데 요청자가 없는 것도 같은 CHECK 가 막는다.
        assertThatThrownBy(() -> jdbc.update("""
                insert into zzal_admin_retry (kind, target_id, pet_id, auto, requested_by, requested_at)
                values ('CUSTOM', ?, ?, false, null, now())
                """, 1L, pet.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(retries.count()).isZero();

        // 엔티티 생성자는 규칙대로 만든다 — 자동이면 요청자 null.
        AdminRetry saved = retries.saveAndFlush(AdminRetry.auto(AdminRetryKind.CUSTOM, 77L, pet.getId(), now));
        assertThat(saved.getRequestedBy()).isNull();
        assertThat(saved.isAuto()).isTrue();
        assertThat(saved.getResult()).isNull();
        assertThat(retries.findPending()).extracting(AdminRetry::getId).containsExactly(saved.getId());

        // 러너가 집으면 pending 에서 빠지고, 후보가 오면 아직 열린 요청이다.
        AdminRetry locked = transactions.execute(s -> {
            AdminRetry r = retries.findByIdForUpdate(saved.getId()).orElseThrow();
            r.claim(now.plusSeconds(1));
            return r;
        });
        assertThat(locked.getPickedAt()).isNotNull();
        assertThat(retries.findPending()).isEmpty();
        transactions.executeWithoutResult(s -> retries.findByIdForUpdate(saved.getId()).orElseThrow()
                .deliver(AdminRetryResult.CANDIDATES, "후보 1", java.util.Map.of("keys", java.util.List.of("a.webp")),
                        now.plusSeconds(2)));
        AdminRetry delivered = retries.findById(saved.getId()).orElseThrow();
        assertThat(delivered.isOpen()).isTrue();
        assertThat(delivered.getCandidateKeys()).containsKey("keys");
    }

    // ── 작업 1.3: 실패 목록 ─────────────────────────────────────────────────
    //
    // ★ 시각은 고정 now 하나에서 거꾸로 잡는다. 유예(부화 12분 · 맞춤 30분) 경계를 1~2분 차로 양쪽에서 본다.

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private final Instant weekAgo = now.minus(Duration.ofDays(7));

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private Instant ago(int minutes) {
        return now.minusSeconds(minutes * 60L);
    }

    /** 알 한 마리 — phase·이름을 바로 앉힌다(부화 파이프라인은 이 시험이 보려는 것이 아니다). */
    private Long egg(String phase, String name) {
        Long owner = newUserId();
        ZzalPet pet = petRepository.save(ZzalPet.draft(owner, newUploadedImageKey(owner), ago(60)));
        jdbc.update("update zzal_pet set phase = ?, name = ?, hatch_started_at = ? where id = ?",
                phase, name == null ? "" : name, ts(ago(60)), pet.getId()); // 이름 전이면 빈 문자열(칸은 NOT NULL)
        return pet.getId();
    }

    private String version() {
        return hatchService.currentVersion();
    }

    private Long hatchJob(Long petId, int attempt, String status, String error,
                          Instant started, Instant running, Instant finished) {
        return jdbc.queryForObject("""
                insert into zzal_gen_job (pet_id, kind, attempt, status, error_code, pipeline_version,
                                          started_at, running_at, finished_at, total_cost_usd)
                values (?, 'HATCH', ?, ?, ?, ?, ?, ?, ?, 0.05) returning id
                """, Long.class, petId, attempt, status, error, version(), ts(started), ts(running), ts(finished));
    }

    private void step(Long jobId, int seq, String name, String status) {
        jdbc.update("""
                insert into zzal_gen_step (job_id, seq, name, status, started_at, finished_at)
                values (?, ?, ?, ?, ?, ?)
                """, jobId, seq, name, status, ts(ago(50)), ts(ago(49)));
    }

    private List<FailureRow> hatchRows() {
        return failures.hatchFailures(weekAgo, now);
    }

    private List<FailureRow> customRows() {
        return failures.customFailures(weekAgo, now);
    }

    @Test
    @DisplayName("부화 — FAILED 펫은 마지막 실패 잡의 이유·시도 수로 한 줄")
    void hatchFailures_includesFailedPet() {
        Long pet = egg("FAILED", "몽이");
        hatchJob(pet, 1, "FAILED", "UNKNOWN", ago(40), ago(40), ago(35));
        hatchJob(pet, 2, "FAILED", "UNKNOWN", ago(34), ago(34), ago(30));
        Long last = hatchJob(pet, 3, "FAILED", "TIMEOUT", ago(29), ago(29), ago(20));
        step(last, 1, "sheet", "SUCCEEDED");
        step(last, 2, "grid", "FAILED");

        List<FailureRow> rows = hatchRows();
        assertThat(rows).hasSize(1);
        FailureRow row = rows.getFirst();
        assertThat(row.kind()).isEqualTo("HATCH");
        assertThat(row.id()).isEqualTo(pet);
        assertThat(row.petName()).isEqualTo("몽이");
        assertThat(row.attempts()).isEqualTo(3);
        assertThat(row.reasonCode()).isEqualTo("TIMEOUT");
        assertThat(row.stage()).isEqualTo("grid");
        assertThat(row.failedAt()).isEqualTo(ago(20));
        assertThat(row.costUsd()).isEqualByComparingTo("0.15");
        assertThat(row.revivable()).isTrue();
        assertThat(row.autoCli()).isEqualTo("NONE");
        assertThat(row.request()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("부화 — HATCHING 인데 마지막 잡 활동이 유예(12분)를 넘기면 멈춘 알, 11분이면 아직 굽는 중")
    void hatchFailures_includesStuckHatching_afterGrace() {
        Long stuck = egg("HATCHING", "콩이");
        hatchJob(stuck, 1, "RUNNING", null, ago(14), ago(13), null);
        Long baking = egg("HATCHING", "밤이");
        hatchJob(baking, 1, "RUNNING", null, ago(12), ago(11), null);

        List<FailureRow> rows = hatchRows();
        assertThat(rows).extracting(FailureRow::id).containsExactly(stuck);
        assertThat(rows.getFirst().failedAt()).isEqualTo(ago(13));
        assertThat(rows.getFirst().reasonCode()).isEqualTo("STUCK");
        assertThat(rows.getFirst().revivable()).as("이름이 있으면 닫고 이어서 굽기").isTrue();
    }

    @Test
    @DisplayName("★ 부화 — 시작은 20분 전이어도 3번째 시도가 3분 전에 시작했으면 실패가 아니다(리뷰 초점 1)")
    void hatchFailures_excludesThirdAttemptInProgress() {
        Long pet = egg("HATCHING", "별이");
        jdbc.update("update zzal_pet set hatch_started_at = ? where id = ?", ts(ago(20)), pet);
        hatchJob(pet, 1, "FAILED", "UNKNOWN", ago(20), ago(20), ago(15));
        hatchJob(pet, 2, "FAILED", "UNKNOWN", ago(14), ago(14), ago(9));
        hatchJob(pet, 3, "RUNNING", null, ago(3), ago(3), null);

        assertThat(hatchRows()).isEmpty();
    }

    @Test
    @DisplayName("부화 — 단계를 다 끝내고 이름을 기다리는 DRAFT · 잡이 0개인 DRAFT 는 실패가 아니다")
    void hatchFailures_excludesDraftWaitingForName() {
        Long waiting = egg("DRAFT", null);
        Long job = hatchJob(waiting, 1, "SUCCEEDED", null, ago(40), ago(40), ago(35));
        int seq = 1;
        for (GenerationStep st : registry.steps(GenKind.HATCH, version())) {
            step(job, seq++, st.name(), "SUCCEEDED");
        }
        egg("DRAFT", null); // 잡 0개

        assertThat(hatchRows()).isEmpty();
    }

    @Test
    @DisplayName("부화 — 이름 짓기 전 실패는 목록에 보이되 1층 복구로 못 살린다(revivable=false)")
    void hatchFailures_marksNamelessAsNotRevivable() {
        Long pet = egg("FAILED", null);
        hatchJob(pet, 1, "FAILED", "MODERATION_BLOCKED", ago(30), ago(30), ago(25));

        FailureRow row = hatchRows().getFirst();
        assertThat(row.revivable()).isFalse();
        assertThat(row.blockedReason()).contains("이름");
    }

    // ── 맞춤 ──

    private ZzalPet alive() {
        return aliveLayerTwoPet(newUserId());
    }

    /** 방문 n회 — 마지막 방문이 lastAt, 앞의 것은 하루씩 앞. */
    private void visits(Long petId, int n, Instant lastAt) {
        for (int i = 1; i <= n; i++) {
            Instant at = lastAt.minus(Duration.ofDays(n - i));
            jdbc.update("""
                    insert into zzal_awake_visit (pet_id, program_version, cycle_key, event_id, observed_at)
                    values (?, 'first-v1', ?, ?, ?)
                    """, petId, "c" + i, "ev-" + petId + "-" + i, ts(at));
        }
    }

    private void slot(Long petId, String key, int day) {
        jdbc.update("""
                insert into zzal_attendance_reward_slot (pet_id, program_version, slot_key, eligible_day)
                values (?, 'first-v1', ?, ?)
                """, petId, key, day);
    }

    private Long customJob(Long petId, String bundle, String state, String error, String claim,
                           String manifest, String plan) {
        return jdbc.queryForObject("""
                insert into zzal_custom_bundle_job (pet_id, program_version, bundle_version, snapshot_json, input_hash,
                                                    state, error_code, claim_token, manifest_hash, source_plan_json,
                                                    created_at)
                values (?, 'first-v1', ?, '{}', ?, ?, ?, ?, ?, ?, ?) returning id
                """, Long.class, petId, bundle, "h-" + petId + "-" + bundle, state, error, claim, manifest, plan,
                ts(ago(60 * 24)));
    }

    private void publishedAssets(Long jobId) {
        for (int order = 1; order <= 8; order++) {
            jdbc.update("""
                    insert into zzal_custom_bundle_asset (job_id, motion_order, asset_key, asset_hash, auto_check,
                                                          human_review, published)
                    values (?, ?, ?, 'x', 'PASS', 'UNREVIEWED', true)
                    """, jobId, order, "images/zzal/custom/r/" + order + ".webp");
        }
    }

    @Test
    @DisplayName("맞춤 — 3일차 방문 뒤 31분 지나도록 못 받았고 잡이 CLAIMED·오류 없음이면 고아, 29분이면 아직")
    void customFailures_claimedOrphanAfterGrace() {
        ZzalPet late = alive();
        visits(late.getId(), 3, ago(31));
        slot(late.getId(), "motion-1", 3);
        Long job = customJob(late.getId(), "bundle-1", "CLAIMED", null, "tok", null, "{}");

        ZzalPet fresh = alive();
        visits(fresh.getId(), 3, ago(29));
        slot(fresh.getId(), "motion-1", 3);
        customJob(fresh.getId(), "bundle-1", "CLAIMED", null, "tok", null, "{}");

        List<FailureRow> rows = customRows();
        assertThat(rows).hasSize(1);
        FailureRow row = rows.getFirst();
        assertThat(row.kind()).isEqualTo("CUSTOM");
        assertThat(row.id()).isEqualTo(job);
        assertThat(row.petId()).isEqualTo(late.getId());
        assertThat(row.reasonCode()).isEqualTo("CLAIMED_ORPHAN");
        assertThat(row.failedAt()).isEqualTo(ago(31));
        assertThat(row.attempts()).isEqualTo(1);
        assertThat(row.costUsd()).isNull();
        assertThat(row.revivable()).isTrue();
    }

    @Test
    @DisplayName("★ 맞춤 — 게시까지 끝나 다음 방문에 받을 세트는 실패가 아니다(리뷰 초점 2)")
    void customFailures_excludesPublishedAwaitingVisit() {
        ZzalPet pet = alive();
        visits(pet.getId(), 3, ago(120));
        slot(pet.getId(), "motion-1", 3);
        Long job = customJob(pet.getId(), "bundle-1", "GENERATED", null, "tok", "m-hash", "{}");
        publishedAssets(job);

        assertThat(customRows()).isEmpty();
    }

    @Test
    @DisplayName("맞춤 — 배경 슬롯만 ELIGIBLE 이면 맞춤 실패가 아니다")
    void customFailures_ignoresBackgroundSlot() {
        ZzalPet pet = alive();
        visits(pet.getId(), 3, ago(120));
        slot(pet.getId(), "background", 2);

        assertThat(customRows()).isEmpty();
    }

    @Test
    @DisplayName("맞춤 — bundle-2 슬롯은 bundle-2 잡에 붙는다")
    void customFailures_bundle2UsesItsOwnJob() {
        ZzalPet pet = alive();
        visits(pet.getId(), 5, ago(120));
        Long first = customJob(pet.getId(), "bundle-1", "GENERATED", null, "tok", "m-hash", "{}");
        publishedAssets(first);
        slot(pet.getId(), "bundle-2:motion-1", 5);
        Long second = customJob(pet.getId(), "bundle-2", "FAILED", "ASSET_CHECK_FAILED", "tok", null, "{}");

        List<FailureRow> rows = customRows();
        assertThat(rows).extracting(FailureRow::id).containsExactly(second);
        assertThat(rows.getFirst().reasonCode()).isEqualTo("ASSET_CHECK_FAILED");
    }

    @Test
    @DisplayName("맞춤 — 계획 짜다 멈춘 잡(PENDING · PLANNING_IN_PROGRESS)은 PLANNING_STUCK")
    void customFailures_planningStuck() {
        ZzalPet pet = alive();
        visits(pet.getId(), 3, ago(120));
        slot(pet.getId(), "motion-1", 3);
        customJob(pet.getId(), "bundle-1", "PENDING", "PLANNING_IN_PROGRESS", null, null, null);

        ZzalPet noJob = alive();
        visits(noJob.getId(), 3, ago(120));
        slot(noJob.getId(), "motion-1", 3);

        List<FailureRow> rows = customRows();
        assertThat(rows).extracting(FailureRow::reasonCode)
                .containsExactlyInAnyOrder("PLANNING_STUCK", "NO_JOB");
        FailureRow missing = rows.stream().filter(r -> r.reasonCode().equals("NO_JOB")).findFirst().orElseThrow();
        assertThat(missing.revivable()).isFalse();
        assertThat(missing.blockedReason()).isEqualTo("잡이 없음");
    }

    @Test
    @DisplayName("목록 — 기간은 1·7·30일만, 5일이면 INVALID_INPUT. 머리 숫자와 시간대 막대")
    void page_rejectsDays5() {
        assertThatThrownBy(() -> failures.page(5, now))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        Long failed = egg("FAILED", "몽이");
        hatchJob(failed, 1, "FAILED", "TIMEOUT", ago(40), ago(40), ago(30));
        ZzalPet hatched = alive();
        jdbc.update("update zzal_pet set hatched_at = ? where id = ?", ts(ago(10)), hatched.getId()); // 부화 성공 1
        ZzalPet older = alive();
        jdbc.update("update zzal_pet set hatched_at = ? where id = ?", ts(now.minus(Duration.ofDays(2))), older.getId());

        FailurePage page = failures.page(1, now);
        assertThat(page.hatchFailed()).isEqualTo(1);
        assertThat(page.hatchSucceeded()).isEqualTo(1);
        assertThat(page.customFailed()).isZero();
        // from 이 든 시간부터 now 가 든 시간까지 빈 칸 없이 — 하루면 양 끝 포함 25칸
        assertThat(page.hours()).hasSize(25);
        assertThat(page.hours().getFirst().hour()).isEqualTo(now.minus(Duration.ofDays(1)).truncatedTo(ChronoUnit.HOURS));
        assertThat(page.hours().stream().mapToInt(h -> h.hatch()).sum()).isEqualTo(1);
        assertThat(page.rows()).extracting(FailureRow::id).containsExactly(failed);
    }

    @Test
    @DisplayName("목록 — 요청 이력으로 자동 CLI·수동 요청 상태와 열린 요청 id 를 채운다")
    void failures_carryRetryState() {
        setUpPet();
        Long egg = egg("FAILED", "몽이");
        hatchJob(egg, 1, "FAILED", "TIMEOUT", ago(40), ago(40), ago(30));
        AdminRetry auto = retries.saveAndFlush(AdminRetry.auto(AdminRetryKind.HATCH, egg, egg, ago(29)));
        auto.applyFailed("게이트 미통과", ago(25));
        retries.saveAndFlush(auto);
        AdminRetry manual = retries.saveAndFlush(
                AdminRetry.manual(AdminRetryKind.HATCH, egg, egg, "격자 3번 칸 비어 있음", admin, ago(20)));

        FailureRow row = hatchRows().getFirst();
        assertThat(row.autoCli()).isEqualTo("FAILED");
        assertThat(row.request()).isEqualTo("WAITING");
        assertThat(row.openRetryId()).isEqualTo(manual.getId());
    }

    // ── 작업 1.4: 실패 상세 ─────────────────────────────────────────────────

    private void stepWith(Long jobId, int seq, String name, String status, String key, String text) {
        jdbc.update("""
                insert into zzal_gen_step (job_id, seq, name, status, started_at, finished_at, output_key, output_text,
                                           cost_usd, model)
                values (?, ?, ?, ?, ?, ?, ?, ?, 0.02, 'gpt-image-2')
                """, jobId, seq, name, status, ts(ago(50)), ts(ago(49)), key, text);
    }

    @Test
    @DisplayName("상세(부화) — 시트는 성공한 sheet 단계 기록의 output_key, 단계 타임라인과 입력 그림")
    void detail_hatch_returnsSheetFromSucceededStep() {
        setUpPet();
        Long pet = egg("FAILED", "몽이");
        Long job = hatchJob(pet, 1, "FAILED", "TIMEOUT", ago(40), ago(40), ago(30));
        stepWith(job, 1, "sheet", "SUCCEEDED", "images/zzal/pets/%d/sheet.png".formatted(pet), null);
        stepWith(job, 2, "identity", "SUCCEEDED", null, "(정체성 문단)");
        stepWith(job, 3, "grid", "FAILED", null, "격자 시간 초과");

        FailureDetail d = details.detail(admin, "HATCH", pet, now);
        assertThat(d.row()).isNotNull();
        assertThat(d.row().id()).isEqualTo(pet);
        assertThat(d.images().sheetKey()).isEqualTo("images/zzal/pets/%d/sheet.png".formatted(pet));
        assertThat(d.images().inputKey()).startsWith("images/zzal/");
        assertThat(d.images().identityText()).isEqualTo("(정체성 문단)");
        assertThat(d.images().resultKeys()).isEmpty();
        assertThat(d.identityMissing()).isFalse();
        assertThat(d.steps()).extracting(s -> s.name()).containsExactly("sheet", "identity", "grid");
        assertThat(d.steps().get(2).durationMs()).isEqualTo(60_000L);
        assertThat(d.errorDetail()).isEqualTo("격자 시간 초과");
        assertThat(d.retries()).isEmpty();
    }

    @Test
    @DisplayName("상세(부화) — 정체성 성공 기록이 없거나 빈 문단이면 identityMissing")
    void detail_hatch_flagsMissingIdentity() {
        setUpPet();
        Long pet = egg("FAILED", "몽이");
        Long job = hatchJob(pet, 1, "FAILED", "UNKNOWN", ago(40), ago(40), ago(30));
        stepWith(job, 1, "sheet", "SUCCEEDED", "images/zzal/pets/%d/sheet.png".formatted(pet), null);
        assertThat(details.detail(admin, "HATCH", pet, now).identityMissing()).isTrue();

        stepWith(job, 2, "identity", "SUCCEEDED", null, "  ");
        assertThat(details.detail(admin, "HATCH", pet, now).identityMissing()).isTrue();
    }

    @Test
    @DisplayName("상세(맞춤) — 격자는 images/zzal/custom/{requestId}/grid.png, 결과는 자산 8개 키")
    void detail_custom_returnsGridUnderRequestPrefix() {
        setUpPet();
        ZzalPet pet = alive();
        visits(pet.getId(), 3, ago(120));
        slot(pet.getId(), "motion-1", 3);
        String plan = "{\"motionPlan\":{}}";
        Long job = customJob(pet.getId(), "bundle-1", "CLAIMED", null, "tok", null, plan);
        jdbc.update("update zzal_custom_bundle_job set snapshot_json = ? where id = ?",
                "{\"sourceImageKey\":\"images/zzal/in.png\",\"sheetImageKey\":\"images/zzal/sheet.png\",\"identity\":\"문단\"}",
                job);
        publishedAssets(job);

        FailureDetail d = details.detail(admin, "CUSTOM", job, now);
        String requestId = java.util.UUID.nameUUIDFromBytes((job + ":" + sha256(plan))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        assertThat(d.row().reasonCode()).isEqualTo("CLAIMED_ORPHAN");
        assertThat(d.images().inputKey()).isEqualTo("images/zzal/in.png");
        assertThat(d.images().sheetKey()).isEqualTo("images/zzal/sheet.png");
        assertThat(d.images().gridKeys()).containsExactly("images/zzal/custom/" + requestId + "/grid.png");
        assertThat(d.images().resultKeys()).hasSize(8).containsEntry("1", "images/zzal/custom/r/1.webp");
        assertThat(d.steps()).extracting(s -> s.name()).containsExactly("접수", "계획", "그림", "후처리·게시");
        assertThat(d.identityMissing()).isFalse();
    }

    @Test
    @DisplayName("상세 — kind 가 HATCH·CUSTOM 이 아니면 INVALID_INPUT, 없는 id 는 NOT_FOUND")
    void detail_unknownKind_400() {
        setUpPet();
        assertThatThrownBy(() -> details.detail(admin, "LAYER2", 1L, now))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThatThrownBy(() -> details.detail(admin, "HATCH", 999_999L, now))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode().getStatus().value()).isEqualTo(404);
        assertThatThrownBy(() -> details.detail(admin, "CUSTOM", 999_999L, now))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode().getStatus().value()).isEqualTo(404);
    }

    static String sha256(String v) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(v.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── 작업 1.5: 요청 · 취소 · 러너 집기 · 대기 목록 · 러너 시각 ─────────────────

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).getErrorCode();
    }

    private Long failedEgg() {
        Long pet = egg("FAILED", "몽이");
        Long job = hatchJob(pet, 1, "FAILED", "TIMEOUT", ago(40), ago(40), ago(30));
        stepWith(job, 1, "sheet", "SUCCEEDED", "images/zzal/pets/%d/sheet.png".formatted(pet), null);
        return pet;
    }

    private Long orphanJob(String plan) {
        ZzalPet pet = alive();
        visits(pet.getId(), 3, ago(120));
        slot(pet.getId(), "motion-1", 3);
        Long job = customJob(pet.getId(), "bundle-1", "CLAIMED", null, "tok", null, plan);
        if (plan != null) {
            jdbc.update("update zzal_custom_bundle_job set source_plan_hash = ? where id = ?", sha256(plan), job);
        }
        return job;
    }

    @Test
    @DisplayName("요청(부화 FAILED) — 행 1개에 메모가 남고, 기존 1층 LOCAL_REQUESTED 표시가 걸린다")
    void request_hatch_createsRowAndSetsLocalRequested() {
        setUpPet();
        Long pet = failedEgg();
        RetryView v = retryService.request(admin, "HATCH", pet, "  격자 3번 칸 비어 있음\u0007 ", now);

        assertThat(v.result()).isNull();
        AdminRetry row = retries.findById(v.id()).orElseThrow();
        assertThat(row.getMemo()).isEqualTo("격자 3번 칸 비어 있음");
        assertThat(row.getRequestedBy()).isEqualTo(admin);
        assertThat(jdbc.queryForObject("select layer1_regen_requested_at is not null from zzal_pet where id = ?",
                Boolean.class, pet)).isTrue();
        assertThat(hatchRows().getFirst().request()).isEqualTo("WAITING");
    }

    @Test
    @DisplayName("요청 — 같은 대상에 두 번이면 두 번째는 ADMIN_RETRY_OPEN(409)")
    void request_duplicate_409() {
        setUpPet();
        Long pet = failedEgg();
        retryService.request(admin, "HATCH", pet, null, now);
        assertThatThrownBy(() -> retryService.request(admin, "HATCH", pet, null, now))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.ADMIN_RETRY_OPEN))
                .satisfies(e -> assertThat(code(e).getStatus().value()).isEqualTo(409));
        assertThat(retries.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("요청 — 메모 500자 넘으면 INVALID_INPUT, 행 없음 · 실패 목록에 없는 대상도 INVALID_INPUT")
    void request_memoOver500_400() {
        setUpPet();
        Long pet = failedEgg();
        assertThatThrownBy(() -> retryService.request(admin, "HATCH", pet, "가".repeat(501), now))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.INVALID_INPUT));
        assertThat(retries.count()).isZero();

        ZzalPet fine = alive();
        assertThatThrownBy(() -> retryService.request(admin, "HATCH", fine.getId(), null, now))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    @DisplayName("요청(맞춤) — 잡 행은 한 칸도 안 바뀐다")
    void request_custom_doesNotTouchJobRow() {
        setUpPet();
        Long job = orphanJob(null);
        var before = jdbc.queryForMap("select * from zzal_custom_bundle_job where id = ?", job);
        RetryView v = retryService.request(admin, "CUSTOM", job, "고아", now);
        assertThat(v.kind()).isEqualTo("CUSTOM");
        assertThat(jdbc.queryForMap("select * from zzal_custom_bundle_job where id = ?", job)).isEqualTo(before);
    }

    @Test
    @DisplayName("취소하면 LOCAL_REQUESTED 도 거두고, 다시 요청할 수 있다 · 닫힌 행 취소는 409")
    void cancel_thenRequestAgain_ok() {
        setUpPet();
        Long pet = failedEgg();
        RetryView first = retryService.request(admin, "HATCH", pet, null, now);
        RetryView cancelled = retryService.cancel(admin, first.id(), now.plusSeconds(1));
        assertThat(cancelled.result()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select layer1_regen_requested_at is null from zzal_pet where id = ?",
                Boolean.class, pet)).isTrue();
        assertThatThrownBy(() -> retryService.cancel(admin, first.id(), now.plusSeconds(2)))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.ADMIN_RETRY_CLOSED));

        RetryView again = retryService.request(admin, "HATCH", pet, "다시", now.plusSeconds(3));
        assertThat(again.id()).isNotEqualTo(first.id());
    }

    @Test
    @DisplayName("대기 목록 — 안 집힌 것만, 부를 때마다 러너 확인 시각을 남긴다")
    void pending_returnsOnlyUnpicked_andTouchesClock() {
        setUpPet();
        Long a = failedEgg();
        Long b = failedEgg();
        RetryView ra = retryService.request(admin, "HATCH", a, null, now);
        RetryView rb = retryService.request(admin, "HATCH", b, null, now.plusSeconds(1));
        assertThat(retryService.runnerLastSeen()).isEmpty();

        PendingItem claimed = retryService.claim(admin, ra.id(), now.plusSeconds(5));
        assertThat(claimed.hatch().sheetKey()).isEqualTo("images/zzal/pets/%d/sheet.png".formatted(a));
        assertThat(claimed.hatch().failedStage()).isNull();

        List<PendingItem> pending = retryService.pending(admin, now.plusSeconds(10));
        assertThat(pending).extracting(PendingItem::retryId).containsExactly(rb.id());
        assertThat(retryService.runnerLastSeen()).contains(now.plusSeconds(10));
        assertThat(hatchRows().stream().filter(r -> r.id() == a).findFirst().orElseThrow().request())
                .isEqualTo("BAKING");
    }

    @Test
    @DisplayName("★ 대기 목록 — 대상 펫이 이미 살아 있으면 그 행을 APPLIED(다른 길로 복구됨)로 닫는다(리뷰 초점 5)")
    void pending_closesAlreadyRecoveredHatch() {
        setUpPet();
        Long pet = failedEgg();
        RetryView v = retryService.request(admin, "HATCH", pet, null, now);
        jdbc.update("update zzal_pet set phase = 'ALIVE' where id = ?", pet);

        assertThat(retryService.pending(admin, now.plusSeconds(1))).isEmpty();
        AdminRetry row = retries.findById(v.id()).orElseThrow();
        assertThat(row.getResult()).isEqualTo(AdminRetryResult.APPLIED);
        assertThat(row.getNote()).isEqualTo("다른 길로 복구됨");
        assertThat(row.getAppliedBy()).isNull();
    }

    @Test
    @DisplayName("집기 — 두 번째는 ADMIN_RETRY_CLOSED(409)")
    void claim_twice_409() {
        setUpPet();
        RetryView v = retryService.request(admin, "HATCH", failedEgg(), null, now);
        retryService.claim(admin, v.id(), now.plusSeconds(1));
        assertThatThrownBy(() -> retryService.claim(admin, v.id(), now.plusSeconds(2)))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.ADMIN_RETRY_CLOSED));
    }

    @Test
    @DisplayName("대기 목록(맞춤) — 계획 원문 바이트 그대로, 그 sha256 이 source_plan_hash, requestId 는 같은 식")
    void pending_customIncludesExactPlanBytes() {
        setUpPet();
        String plan = "{\"motionPlan\": {\"version\":\"v1\"},  \"selectedSources\":[]}";
        Long job = orphanJob(plan);
        retryService.request(admin, "CUSTOM", job, null, now);

        PendingItem item = retryService.pending(admin, now.plusSeconds(1)).getFirst();
        assertThat(item.custom().sourcePlanJson()).isEqualTo(plan);
        assertThat(sha256(item.custom().sourcePlanJson())).isEqualTo(item.custom().sourcePlanHash());
        assertThat(item.custom().requestId()).isEqualTo(java.util.UUID.nameUUIDFromBytes((job + ":" + sha256(plan))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
        assertThat(item.custom().state()).isEqualTo("CLAIMED");
        assertThat(item.hatch()).isNull();
    }

    // ── 작업 1.6: 러너 결과 등록 + 부화 반영 ──────────────────────────────────

    /** 시트 없이 실패한 알(시트부터 실패) — 러너가 시트를 새로 만들어 와야 살린다. */
    private Long sheetlessEgg() {
        Long pet = egg("FAILED", "몽이");
        Long job = hatchJob(pet, 1, "FAILED", "MODERATION_BLOCKED", ago(40), ago(40), ago(30));
        stepWith(job, 1, "sheet", "FAILED", null, "그림 안전 검사");
        return pet;
    }

    private long claimedHatchRetry(Long pet) {
        RetryView v = retryService.request(admin, "HATCH", pet, null, now);
        retryService.claim(admin, v.id(), now.plusSeconds(1));
        return v.id();
    }

    private void put(String key, String body) throws Exception {
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("admin-it-", ".bin");
        java.nio.file.Files.writeString(tmp, body);
        storage.upload(key, tmp, "application/octet-stream");
        java.nio.file.Files.deleteIfExists(tmp);
    }

    @Test
    @DisplayName("★ 러너 결과 — 취소된 행이면 409, 1층 후보도 올리지 않는다(리뷰 초점 4)")
    void hatchCandidates_cancelledRow_409_andNoLayerCandidates() {
        setUpPet();
        Long pet = sheetlessEgg();
        long id = claimedHatchRetry(pet);
        retryService.cancel(admin, id, now.plusSeconds(2));

        String grid = newUploadedImageKey(admin);
        assertThatThrownBy(() -> hatchApplier.hatchCandidates(admin, id,
                new RetryCandidatesRequest(List.of(grid), null, null, null, "늦게 옴"), now.plusSeconds(3)))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.ADMIN_RETRY_CLOSED));
        assertThat(jdbc.queryForObject("select layer1_candidates from zzal_pet where id = ?", String.class, pet))
                .isNull();
        assertThat(retries.findById(id).orElseThrow().getResult()).isEqualTo(AdminRetryResult.CANCELLED);
    }

    @Test
    @DisplayName("러너 결과 — 격자 0장이면 NONE 으로 닫힌다 · 안 집은 행은 400")
    void hatchCandidates_emptyGrids_resultNone() {
        setUpPet();
        Long pet = sheetlessEgg();
        RetryView unclaimed = retryService.request(admin, "HATCH", pet, null, now);
        assertThatThrownBy(() -> hatchApplier.hatchCandidates(admin, unclaimed.id(),
                new RetryCandidatesRequest(List.of(), null, null, null, null), now))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.INVALID_INPUT));

        retryService.claim(admin, unclaimed.id(), now.plusSeconds(1));
        var result = hatchApplier.hatchCandidates(admin, unclaimed.id(),
                new RetryCandidatesRequest(List.of(), null, null, null, "격자가 다 깨짐"), now.plusSeconds(2));
        assertThat(result.result()).isEqualTo("NONE");
        AdminRetry row = retries.findById(unclaimed.id()).orElseThrow();
        assertThat(row.getResult()).isEqualTo(AdminRetryResult.NONE);
        assertThat(row.getNote()).isEqualTo("격자가 다 깨짐");
        assertThat(row.isOpen()).isFalse();
    }

    @Test
    @DisplayName("★ 고르기 — 봇 토큰 호출이면 403, 펫은 FAILED 그대로")
    void hatchPick_byBotToken_403() {
        setUpPet();
        Long pet = sheetlessEgg();
        long id = claimedHatchRetry(pet);
        transactions.executeWithoutResult(s -> retries.findByIdForUpdate(id).orElseThrow().deliver(
                AdminRetryResult.CANDIDATES, null,
                java.util.Map.of("grids", List.of(java.util.Map.of("candidateId", "c-1", "gate", "PASS"))), now));

        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(admin, null,
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"),
                                new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                        AdminBotToken.BOT_AUTHORITY))));
        try {
            assertThatThrownBy(() -> hatchApplier.hatchPick(admin, id, "c-1", now))
                    .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.FORBIDDEN));
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
        assertThat(jdbc.queryForObject("select phase from zzal_pet where id = ?", String.class, pet))
                .isEqualTo("FAILED");
        assertThat(retries.findById(id).orElseThrow().getResult()).isEqualTo(AdminRetryResult.CANDIDATES);
    }

    @Test
    @DisplayName("★ 고르기(쿠키 관리자) — 시트 기록이 없는 알을 러너 시트·문단으로 살린다, 행 APPLIED")
    void hatchPick_byCookieAdmin_revivesWithRunnerSheet() throws Exception {
        setUpPet();
        Long pet = sheetlessEgg();
        long id = claimedHatchRetry(pet);
        String grid = newUploadedImageKey(admin);
        String sheet = newUploadedImageKey(admin);

        var result = hatchApplier.hatchCandidates(admin, id,
                new RetryCandidatesRequest(List.of(grid), sheet, "(러너 문단)", null, "Codex 1회"), now.plusSeconds(2));
        assertThat(result.result()).isEqualTo("CANDIDATES");
        assertThat(result.hatch()).hasSize(1);
        String cid = result.hatch().getFirst().candidateId();
        assertThat(result.hatch().getFirst().gate()).isEqualTo("PASS");
        assertThat(retries.findById(id).orElseThrow().getCandidateKeys()).containsEntry("sheetKey", sheet);
        assertThat(hatchRows().getFirst().request()).isEqualTo("CANDIDATES");

        // 가짜 후처리는 파일을 안 만든다 — 고를 때 옮길 후보 그림을 메모리 S3 에 놓아 둔다.
        String prefix = com.lore.zzal.generation.LayerCandidateBaker.prefix(pet, 1, cid);
        for (String k : baker.keys(1)) {
            put("%s/%s.webp".formatted(prefix, k), k);
        }
        put(prefix + "/anchors.json", "{}");

        var picked = hatchApplier.hatchPick(admin, id, cid, now.plusSeconds(5));
        assertThat(picked.phase()).isEqualTo("ALIVE");
        assertThat(jdbc.queryForObject("select sheet_image_key from zzal_pet where id = ?", String.class, pet))
                .isEqualTo(sheet);
        assertThat(jdbc.queryForObject("select identity_text from zzal_pet where id = ?", String.class, pet))
                .isEqualTo("(러너 문단)");
        AdminRetry row = retries.findById(id).orElseThrow();
        assertThat(row.getResult()).isEqualTo(AdminRetryResult.APPLIED);
        assertThat(row.getAppliedBy()).isEqualTo(admin);
        assertThat(row.getAppliedChoice()).isEqualTo(cid);
        assertThat(hatchRows()).isEmpty();
    }

    @Test
    @DisplayName("관리자 스위치가 꺼져 있으면 실패 API 는 없다(404)")
    void controller_adminDisabled_404() throws Exception {
        setUpPet();
        var res = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/zzal/v1/admin/failures").with(asUser(admin))).andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(404);
    }

    // ── 수정 9: 멈춘 알도 러너 길 ─────────────────────────────────────────────

    @Test
    @DisplayName("★ 멈춘 알 재시도 — 굽던 잡을 실패로 닫고 알도 FAILED(사망 사유 기록), 러너 요청 · LOCAL_REQUESTED, 새 잡 없음")
    void request_stuckHatch_closesAndRequestsRunner() {
        setUpPet();
        Long pet = egg("HATCHING", "콩이");
        Long job = hatchJob(pet, 1, "RUNNING", null, ago(30), ago(30), null);
        RetryView v = retryService.request(admin, "HATCH", pet, "멈춤", now);

        assertThat(v.result()).isNull();
        assertThat(jdbc.queryForObject("select phase from zzal_pet where id = ?", String.class, pet)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select death_reason from zzal_pet where id = ?", String.class, pet)).isEqualTo("HATCH_FAILED");
        assertThat(jdbc.queryForObject("select status from zzal_gen_job where id = ?", String.class, job)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select layer1_regen_requested_at is not null from zzal_pet where id = ?",
                Boolean.class, pet)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from zzal_gen_job where pet_id = ?", Integer.class, pet))
                .as("서버에서 굽지 않는다").isEqualTo(1);
        assertThat(retryService.pending(admin, now)).extracting(PendingItem::retryId).containsExactly(v.id());
    }

    @Test
    @DisplayName("★ 멈춘 알 재시도 — 동시에 두 번 눌러도 요청은 하나(두 번째는 409)")
    void request_stuckHatch_concurrentTwice_oneRow() throws Exception {
        setUpPet();
        Long pet = egg("HATCHING", "콩이");
        hatchJob(pet, 1, "RUNNING", null, ago(30), ago(30), null);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<Object>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        return retryService.request(admin, "HATCH", pet, null, now);
                    } catch (BusinessException e) {
                        return e.getErrorCode();
                    }
                }));
            }
            start.countDown();
            List<Object> out = new java.util.ArrayList<>();
            for (var f : results) {
                out.add(f.get(30, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertThat(out).filteredOn(o -> o instanceof RetryView).hasSize(1);
            assertThat(out).filteredOn(o -> o instanceof ErrorCode).hasSize(1)
                    .allSatisfy(o -> assertThat(((ErrorCode) o).getStatus().value()).isEqualTo(409));
        } finally {
            pool.shutdownNow();
        }
        assertThat(retries.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select phase from zzal_pet where id = ?", String.class, pet)).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("멈춘 알 재시도 — 시도 상한(3회)에 닿은 알도 같은 길(새 시도 없음), 단계가 방금 돌았으면 아직 굽는 중이라 거부")
    void request_stuckHatch_atAttemptCap_andRecentStepRejected() {
        setUpPet();
        Long pet = egg("HATCHING", "콩이");
        hatchJob(pet, 1, "FAILED", "UNKNOWN", ago(60), ago(60), ago(55));
        hatchJob(pet, 2, "FAILED", "UNKNOWN", ago(50), ago(50), ago(45));
        Long third = hatchJob(pet, 3, "RUNNING", null, ago(40), ago(40), null);
        retryService.request(admin, "HATCH", pet, null, now);
        assertThat(jdbc.queryForObject("select status from zzal_gen_job where id = ?", String.class, third)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select count(*) from zzal_gen_job where pet_id = ?", Integer.class, pet)).isEqualTo(3);

        // 잡 칸은 30분 전이지만 단계가 3분 전에 시작했다 → 아직 굽는 중
        Long busy = egg("HATCHING", "별이");
        Long job = hatchJob(busy, 1, "RUNNING", null, ago(30), ago(30), null);
        jdbc.update("insert into zzal_gen_step (job_id, seq, name, status, started_at) values (?, 2, 'grid', 'RUNNING', ?)",
                job, ts(ago(3)));
        assertThat(hatchRows()).extracting(FailureRow::id).doesNotContain(busy);
        assertThatThrownBy(() -> retryService.request(admin, "HATCH", busy, null, now))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.INVALID_INPUT));
        assertThat(jdbc.queryForObject("select phase from zzal_pet where id = ?", String.class, busy)).isEqualTo("HATCHING");
    }

    // ── 수정 2: 재시도 대상 = 반영할 수 있는 잡 상태 ─────────────────────────────

    @Test
    @DisplayName("맞춤 — 다 만들었지만 미게시(GENERATED)는 목록에 보이되 재시도 거부(게시만 다시 필요), 다른 FAILED 도 거부")
    void customRetry_onlyApplicableJobStates() {
        setUpPet();
        ZzalPet a = alive();
        visits(a.getId(), 3, ago(120));
        slot(a.getId(), "motion-1", 3);
        Long generated = customJob(a.getId(), "bundle-1", "GENERATED", null, "tok", "m-hash", "{}");
        ZzalPet b = alive();
        visits(b.getId(), 3, ago(120));
        slot(b.getId(), "motion-1", 3);
        Long otherFail = customJob(b.getId(), "bundle-1", "FAILED", "CUSTOM_POSTPROCESS_CRASH", "tok", null, "{}");

        FailureRow g = customRows().stream().filter(r -> r.id() == generated).findFirst().orElseThrow();
        assertThat(g.reasonCode()).isEqualTo("NOT_PUBLISHED");
        assertThat(g.revivable()).isFalse();
        assertThat(g.blockedReason()).isEqualTo("게시만 다시 필요");
        FailureRow f = customRows().stream().filter(r -> r.id() == otherFail).findFirst().orElseThrow();
        assertThat(f.revivable()).isFalse();
        for (Long job : List.of(generated, otherFail)) {
            assertThatThrownBy(() -> retryService.request(admin, "CUSTOM", job, null, now))
                    .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.INVALID_INPUT));
        }
        assertThat(retries.count()).isZero();
    }
}
