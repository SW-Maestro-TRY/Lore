package com.lore.zzal.motion;

import com.lore.zzal.generation.GenJob;
import com.lore.zzal.generation.GenJobRepository;
import com.lore.zzal.generation.GenKind;
import com.lore.zzal.generation.GenStepRecord;
import com.lore.zzal.generation.GenStepRecordRepository;
import com.lore.zzal.generation.GenerationRecorder;
import com.lore.zzal.generation.GenerationRunner;
import com.lore.zzal.generation.GenerationStep;
import com.lore.zzal.generation.PipelineRegistry;
import com.lore.zzal.generation.RunResult;
import com.lore.zzal.generation.StepContext;
import com.lore.zzal.generation.steps.MotionGridStep;
import com.lore.zzal.generation.steps.MotionPostStep;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalPetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

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
 * 모션 굽기 테스트.
 *
 * ★ 여기서 지키는 것은 대부분 <b>조용히 틀리는</b> 것들이다 — 예외도 안 나고 로그도 정상인데
 *   결과만 잘못된 종류. 특히 두 가지가 그렇다.
 *
 *     1. 재시도 이어받기를 펫 단위로 찾으면 <b>다른 동작의 격자를 물려받는다</b>
 *     2. 출력 경로에 모션 번호가 없으면 <b>두 번째 동작이 첫 번째를 덮어쓴다</b>
 *
 *   둘 다 "모든 동작의 그림이 같다" 로 나타나는데, 그건 화면을 눈으로 봐야만 드러난다.
 */
@DisplayName("모션 — 굽기와 판정")
class MotionServiceTest {

    private static final Long PET_ID = 7L;
    private static final Long MOTION_ID = 42L;
    private static final String MOTION_NAME = "교감1_머리쓰다듬";

    private GenerationRunner runner;
    private MotionRecorder motionRecorder;
    private GenStepRecordRepository stepRepository;
    private ZzalMotionRepository motionRepository;
    private MotionGate gate;
    private MotionCatalog catalog;
    private GenJobRepository jobRepository;
    private ZzalPetRepository petRepository;
    private PipelineRegistry registry;
    private MotionService service;

    private ZzalMotion motion;

    @BeforeEach
    void setUp() {
        runner = mock(GenerationRunner.class);
        motionRecorder = mock(MotionRecorder.class);
        stepRepository = mock(GenStepRecordRepository.class);
        motionRepository = mock(ZzalMotionRepository.class);
        gate = mock(MotionGate.class);

        GenerationRecorder recorder = mock(GenerationRecorder.class);
        jobRepository = mock(GenJobRepository.class);
        petRepository = mock(ZzalPetRepository.class);
        registry = mock(PipelineRegistry.class);
        catalog = mock(MotionCatalog.class);

        motion = ZzalMotion.start(PET_ID, 3, MOTION_NAME, "v1");
        when(motionRepository.findById(MOTION_ID)).thenReturn(Optional.of(motion));

        ZzalPet pet = ZzalPet.hatch(1L, "여울", null, "images/zzal/src", Instant.now());
        pet.markAlive("images/zzal/sheet", "생김새 문단", Instant.now());
        when(petRepository.findById(any())).thenReturn(Optional.of(pet));

        when(jobRepository.save(any(GenJob.class))).thenAnswer(i -> i.getArgument(0));
        when(registry.steps(eq(GenKind.MOTION), anyString())).thenReturn(List.<GenerationStep>of());
        when(catalog.block(MOTION_NAME)).thenReturn("TASK: 머리를 쓰다듬는다");
        when(stepRepository.findSucceededByMotion(anyLong())).thenReturn(List.<GenStepRecord>of());

        service = new MotionService(runner, recorder, motionRecorder, jobRepository, stepRepository,
                motionRepository, petRepository, registry, catalog, gate, 1, 2);
    }

