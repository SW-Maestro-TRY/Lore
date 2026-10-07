package com.lore.zzal.generation;

import com.lore.zzal.PetFixture;
import com.lore.zzal.alert.ZzalAlerts;
import com.lore.zzal.generation.steps.Layer2PostStep;
import com.lore.zzal.generation.steps.MotionGridStep;
import com.lore.zzal.generation.steps.MotionPostStep;
import com.lore.zzal.generation.steps.PostProcessStep;
import com.lore.zzal.motion.MotionSeeder;
import com.lore.zzal.pet.Layer2Status;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalPetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1층 우선 부화 + 2층 배경 굽기(#696).
 *
 * <ul>
 *   <li>부화 완료(ALIVE)는 1층에서 — 살아나는 같은 커밋에서 2층이 PENDING 이 되고, 2층 작업이 넘어간다.</li>
 *   <li>2층 작업은 최대 N회. 거부 격자는 보존하고 그 격자만 버린다. 다 쓰면 FAILED + 경보.</li>
 *   <li>바깥 한도(429)면 다시 굽지 않는다.</li>
 * </ul>
 */
@DisplayName("2층 배경 굽기 — 등록·재시도·FAILED 전이")
class Layer2BakeTest {

    private static final Long PET = 7L;
    private static final Instant T0 = Instant.parse("2026-10-08T03:00:00Z");

    private ZzalPetRepository pets;
    private ZzalPet pet;
    private GenerationRunner runner;
    private GenerationRecorder recorder;
    private Layer2Recorder layer2;
    private GenJobRepository jobs;
    private RejectedGridArchive archive;
    private ZzalAlerts alerts;
    private Layer2Service service;
    private final AtomicInteger attempts = new AtomicInteger();

    @BeforeEach
    void setUp() {
        pets = mock(ZzalPetRepository.class);
        pet = PetFixture.hatching(1L, "여울", null, "images/zzal/src", T0);
        pet.markAlive("sheet.png", "생김새 문단", T0);
        pet.resetLayer2(T0);
        pet.markBasicBaked(1);
        when(pets.findById(PET)).thenReturn(Optional.of(pet));

        runner = mock(GenerationRunner.class);
        recorder = mock(GenerationRecorder.class);
        when(recorder.loadSucceeded(anyLong(), any(), anyString())).thenReturn(List.of());
        when(recorder.loadSucceeded(anyLong(), any())).thenReturn(List.of());
        jobs = mock(GenJobRepository.class);
        when(jobs.save(any())).thenAnswer(inv -> inv.getArgument(0));
        archive = mock(RejectedGridArchive.class);
        alerts = mock(ZzalAlerts.class);

        // 2층 기록은 진짜 펫에 반영되게 잇는다(상태 전이를 눈으로 본다).
        layer2 = mock(Layer2Recorder.class);
        when(layer2.claim(eq(PET), any())).thenAnswer(inv -> pet.startLayer2Attempt(T0));
        when(layer2.attempts(PET)).thenAnswer(inv -> pet.getLayer2Attempts());
        doAnswer(inv -> { pet.nextLayer2Attempt(inv.getArgument(1), T0); return null; })
                .when(layer2).next(eq(PET), any(), any());
        doAnswer(inv -> { pet.markBasicBaked(inv.getArgument(1)); pet.markLayer2Ready(T0); return null; })
                .when(layer2).ready(eq(PET), any(Integer.class), any());
        doAnswer(inv -> { pet.markLayer2Failed(inv.getArgument(1), T0); return null; })
                .when(layer2).failed(eq(PET), any(), any());

        PipelineRegistry registry = new PipelineRegistry(StepMocks.sheet(), StepMocks.identity(),
                StepMocks.grid(), StepMocks.grid2(), StepMocks.post(),
                mock(MotionGridStep.class), mock(MotionPostStep.class), "v1", "v1");
        service = new Layer2Service(runner, recorder, layer2, jobs, registry, pets, archive, alerts,
                Runnable::run, 5);
    }

    private RunResult ok(StepContext ctx, int round) {
        ctx.putText(Layer2PostStep.NAME, String.valueOf(round));
        return RunResult.ok(ctx, new BigDecimal("0.18"));
    }

    private static RunResult rejected(StepContext ctx) {
        return RunResult.failedWith(ctx, new BigDecimal("0.18"), GenErrorCode.UNKNOWN, true, true,
                PostProcessStep.GRID2).withDetail("postprocess2: [격자=grid2] 후처리 실패(exit 1)\n로그\nGRID_STRUCTURE_INVALID 열 개수 5 != 4");
    }

    @Test
    @DisplayName("★ 한 번에 성공 — READY · 판이 올라간다(1층 판 1 → 2) · 경보 없음")
    void succeedsFirstTime() {
        when(runner.run(any(), any(), any(), any())).thenAnswer(inv -> ok(inv.getArgument(1), 2));

        service.bake(PET);

        assertThat(pet.getLayer2Status()).isEqualTo(Layer2Status.READY);
        assertThat(pet.getBasicRound()).isEqualTo(2);
        assertThat(pet.getLayer2Attempts()).isEqualTo(1);
        verify(runner, times(1)).run(any(), any(), any(), any());
        verify(alerts, never()).layer2Failed(any(), any(), any());
    }

    @Test
    @DisplayName("★ 시트·문단은 부화 결과를 그대로 쓴다 — 다시 굽지 않는다")
    void reusesSheetAndIdentity() {
        List<StepContext> seen = new ArrayList<>();
        when(runner.run(any(), any(), any(), any())).thenAnswer(inv -> {
            seen.add(inv.getArgument(1));
            return ok(inv.getArgument(1), 2);
        });

        service.bake(PET);

        assertThat(seen.get(0).image("sheet")).isEqualTo("sheet.png");
        assertThat(seen.get(0).text("identity")).isEqualTo("생김새 문단");
    }

    @Test
    @DisplayName("★★ 게이트 거부 두 번 뒤 성공 — 거부 격자는 보존하고 그 격자만 버린다, 3번째에 READY")
    void retriesAfterGridRejection() {
        AtomicInteger call = new AtomicInteger();
        when(runner.run(any(), any(), any(), any())).thenAnswer(inv ->
                call.incrementAndGet() <= 2 ? rejected(inv.getArgument(1)) : ok(inv.getArgument(1), 2));
        GenStepRecord grid2 = GenStepRecord.start(11L, 0, PostProcessStep.GRID2, T0);
        grid2.succeed("images/zzal/pets/7/grid2.png", null, "m", BigDecimal.ZERO, T0);
        when(recorder.loadSucceeded(PET, GenKind.LAYER2)).thenReturn(List.of(grid2));

        service.bake(PET);

        assertThat(pet.getLayer2Status()).isEqualTo(Layer2Status.READY);
        assertThat(pet.getLayer2Attempts()).isEqualTo(3);
        verify(archive, times(2)).preserve(PET, grid2);
        verify(recorder, times(2)).discardSucceeded(PET, GenKind.LAYER2, PostProcessStep.GRID2);
        // 시도마다 LAYER2 job 한 줄 — 사람 상한(HATCH·attempt=1)에 안 섞인다.
        verify(jobs, times(3)).save(org.mockito.ArgumentMatchers.argThat(j -> j.getKind() == GenKind.LAYER2));
    }

    @Test
    @DisplayName("★★ 다섯 번 다 거부 — FAILED · 마지막 사유는 게이트 줄 · 경보 한 번 · 마지막 격자도 보존(버리지는 않음)")
    void failsAfterMaxAttempts() {
        when(runner.run(any(), any(), any(), any())).thenAnswer(inv -> rejected(inv.getArgument(1)));
        GenStepRecord grid2 = GenStepRecord.start(11L, 0, PostProcessStep.GRID2, T0);
        grid2.succeed("images/zzal/pets/7/grid2.png", null, "m", BigDecimal.ZERO, T0);
        when(recorder.loadSucceeded(PET, GenKind.LAYER2)).thenReturn(List.of(grid2));

        service.bake(PET);

        assertThat(pet.getLayer2Status()).isEqualTo(Layer2Status.FAILED);
        assertThat(pet.getLayer2Attempts()).isEqualTo(5);
        assertThat(pet.getLayer2LastError()).contains("GRID_STRUCTURE_INVALID");
        verify(runner, times(5)).run(any(), any(), any(), any());
        verify(archive, times(5)).preserve(PET, grid2);
        verify(recorder, times(4)).discardSucceeded(PET, GenKind.LAYER2, PostProcessStep.GRID2);
        verify(alerts, times(1)).layer2Failed(eq(PET), any(), any());
        // 사용자에게는 실패 화면이 없다 — 펫은 살아 있다.
        assertThat(pet.isAlive()).isTrue();
    }

    @Test
    @DisplayName("★ 바깥 한도(429) — 다시 굽지 않고 바로 FAILED")
    void quotaStopsImmediately() {
        when(runner.run(any(), any(), any(), any())).thenAnswer(inv ->
                RunResult.quotaBlocked(inv.getArgument(1), BigDecimal.ZERO, GenErrorCode.UNKNOWN));

        service.bake(PET);

        assertThat(pet.getLayer2Status()).isEqualTo(Layer2Status.FAILED);
        verify(runner, times(1)).run(any(), any(), any(), any());
    }

    @Test
    @DisplayName("★ 이미 굽는 중·READY·FAILED 는 집지 않는다 — 부화 완료 훅이 두 번 불려도 한 번만 굽는다")
    void claimsOnlyPending() {
        when(runner.run(any(), any(), any(), any())).thenAnswer(inv -> ok(inv.getArgument(1), 2));
        service.bake(PET);
        service.bake(PET);                       // READY — 건너뜀
        verify(runner, times(1)).run(any(), any(), any(), any());

        pet.flagLayer2("빈 칸", T0);             // FAILED — 저절로 다시 굽지 않는다
        service.bake(PET);
        verify(runner, times(1)).run(any(), any(), any(), any());

        pet.resetLayer2(T0);                     // 관리자 재시도 — PENDING 으로 돌리면 다시 집힌다
        service.bake(PET);
        verify(runner, times(2)).run(any(), any(), any(), any());
    }

    @Test
    @DisplayName("★ 실패 사유 요약 — 파이썬 로그 전체가 아니라 게이트 표식 줄을 집는다")
    void summarizesGateLine() {
        String s = Layer2Service.summarize(rejected(new StepContext(PET, "여울", null, "v1")));
        assertThat(s).startsWith("postprocess2: [격자=grid2]").contains("열 개수 5 != 4").doesNotContain("\n");
    }

    @Test
    @DisplayName("★★ 부화 완료(1층) — 살아나는 같은 커밋에서 2층 PENDING, 그리고 2층 작업이 넘어간다")
    void hatchCompletionSchedulesLayer2() {
        ZzalPet hatching = PetFixture.hatching(1L, "여울", null, "images/zzal/src", T0);
        ZzalPetRepository repo = mock(ZzalPetRepository.class);
        when(repo.findById(PET)).thenReturn(Optional.of(hatching));
        GenerationRecorder real = new GenerationRecorder(mock(GenJobRepository.class),
                mock(GenStepRecordRepository.class), repo);
        GenerationRecorder spy = org.mockito.Mockito.spy(real);
        List<GenStepRecord> done = new ArrayList<>();
        for (String n : List.of("sheet", "identity", "grid", "postprocess")) {
            GenStepRecord r = GenStepRecord.start(1L, 0, n, T0);
            r.succeed(n + ".png", "identity".equals(n) ? "문단" : null, "m", BigDecimal.ZERO, T0);
            done.add(r);
        }
        org.mockito.Mockito.doReturn(done).when(spy).loadSucceeded(anyLong(), any(), anyString());
        PipelineRegistry registry = new PipelineRegistry(StepMocks.sheet(), StepMocks.identity(),
                StepMocks.grid(), StepMocks.grid2(), StepMocks.post(),
                mock(MotionGridStep.class), mock(MotionPostStep.class), "v1", "v1");
        HatchService hatch = new HatchService(runner, spy, jobs, registry, repo,
                new com.lore.zzal.guard.QuotaBreaker(), mock(com.lore.zzal.guard.HatchBlockLog.class),
                alerts, 5, mock(MotionSeeder.class));
        Layer2Service l2 = mock(Layer2Service.class);
        hatch.setLayer2Service(l2);

        // 1층 네 단계(grid2 없음)로 완료된다.
        assertThat(hatch.completeIfReady(PET, "v1")).isTrue();
        assertThat(hatching.isAlive()).isTrue();
        assertThat(hatching.getLayer2Status()).isEqualTo(Layer2Status.PENDING);
        assertThat(hatching.isLayer2Ready()).isFalse();
        verify(l2).schedule(PET);
    }
}
