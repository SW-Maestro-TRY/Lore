package com.lore.zzal.generation;

import com.lore.zzal.alert.ZzalAlerts;
import com.lore.zzal.generation.steps.MotionGridStep;
import com.lore.zzal.generation.steps.MotionPostStep;
import com.lore.zzal.guard.HatchBlockLog;
import com.lore.zzal.guard.QuotaBreaker;
import com.lore.zzal.motion.MotionSeeder;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalPetRepository;
import org.junit.jupiter.api.AfterEach;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1 검토 표 1번(막음): 바깥 끼어들기(배포 때 부화 풀 종료 등) 뒤에는 <b>다시 굽지도, 실패로 닫지도 않는다</b>.
 *
 * <h3>★ 왜 필요한가</h3>
 * 러너는 바깥 끊김을 받으면 기록을 다 남긴 뒤 끊김 표시를 되살린다(설계 3-8). 그런데 재시도 고리가 그
 * 표시를 보지 않으면, 새 잡을 만들어 다시 러너를 부르고 → 새 단계의 {@code future.get} 이 곧바로
 * InterruptedException → 또 INTERRUPTED … 를 밀리초 안에 되풀이해 시도 상한을 다 쓰고 펫을 FAILED 로 닫는다.
 * 펫은 HATCHING 으로 남아야 재기동 뒤 기동 복구({@link StuckHatchRecovery})가 이어 굽는다.
 */
@DisplayName("부화 — 바깥 끼어들기 뒤 재시도·실패 확정 없이 돌아간다")
class HatchInterruptedNoRetryTest {

    private static final Long PET = 7L;
    private static final String V = "v1";
    private static final Instant T0 = Instant.parse("2026-11-11T03:00:00Z");

    private GenerationRunner runner;
    private GenerationRecorder recorder;
    private ZzalAlerts alerts;
    private HatchService service;
    private final List<GenJob> jobs = new ArrayList<>();
    private final AtomicLong jobIds = new AtomicLong();

    @BeforeEach
    void setUp() {
        runner = mock(GenerationRunner.class);
        recorder = mock(GenerationRecorder.class);
        alerts = mock(ZzalAlerts.class);
        GenJobRepository jobRepository = mock(GenJobRepository.class);
        ZzalPetRepository petRepository = mock(ZzalPetRepository.class);

        ZzalPet pet = ZzalPet.draft(1L, "images/zzal/src", T0);
        pet.character("여울", null, null, null, null, null, T0);
        when(petRepository.findById(PET)).thenReturn(Optional.of(pet));
        when(recorder.loadSucceeded(anyLong(), any(), anyString())).thenReturn(List.of());
        when(jobRepository.save(any())).thenAnswer(inv -> {
            GenJob job = inv.getArgument(0);
            ReflectionTestUtils.setField(job, "id", jobIds.incrementAndGet());
            jobs.add(job);
            return job;
        });
        when(jobRepository.findById(anyLong())).thenAnswer(inv ->
                jobs.stream().filter(j -> inv.getArgument(0).equals(j.getId())).findFirst());

        PipelineRegistry registry = new PipelineRegistry(
                StepMocks.sheet(), StepMocks.identity(),
                StepMocks.grid(), StepMocks.grid2(), StepMocks.post(),
                mock(MotionGridStep.class), mock(MotionPostStep.class), "v1", "v1");
        service = new HatchService(runner, recorder, jobRepository, registry, petRepository,
                new QuotaBreaker(), mock(HatchBlockLog.class), alerts, 3, mock(MotionSeeder.class));

        GenJob job = jobRepository.save(GenJob.start(PET, GenKind.HATCH, 1, V, T0));
        job.markRunning(T0);
        // 러너가 끊김으로 잡을 닫은 상태(실제 러너는 failJob(INTERRUPTED) 를 적는다).
        job.fail(GenErrorCode.INTERRUPTED, BigDecimal.ZERO, T0);
    }

    @AfterEach
    void clearInterrupt() {
        // JUnit 스레드에 끊김 표시를 남기지 않는다.
        Thread.interrupted();
    }

    @Test
    @DisplayName("★★ 러너가 INTERRUPTED 를 내고 끊김 표시를 되살리면 — 새 잡 0 · 실패 확정 0 · 실패 알림 0")
    void outerInterruptDoesNotRetryOrFail() {
        when(runner.run(anyLong(), any(), any(), any())).thenAnswer(inv -> {
            Thread.currentThread().interrupt();
            return RunResult.failed(null, BigDecimal.ZERO, GenErrorCode.INTERRUPTED);
        });

        service.hatch(1L, PET, V);
        boolean stillInterrupted = Thread.currentThread().isInterrupted();

        assertThat(jobs).as("끊긴 뒤 새 잡을 만들면 죽어 가는 서버에서 재시도가 돈다").hasSize(1);
        verify(runner, times(1)).run(anyLong(), any(), any(), any());
        verify(recorder, never()).markPetFailed(PET);
        verify(alerts, never()).hatchFinallyFailed(any(), any());
        assertThat(stillInterrupted).as("끊김 표시는 부른 쪽(실행기 종료)이 볼 수 있게 남긴다").isTrue();
    }

    @Test
    @DisplayName("★ 결과 코드만 INTERRUPTED(표시는 없음)여도 다시 굽지 않는다 — 기동 복구가 받는다")
    void interruptedCodeAloneDoesNotRetry() {
        when(runner.run(anyLong(), any(), any(), any()))
                .thenAnswer(inv -> RunResult.failed(null, BigDecimal.ZERO, GenErrorCode.INTERRUPTED));

        service.hatch(1L, PET, V);

        assertThat(jobs).hasSize(1);
        verify(recorder, never()).markPetFailed(PET);
        verify(alerts, never()).hatchFinallyFailed(any(), any());
    }

    @Test
    @DisplayName("★ 끊김 표시만 남고 다른 실패 코드여도(단계가 TIMEOUT 등) 재시도하지 않는다")
    void interruptFlagAloneDoesNotRetry() {
        when(runner.run(anyLong(), any(), any(), any())).thenAnswer(inv -> {
            Thread.currentThread().interrupt();
            return RunResult.failed(null, BigDecimal.ZERO, GenErrorCode.TIMEOUT);
        });

        service.hatch(1L, PET, V);

        assertThat(jobs).hasSize(1);
        verify(recorder, never()).markPetFailed(PET);
    }
}