    /** 실행기가 성공했다고 답하게 만든다. */
    private void runnerSucceeds() {
        when(runner.run(any(), any(), any(), any())).thenAnswer(i -> {
            StepContext ctx = i.getArgument(1);
            ctx.putImage(MotionPostStep.NAME, ctx.outputPrefix() + "/motion.webp");
            return RunResult.ok(ctx, new BigDecimal("0.0985"));
        });
    }

    @Test
    @DisplayName("★ 잘 구워지면 열리는 게 아니라 검수 대기로 간다(검수 전 지급 제거)")
    void opensWhenBaked() {
        runnerSucceeds();
        when(gate.judge(anyString()))
                .thenReturn(new MotionGate.Verdict(GateVerdict.REVIEW, "게이트 미적용", "g0"));

        service.bake(MOTION_ID);

        verify(motionRecorder).toReview(eq(MOTION_ID), anyString(), any());
        verify(motionRecorder, never()).markFailed(anyLong());
    }

    @Test
    @DisplayName("★ 이어받기를 모션 단위로 찾는다 — 펫 단위면 다른 동작의 격자를 물려받는다")
    void resumesByMotionNotPet() {
        runnerSucceeds();
        when(gate.judge(anyString()))
                .thenReturn(new MotionGate.Verdict(GateVerdict.REVIEW, "", "g0"));

        service.bake(MOTION_ID);

        verify(stepRepository).findSucceededByMotion(MOTION_ID);
        // 펫 단위 조회는 한 번도 부르지 않아야 한다
        verify(stepRepository, never()).findSucceededByPet(anyLong(), any());
    }

    @Test
    @DisplayName("★ 출력 경로에 모션 번호가 들어간다 — 없으면 다음 동작이 앞의 것을 덮어쓴다")
    void outputPathIsPerMotion() {
        runnerSucceeds();
        when(gate.judge(anyString()))
                .thenReturn(new MotionGate.Verdict(GateVerdict.REVIEW, "", "g0"));

        service.bake(MOTION_ID);

        ArgumentCaptor<StepContext> ctx = ArgumentCaptor.forClass(StepContext.class);
        verify(runner).run(any(), ctx.capture(), any(), any());
        assertThat(ctx.getValue().outputPrefix()).contains("/motions/" + MOTION_ID);
    }

    @Test
    @DisplayName("시트와 정체성 문단은 부화 때 만든 것을 재사용한다")
    void reusesSheetAndIdentity() {
        runnerSucceeds();
        when(gate.judge(anyString()))
                .thenReturn(new MotionGate.Verdict(GateVerdict.REVIEW, "", "g0"));

        service.bake(MOTION_ID);

        ArgumentCaptor<StepContext> ctx = ArgumentCaptor.forClass(StepContext.class);
        verify(runner).run(any(), ctx.capture(), any(), any());
        assertThat(ctx.getValue().image(MotionGridStep.SHEET_IN)).isEqualTo("images/zzal/sheet");
        assertThat(ctx.getValue().text(MotionGridStep.IDENTITY_IN)).isEqualTo("생김새 문단");
        assertThat(ctx.getValue().text(MotionGridStep.MOTION_IN)).contains("머리를 쓰다듬는다");
    }

    @Test
    @DisplayName("★★ 게이트가 실패라 하면 검수 대기로 올리지 않고 맥미니로 넘긴다 — API 는 다시 안 부른다")
    void handsOverToLocalWhenGateFails() {
        runnerSucceeds();
        when(gate.judge(anyString()))
                .thenReturn(new MotionGate.Verdict(GateVerdict.FAIL, "잘림", "g0"));

        service.bake(MOTION_ID);

        verify(runner, times(1)).run(any(), any(), any(), any());   // 정본 = API 1회
        verify(motionRecorder, never()).toReview(anyLong(), anyString(), any());
        verify(motionRecorder).recordGate(eq(MOTION_ID), anyString(), any());   // 판정은 남긴다
        verify(motionRecorder).requestLocalRegen(MOTION_ID, 2);
    }

