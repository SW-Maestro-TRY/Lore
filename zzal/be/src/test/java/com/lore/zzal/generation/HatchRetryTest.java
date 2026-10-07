package com.lore.zzal.generation;

import com.lore.zzal.alert.ZzalAlerts;
import com.lore.zzal.generation.steps.MotionGridStep;
import com.lore.zzal.guard.HatchBlockLog;
import com.lore.zzal.guard.QuotaBreaker;
import com.lore.zzal.generation.steps.MotionPostStep;
import com.lore.zzal.motion.MotionSeeder;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalPetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 부화 재시도 — <b>다시 하는가, 그리고 상한에서 멈추는가</b>(M-8).
 *
 * <h3>★ 왜 이 시험이 필요한가</h3>
 * {@code HatchService.hatch} 를 <b>실제로 실행하는 시험이 하나도 없었다.</b>
 * {@code StuckHatchRecoveryTest} 는 {@code verify(hatch).hatch(...)} 로 목만 보고,
 * {@code HatchCompletionTest} 는 {@code completeIfReady} 만 부른다. 그래서 재시도 오케스트레이션 —
 * "한 번 더 굽고, 상한에 닿으면 실패로 끝낸다" — 가 통째로 사각지대였다.
 *
 * <h3>왜 위험한가</h3>
 * 재시도가 안 돌면 사용자는 <b>끝나지 않는 알</b>을 본다(끝났다는 말도 없다). 반대로 상한이 새면
 * 같은 그림 한 장에 $0.25 가 세 번 나간다. 거부(MODERATION)로 실패했을 때 문단을 폐기하지 않으면
 * <b>같은 문단을 또 보내 또 막힌다</b> — 실패가 두 배 값으로 반복된다.
 *
 * <h3>★ 무엇이 목인가</h3>
 * 실행기(runner)만 실패·성공을 흉내 내고, 그 앞뒤 판단은 전부 진짜 {@link HatchService} 다.
 */
@DisplayName("부화 재시도 — 상한 5의 경계 · 어느 격자를 버리나")
class HatchRetryTest {

    private static final Long PET = 7L;
    private static final String V = "v1";
    private static final Instant T0 = Instant.parse("2026-09-11T03:00:00Z");
    private static final int MAX_ATTEMPTS = 5;

    private GenerationRunner runner;
    private GenerationRecorder recorder;
    private GenJobRepository jobRepository;
    private PipelineRegistry registry;
    private HatchService service;
    private QuotaBreaker quotaBreaker;
    private HatchBlockLog blockLog;

    /** 저장된 job 들 — 재시도가 <b>새 job 을 저장하는가</b>가 이 시험의 핵심이라 진짜 표처럼 둔다. */
    private final List<GenJob> jobs = new ArrayList<>();
    private final AtomicLong jobIds = new AtomicLong();
    private long alreadyAttempted;

    @BeforeEach
    void setUp() {
        runner = mock(GenerationRunner.class);
        recorder = mock(GenerationRecorder.class);
        jobRepository = mock(GenJobRepository.class);
        ZzalPetRepository petRepository = mock(ZzalPetRepository.class);

        ZzalPet pet = ZzalPet.draft(1L, "images/zzal/src", T0);
        pet.character("여울", null, null, null, null, null, T0);
        when(petRepository.findById(PET)).thenReturn(Optional.of(pet));
        when(recorder.loadSucceeded(anyLong(), any(), anyString())).thenReturn(List.of());
        when(recorder.discardSucceeded(anyLong(), any(), anyString())).thenReturn(1);

        when(jobRepository.save(any())).thenAnswer(inv -> {
            GenJob job = inv.getArgument(0);
            ReflectionTestUtils.setField(job, "id", jobIds.incrementAndGet());
            jobs.add(job);
            return job;
        });
        when(jobRepository.findById(anyLong())).thenAnswer(inv ->
                jobs.stream().filter(j -> inv.getArgument(0).equals(j.getId())).findFirst());
        // ★ "지금까지 몇 번 구웠나" = 이미 있던 시도 + 이 시험 안에서 저장된 것
        when(jobRepository.countByPetIdAndKind(eq(PET), eq(GenKind.HATCH)))
                .thenAnswer(inv -> alreadyAttempted + jobs.size());

        registry = new PipelineRegistry(
                StepMocks.sheet(), StepMocks.identity(),
                StepMocks.grid(), StepMocks.grid2(), StepMocks.post(),
                mock(MotionGridStep.class), mock(MotionPostStep.class), "v1", "v1");

        quotaBreaker = new QuotaBreaker();
        blockLog = mock(HatchBlockLog.class);
        service = new HatchService(runner, recorder, jobRepository, registry, petRepository,
                quotaBreaker, blockLog, mock(ZzalAlerts.class), MAX_ATTEMPTS, mock(MotionSeeder.class));
    }

