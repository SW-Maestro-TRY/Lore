package com.lore.zzal.generation;

import com.lore.zzal.generation.steps.MotionGridStep;
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
@DisplayName("부화 재시도 — 상한 2의 경계")
class HatchRetryTest {

    private static final Long PET = 7L;
    private static final String V = "v2";
    private static final Instant T0 = Instant.parse("2026-09-11T03:00:00Z");
    private static final int MAX_ATTEMPTS = 2;

    private GenerationRunner runner;
    private GenerationRecorder recorder;
    private GenJobRepository jobRepository;
    private PipelineRegistry registry;
    private HatchService service;

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
                mock(MotionGridStep.class), mock(MotionPostStep.class), "v2", "v1", path -> true);

        service = new HatchService(runner, recorder, jobRepository, registry, petRepository,
                MAX_ATTEMPTS, mock(MotionSeeder.class));
    }

    /** 이 펫이 이미 {@code n} 번 구워진 상태로 둔다(그 job 들은 표에 있고 이 시험이 세지 않는다). */
    private void alreadyAttempted(long n) {
        alreadyAttempted = n;
    }

    /** 지금 도는 그 job 한 줄. 실행기가 실패로 끝냈다고 친다. */
    private GenJob failedJob(GenErrorCode code) {
        GenJob job = jobRepository.save(GenJob.start(PET, GenKind.HATCH, 1, V, T0));
        job.markRunning(T0);
        job.fail(code, BigDecimal.ZERO, T0);
        return job;
    }

    private void runnerAlwaysFails(GenErrorCode code) {
        when(runner.run(anyLong(), any(), any(), any()))
                .thenAnswer(inv -> RunResult.failed(null, BigDecimal.ZERO, code));
    }

    @Test
    @DisplayName("★ 첫 실패(시도 1회째)는 한 번 더 굽는다 — 새 job 1개, 실행기 2회, 실패 확정 없음")
    void firstFailureRetriesOnce() {
        alreadyAttempted(0);
        GenJob job = failedJob(GenErrorCode.UNKNOWN);
        runnerAlwaysFails(GenErrorCode.UNKNOWN);

        service.hatch(job.getId(), PET, V);

        assertThat(jobs).as("처음 것 + 재시도 하나").hasSize(2);
        assertThat(jobs.get(1).getAttempt()).isEqualTo(2);
        verify(runner, times(2)).run(anyLong(), any(), any(), any());
        verify(recorder).markPetFailed(PET);      // 재시도까지 실패했으므로 여기서 끝낸다
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
    @DisplayName("★★ 상한(2)에 닿으면 새 job 을 안 만들고 실패로 끝낸다 — 여기가 새면 같은 그림에 돈이 세 번")
    void atTheLimitNothingIsQueuedAgain() {
        alreadyAttempted(1);                       // 이미 한 번 구웠다 + 지금 것 = 2
        GenJob job = failedJob(GenErrorCode.UNKNOWN);
        runnerAlwaysFails(GenErrorCode.UNKNOWN);

        service.hatch(job.getId(), PET, V);

        assertThat(jobs).as("새 job 이 없다").hasSize(1);
        verify(runner, times(1)).run(anyLong(), any(), any(), any());
        verify(recorder).markPetFailed(PET);
    }

    @Test
    @DisplayName("★ 상한을 넘긴 값(3)도 같다 — 부등호가 == 이면 여기가 새어 영원히 다시 굽는다")
    void beyondTheLimitIsTheSame() {
        alreadyAttempted(2);
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
        for (String step : dependents) {
            verify(recorder).discardSucceeded(PET, GenKind.HATCH, step);
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