    @Test
    @DisplayName("★ 로컬 재생성 한도를 다 썼으면 그 밤은 FAILED — 다음 밤에 다시 오른다")
    void failsWhenLocalRegenExhausted() {
        when(runner.run(any(), any(), any(), any()))
                .thenAnswer(i -> RunResult.failed(i.getArgument(1), BigDecimal.ZERO, null));
        // MotionRecorder 가 한도 초과를 판정해 false 를 돌려준다(그 안에서 FAILED 를 찍는다)
        when(motionRecorder.requestLocalRegen(anyLong(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(false);

        service.bakeNow(MOTION_ID);

        verify(runner, times(1)).run(any(), any(), any(), any());
        verify(motionRecorder).requestLocalRegen(MOTION_ID, 2);
    }

    @Test
    @DisplayName("★★ 굽는 중 어떤 예외가 나도 FAILED 로 끝낸다 — 안 그러면 그 행이 영구 BAKING 이다")
    void anyExceptionEndsAsFailed() {
        // 지시문 파일이 없을 때 실제로 나던 예외(2026-09-05 리뷰 주입 INJ-C)
        when(catalog.block(MOTION_NAME))
                .thenThrow(new java.io.UncheckedIOException(new java.io.IOException("동작 지시문이 없습니다")));

        service.bakeNow(MOTION_ID);         // 예외가 이 밖으로 새어 나오면 안 된다

        verify(motionRecorder).markFailed(MOTION_ID);
        verify(motionRecorder, never()).toReview(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("★ 재생성 요청 기록마저 터져도 굽기 스레드는 조용히 끝난다(기동 복구가 회수한다)")
    void failureRecordFailureDoesNotEscape() {
        when(runner.run(any(), any(), any(), any()))
                .thenAnswer(i -> RunResult.failed(i.getArgument(1), BigDecimal.ZERO, null));
        org.mockito.Mockito.doThrow(new IllegalStateException("DB 끊김"))
                .when(motionRecorder).requestLocalRegen(anyLong(), org.mockito.ArgumentMatchers.anyInt());

        service.bakeNow(MOTION_ID);         // 예외 없이 돌아와야 한다

        verify(motionRecorder).requestLocalRegen(MOTION_ID, 2);
    }

    @Test
    @DisplayName("★★ 정본은 API 1회 — 딱 한 번 굽고, 못 구우면 맥미니(로컬)로 넘긴다")
    void canonicalSingleApiAttempt() {
        when(runner.run(any(), any(), any(), any()))
                .thenAnswer(i -> RunResult.failed(i.getArgument(1), BigDecimal.ZERO, null));

        service.bakeNow(MOTION_ID);

        verify(runner, times(1)).run(any(), any(), any(), any());       // 돈이 나가는 쪽은 한 번뿐
        verify(motionRecorder).requestLocalRegen(MOTION_ID, 2);
        verify(motionRecorder, never()).markFailed(anyLong());          // 아직 실패가 아니다 — 맥미니가 남았다
    }

    @Test
    @DisplayName("시도 횟수를 2 이상으로 올리면 다시 굽는다 — 두 번째에 검수 대기까지 간다")
    void retriesWhenConfigured() {
        MotionService twice = new MotionService(runner, mock(GenerationRecorder.class), motionRecorder,
                jobRepository, stepRepository, motionRepository, petRepository, registry, catalog, gate, 2, 2);
        when(runner.run(any(), any(), any(), any()))
                .thenAnswer(i -> RunResult.failed(i.getArgument(1), BigDecimal.ZERO, null))
                .thenAnswer(i -> {
                    StepContext ctx = i.getArgument(1);
                    ctx.putImage(MotionPostStep.NAME, ctx.outputPrefix() + "/motion.webp");
                    return RunResult.ok(ctx, new BigDecimal("0.0985"));
                });
        when(gate.judge(anyString()))
                .thenReturn(new MotionGate.Verdict(GateVerdict.REVIEW, "", "g0"));

        twice.bakeNow(MOTION_ID);

        verify(runner, times(2)).run(any(), any(), any(), any());
        verify(motionRecorder).toReview(eq(MOTION_ID), anyString(), any());
        verify(motionRecorder, never()).markFailed(anyLong());
    }
}