    /** 이 펫이 이미 {@code n} 번 구워진 상태로 둔다(그 job 들은 표에 있고 이 시험이 세지 않는다). */
    private void alreadyAttempted(long n) {
        alreadyAttempted = n;
    }

    /** 지금 도는 그 job 한 줄. 실행기가 실패로 끝냈다고 친다. */
    private GenJob failedJob(GenErrorCode code) {
        // ★ 지금 도는 job 의 attempt = 이미 구운 수 + 1 (PetService·기동 복구가 매기는 방식 그대로)
        GenJob job = jobRepository.save(GenJob.start(PET, GenKind.HATCH, (int) alreadyAttempted + 1, V, T0));
        job.markRunning(T0);
        job.fail(code, BigDecimal.ZERO, T0);
        return job;
    }

    private void runnerAlwaysFails(GenErrorCode code) {
        when(runner.run(anyLong(), any(), any(), any()))
                .thenAnswer(inv -> RunResult.failed(null, BigDecimal.ZERO, code));
    }

    @Test
    @DisplayName("★ 계속 실패하면 상한(5)까지 굽고 멈춘다 — 새 job 4개, 실행기 5회, 실패 확정 1번")
    void keepsRetryingUpToTheLimit() {
        alreadyAttempted(0);
        GenJob job = failedJob(GenErrorCode.UNKNOWN);
        runnerAlwaysFails(GenErrorCode.UNKNOWN);

        service.hatch(job.getId(), PET, V);

        assertThat(jobs).as("처음 것 + 재시도 넷").hasSize(5);
        assertThat(jobs.stream().map(GenJob::getAttempt).toList()).containsExactly(1, 2, 3, 4, 5);
        verify(runner, times(5)).run(anyLong(), any(), any(), any());
        verify(recorder, times(1)).markPetFailed(PET);
    }

    @Test
    @DisplayName("★★ 바깥 한도(429)면 <b>다시 굽지 않는다</b> — 한 번 막힌 것이 그대로 두 배가 되던 자리")
    void quotaBlockedIsNotRetried() {
        alreadyAttempted(0);
        GenJob job = failedJob(GenErrorCode.UNKNOWN);
        when(runner.run(anyLong(), any(), any(), any()))
                .thenAnswer(inv -> RunResult.quotaBlocked(null, BigDecimal.ZERO, GenErrorCode.UNKNOWN));

        service.hatch(job.getId(), PET, V);

        assertThat(jobs).as("재시도 job 이 생기면 안 된다 — 같은 키로 또 보내면 또 429 다").hasSize(1);
        verify(runner, times(1)).run(anyLong(), any(), any(), any());
        verify(recorder).markPetFailed(PET);
    }

    @Test
    @DisplayName("★★ 429 를 본 순간 차단기가 내려간다 — 그 뒤의 새 부화는 굽기 시작 전에 막힌다")
    void quotaBlockedTripsTheBreaker() {
        alreadyAttempted(0);
        GenJob job = failedJob(GenErrorCode.UNKNOWN);
        when(runner.run(anyLong(), any(), any(), any()))
                .thenAnswer(inv -> RunResult.quotaBlocked(null, BigDecimal.ZERO, GenErrorCode.UNKNOWN));

        assertThat(quotaBreaker.isOpen(java.time.Duration.ofMinutes(30), Instant.now())).isFalse();
        service.hatch(job.getId(), PET, V);

        assertThat(quotaBreaker.isOpen(java.time.Duration.ofMinutes(30), Instant.now()))
                .as("차단기가 안 내려가면 다음 사람이 곧바로 또 굽는다").isTrue();
        verify(blockLog).record(eq(com.lore.zzal.guard.HatchBlock.QUOTA), any(), any(), any(), any());
    }

