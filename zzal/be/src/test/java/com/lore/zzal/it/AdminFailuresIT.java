package com.lore.zzal.it;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.admin.failures.AdminFailureQuery;
import com.lore.zzal.admin.failures.AdminFailureResponses.FailurePage;
import com.lore.zzal.admin.failures.AdminFailureResponses.FailureRow;
import com.lore.zzal.admin.retry.AdminRetry;
import com.lore.zzal.admin.retry.AdminRetryKind;
import com.lore.zzal.admin.retry.AdminRetryRepository;
import com.lore.zzal.admin.retry.AdminRetryResult;
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
}