    @Test
    @DisplayName("★ 격자 구조 이상은 <b>지금대로</b> 다시 굽는다 — 429 와 처방이 정반대다")
    void gridRejectedStillRetries() {
        alreadyAttempted(0);
        GenJob job = failedJob(GenErrorCode.UNKNOWN);
        when(runner.run(anyLong(), any(), any(), any()))
                .thenAnswer(inv -> RunResult.gridRejected(null, BigDecimal.ZERO, GenErrorCode.UNKNOWN));

        service.hatch(job.getId(), PET, V);

        assertThat(jobs).as("다시 하면 되는 실패는 재시도가 남아 있어야 한다").hasSize(MAX_ATTEMPTS);
        verify(runner, times(MAX_ATTEMPTS)).run(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("★ 재시도가 성공하면 실패로 끝내지 않는다 — 두 번째 판이 통과하는 흔한 경우")
    void secondAttemptSucceeding() {
        alreadyAttempted(0);
        GenJob job = failedJob(GenErrorCode.UNKNOWN);
        when(runner.run(anyLong(), any(), any(), any()))
                .thenReturn(RunResult.failed(null, BigDecimal.ZERO, GenErrorCode.UNKNOWN))
                .thenReturn(RunResult.ok(new StepContext(PET, "여울", null, V), BigDecimal.ZERO));

        service.hatch(job.getId(), PET, V);

        assertThat(jobs).hasSize(2);
        verify(recorder, never()).markPetFailed(anyLong());
    }

    @Test
    @DisplayName("★★ 상한(5)에 닿으면 새 job 을 안 만들고 실패로 끝낸다 — 여기가 새면 같은 그림에 돈이 한 번 더")
    void atTheLimitNothingIsQueuedAgain() {
        alreadyAttempted(4);                       // 이미 네 번 구웠다 + 지금 것 = 5
        GenJob job = failedJob(GenErrorCode.UNKNOWN);
        runnerAlwaysFails(GenErrorCode.UNKNOWN);

        service.hatch(job.getId(), PET, V);

        assertThat(jobs).as("새 job 이 없다").hasSize(1);
        verify(runner, times(1)).run(anyLong(), any(), any(), any());
        verify(recorder).markPetFailed(PET);
    }

    @Test
    @DisplayName("★ 상한을 넘긴 값(6)도 같다 — 부등호가 == 이면 여기가 새어 영원히 다시 굽는다")
    void beyondTheLimitIsTheSame() {
        alreadyAttempted(5);
        GenJob job = failedJob(GenErrorCode.UNKNOWN);
        runnerAlwaysFails(GenErrorCode.UNKNOWN);

        service.hatch(job.getId(), PET, V);

        assertThat(jobs).hasSize(1);
        verify(runner, times(1)).run(anyLong(), any(), any(), any());
        verify(recorder).markPetFailed(PET);
    }

    @Test
    @DisplayName("★★ 거부(MODERATION)로 실패하면 문단과 문단에 기대는 단계를 폐기하고 다시 만든다")
    void moderationDiscardsIdentityAndItsDependents() {
        alreadyAttempted(0);
        GenJob job = failedJob(GenErrorCode.MODERATION_BLOCKED);
        runnerAlwaysFails(GenErrorCode.MODERATION_BLOCKED);

        service.hatch(job.getId(), PET, V);

        List<String> dependents = registry.identityDependents(GenKind.HATCH, V);
        assertThat(dependents).as("폐기 목록이 비어 있으면 이 시험은 아무것도 안 본다").isNotEmpty();
        // ★ 거부가 이어지면 시도마다 다시 버린다(상한 5 → 재시도 4번 = 4번 폐기)
        for (String step : dependents) {
            verify(recorder, times(MAX_ATTEMPTS - 1)).discardSucceeded(PET, GenKind.HATCH, step);
        }
    }

    @Test
    @DisplayName("거부가 아닌 실패는 폐기하지 않는다 — 이미 성공한 유료 단계를 버리면 돈을 두 번 쓴다")
    void nonModerationKeepsSucceededSteps() {
        alreadyAttempted(0);
        GenJob job = failedJob(GenErrorCode.TIMEOUT);
        runnerAlwaysFails(GenErrorCode.TIMEOUT);

        service.hatch(job.getId(), PET, V);

        verify(recorder, never()).discardSucceeded(anyLong(), any(), anyString());
    }

    // ── 2026-10-07 — 어느 격자를 버리나 ───────────────────────────────────

    private HatchService serviceWith(boolean onlyFailedGrid, boolean onPostprocessCrash) {
        ZzalPetRepository petRepository = mock(ZzalPetRepository.class);
        ZzalPet pet = ZzalPet.draft(1L, "images/zzal/src", T0);
        pet.character("여울", null, null, null, null, null, T0);
        when(petRepository.findById(PET)).thenReturn(Optional.of(pet));
        return new HatchService(runner, recorder, jobRepository, registry, petRepository,
                quotaBreaker, blockLog, mock(ZzalAlerts.class), MAX_ATTEMPTS, mock(MotionSeeder.class),
                onlyFailedGrid, onPostprocessCrash, RejectedGridArchive.none());
    }

    private void failsOnceThenSucceeds(RunResult failure) {
        when(runner.run(anyLong(), any(), any(), any()))
                .thenReturn(failure)
                .thenReturn(RunResult.ok(new StepContext(PET, "여울", null, V), BigDecimal.ZERO));
    }

    @Test
    @DisplayName("★★ 게이트가 2층(grid2)만 막았으면 <b>그 한 장만</b> 버린다 — 멀쩡한 1층을 다시 굽지 않는다")
    void gridRejectedDiscardsOnlyThatGrid() {
        GenJob job = failedJob(GenErrorCode.UNKNOWN);
        failsOnceThenSucceeds(RunResult.failedWith(null, BigDecimal.ZERO, GenErrorCode.UNKNOWN,
                true, true, PostProcessStep_GRID2));

        service.hatch(job.getId(), PET, V);

        verify(recorder).discardSucceeded(PET, GenKind.HATCH, PostProcessStep_GRID2);
        verify(recorder, never()).discardSucceeded(PET, GenKind.HATCH, GRID);
        assertThat(jobs).hasSize(2);
    }

    @Test
    @DisplayName("★★ 후처리 예외(exit 1 — 빈 칸 등)도 그 격자를 버린다 — 전에는 같은 격자를 다시 잘라 또 죽었다")
    void postprocessCrashDiscardsThatGrid() {
        GenJob job = failedJob(GenErrorCode.UNKNOWN);
        failsOnceThenSucceeds(RunResult.failedWith(null, BigDecimal.ZERO, GenErrorCode.UNKNOWN,
                false, true, GRID));

        service.hatch(job.getId(), PET, V);

        verify(recorder).discardSucceeded(PET, GenKind.HATCH, GRID);
        verify(recorder, never()).discardSucceeded(PET, GenKind.HATCH, PostProcessStep_GRID2);
    }

    @Test
    @DisplayName("★ 어느 격자인지 모르면 둘 다 버린다")
    void unknownGridDiscardsBoth() {
        GenJob job = failedJob(GenErrorCode.UNKNOWN);
        failsOnceThenSucceeds(RunResult.failedWith(null, BigDecimal.ZERO, GenErrorCode.UNKNOWN,
                false, true, null));

        service.hatch(job.getId(), PET, V);

        verify(recorder).discardSucceeded(PET, GenKind.HATCH, GRID);
        verify(recorder).discardSucceeded(PET, GenKind.HATCH, PostProcessStep_GRID2);
    }

    @Test
    @DisplayName("★ 시간 초과는 아무것도 버리지 않고 다시 굽는다")
    void timeoutRetriesWithoutDiscard() {
        GenJob job = failedJob(GenErrorCode.TIMEOUT);
        failsOnceThenSucceeds(RunResult.failed(null, BigDecimal.ZERO, GenErrorCode.TIMEOUT));

        service.hatch(job.getId(), PET, V);

        verify(recorder, never()).discardSucceeded(anyLong(), any(), anyString());
        assertThat(jobs).hasSize(2);
        verify(recorder, never()).markPetFailed(anyLong());
    }

    @Test
    @DisplayName("스위치 discard-on-postprocess-crash=false — 후처리 예외는 옛 동작대로 폐기하지 않는다")
    void postprocessCrashSwitchOff() {
        service = serviceWith(true, false);
        GenJob job = failedJob(GenErrorCode.UNKNOWN);
        failsOnceThenSucceeds(RunResult.failedWith(null, BigDecimal.ZERO, GenErrorCode.UNKNOWN,
                false, true, GRID));

        service.hatch(job.getId(), PET, V);

        verify(recorder, never()).discardSucceeded(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("스위치 discard-only-failed-grid=false — 게이트 거부면 옛 동작대로 두 장 다 버린다")
    void onlyFailedGridSwitchOff() {
        service = serviceWith(false, true);
        GenJob job = failedJob(GenErrorCode.UNKNOWN);
        failsOnceThenSucceeds(RunResult.failedWith(null, BigDecimal.ZERO, GenErrorCode.UNKNOWN,
                true, true, PostProcessStep_GRID2));

        service.hatch(job.getId(), PET, V);

        verify(recorder).discardSucceeded(PET, GenKind.HATCH, GRID);
        verify(recorder).discardSucceeded(PET, GenKind.HATCH, PostProcessStep_GRID2);
    }

    @Test
    @DisplayName("★ 관리자 재굽기(attempt 를 1 부터 다시 매김)는 옛 실패 job 이 많아도 상한까지 다시 굽는다")
    void rehatchStartsAFreshCount() {
        alreadyAttempted(0);
        // 표에는 옛 실패 job 이 7개 있다고 친다 — 예전처럼 전체 수를 셌다면 한 번도 다시 못 굽는다
        when(jobRepository.countByPetIdAndKind(eq(PET), eq(GenKind.HATCH))).thenAnswer(inv -> 7L + jobs.size());
        GenJob job = failedJob(GenErrorCode.UNKNOWN);
        runnerAlwaysFails(GenErrorCode.UNKNOWN);

        service.hatch(job.getId(), PET, V);

        verify(runner, times(MAX_ATTEMPTS)).run(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("★★ 관리자 재굽기 job(attempt=0)은 1 로 읽혀 상한(5)까지 굽고, 재시도는 attempt 2~ — 사람 상한 셈(attempt=1)에 안 들어간다")
    void adminRehatchJobCountsFromOneAndRetriesAreNotFirstAttempts() {
        GenJob job = jobRepository.save(GenJob.start(PET, GenKind.HATCH, GenJob.ADMIN_REHATCH_ATTEMPT, V, T0));
        job.markRunning(T0);
        job.fail(GenErrorCode.UNKNOWN, BigDecimal.ZERO, T0);
        runnerAlwaysFails(GenErrorCode.UNKNOWN);

        service.hatch(job.getId(), PET, V);

        verify(runner, times(MAX_ATTEMPTS)).run(anyLong(), any(), any(), any());
        assertThat(jobs.stream().map(GenJob::getAttempt).toList()).containsExactly(0, 2, 3, 4, 5);
        assertThat(jobs.stream().filter(j -> j.getAttempt() == 1).count())
                .as("사람이 시작한 부화로 세는 줄(attempt=1)이 하나도 없어야 한다").isZero();
    }

    @Test
    @DisplayName("★★ 사람이 시작한 부화가 재시도 3번을 거쳐도 attempt=1 인 줄은 하나뿐이다")
    void retriesLeaveOnlyOneFirstAttempt() {
        alreadyAttempted(0);
        GenJob job = failedJob(GenErrorCode.UNKNOWN);
        when(runner.run(anyLong(), any(), any(), any()))
                .thenReturn(RunResult.failed(null, BigDecimal.ZERO, GenErrorCode.UNKNOWN))
                .thenReturn(RunResult.failed(null, BigDecimal.ZERO, GenErrorCode.UNKNOWN))
                .thenReturn(RunResult.failed(null, BigDecimal.ZERO, GenErrorCode.UNKNOWN))
                .thenReturn(RunResult.ok(new StepContext(PET, "여울", null, V), BigDecimal.ZERO));

        service.hatch(job.getId(), PET, V);

        assertThat(jobs).hasSize(4);
        assertThat(jobs.stream().filter(j -> j.getAttempt() == 1).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("★★ 버리는 격자는 버리기 직전에 rejected/{jobId}-grid2.png 로 보존한다 — 재시도가 같은 키에 덮어쓴다(2026-10-07)")
    void discardedGridIsPreservedFirst() {
        RejectedGridArchive archive = mock(RejectedGridArchive.class);
        ZzalPetRepository petRepository = mock(ZzalPetRepository.class);
        ZzalPet pet = ZzalPet.draft(1L, "images/zzal/src", T0);
        pet.character("여울", null, null, null, null, null, T0);
        when(petRepository.findById(PET)).thenReturn(Optional.of(pet));
        service = new HatchService(runner, recorder, jobRepository, registry, petRepository,
                quotaBreaker, blockLog, mock(ZzalAlerts.class), MAX_ATTEMPTS, mock(MotionSeeder.class),
                true, true, archive);

        GenStepRecord grid2 = GenStepRecord.start(41L, 3, PostProcessStep_GRID2, T0);
        grid2.succeed("images/zzal/pets/7/grid2.png", null, "gpt-image-2", BigDecimal.ZERO, T0);
        GenStepRecord grid1 = GenStepRecord.start(41L, 2, GRID, T0);
        grid1.succeed("images/zzal/pets/7/grid.png", null, "gpt-image-2", BigDecimal.ZERO, T0);
        when(recorder.loadSucceeded(PET, GenKind.HATCH)).thenReturn(List.of(grid1, grid2));

        GenJob job = failedJob(GenErrorCode.UNKNOWN);
        failsOnceThenSucceeds(RunResult.failedWith(null, BigDecimal.ZERO, GenErrorCode.UNKNOWN,
                true, true, PostProcessStep_GRID2));

        service.hatch(job.getId(), PET, V);

        verify(archive, times(1)).preserve(PET, grid2);
        verify(archive, never()).preserve(PET, grid1);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(archive, recorder);
        order.verify(archive).preserve(PET, grid2);
        order.verify(recorder).discardSucceeded(PET, GenKind.HATCH, PostProcessStep_GRID2);
        assertThat(RejectedGridArchive.keyOf(PET, 41L, PostProcessStep_GRID2))
                .isEqualTo("images/zzal/pets/7/rejected/41-grid2.png");
    }

    @Test
    @DisplayName("★★ 실패가 확정되는 마지막 시도의 거부 격자도 rejected/ 로 보존한 뒤 실패로 끝낸다(2026-10-08 운영 job 119·130)")
    void lastAttemptRejectedGridIsPreservedBeforeFailing() {
        RejectedGridArchive archive = mock(RejectedGridArchive.class);
        ZzalPetRepository petRepository = mock(ZzalPetRepository.class);
        ZzalPet pet = ZzalPet.draft(1L, "images/zzal/src", T0);
        pet.character("여울", null, null, null, null, null, T0);
        when(petRepository.findById(PET)).thenReturn(Optional.of(pet));
        service = new HatchService(runner, recorder, jobRepository, registry, petRepository,
                quotaBreaker, blockLog, mock(ZzalAlerts.class), MAX_ATTEMPTS, mock(MotionSeeder.class),
                true, true, archive);

        GenStepRecord grid = GenStepRecord.start(130L, 2, GRID, T0);
        grid.succeed("images/zzal/pets/7/grid.png", null, "gpt-image-2", BigDecimal.ZERO, T0);
        when(recorder.loadSucceeded(PET, GenKind.HATCH)).thenReturn(List.of(grid));

        // 이미 4번 구웠고 지금이 5번째(마지막) — 재시도가 남아 있지 않다
        alreadyAttempted(MAX_ATTEMPTS - 1);
        GenJob job = failedJob(GenErrorCode.UNKNOWN);
        when(runner.run(anyLong(), any(), any(), any()))
                .thenReturn(RunResult.failedWith(null, BigDecimal.ZERO, GenErrorCode.UNKNOWN, true, false, GRID));

        service.hatch(job.getId(), PET, V);

        assertThat(jobs).as("재시도 job 은 만들지 않는다").hasSize(1);
        verify(archive, times(1)).preserve(PET, grid);
        verify(recorder, never()).discardSucceeded(anyLong(), any(), anyString());
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(archive, recorder);
        order.verify(archive).preserve(PET, grid);
        order.verify(recorder).markPetFailed(PET);
    }

    private static final String GRID = com.lore.zzal.generation.steps.GridStep.NAME;
    private static final String PostProcessStep_GRID2 = com.lore.zzal.generation.steps.PostProcessStep.GRID2;

    @Test
    @DisplayName("첫 판이 성공하면 재시도도 실패 확정도 없다")
    void successOnTheFirstTryDoesNothingElse() {
        alreadyAttempted(0);
        GenJob job = failedJob(GenErrorCode.UNKNOWN);
        when(runner.run(anyLong(), any(), any(), any()))
                .thenReturn(RunResult.ok(new StepContext(PET, "여울", null, V), BigDecimal.ZERO));

        service.hatch(job.getId(), PET, V);

        assertThat(jobs).hasSize(1);
        verify(runner, times(1)).run(anyLong(), any(), any(), any());
        verify(recorder, never()).markPetFailed(anyLong());
    }
}
