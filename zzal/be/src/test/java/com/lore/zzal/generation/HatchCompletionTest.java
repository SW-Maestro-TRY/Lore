package com.lore.zzal.generation;

import com.lore.zzal.generation.steps.GridStep;
import com.lore.zzal.generation.steps.IdentityStep;
import com.lore.zzal.generation.steps.MotionGridStep;
import com.lore.zzal.generation.steps.MotionPostStep;
import com.lore.zzal.generation.steps.PostProcessStep;
import com.lore.zzal.generation.steps.SheetStep;
import com.lore.zzal.motion.MotionSeeder;
import com.lore.zzal.pet.PetPhase;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 살아나는 시점 — <b>굽기 완료 + 이름 제출</b> 둘 다 갖춰진 순간(1.9).
 *
 * <h3>★ 왜 두 조건인가</h3>
 * 그림을 올리는 순간부터 끝까지 굽는다. 그래서 사용자가 이름을 짓는 동안 굽기가 먼저 끝날 수 있고,
 * 그때 바로 살리면 <b>이름 없는 펫이 방에 나타난다.</b> 어느 쪽이 먼저 오든 <b>나중에 오는 쪽</b>이 살린다.
 */
@DisplayName("부화 완료 — 굽기와 이름, 나중에 갖춰지는 쪽이 살린다")
class HatchCompletionTest {

    private static final Long PET = 7L;
    private static final String V = "v2";
    private static final Instant T0 = Instant.parse("2026-09-11T03:00:00Z");

    private ZzalPetRepository petRepository;
    private GenerationRecorder recorder;
    private MotionSeeder seeder;
    private HatchService service;
    private final List<GenStepRecord> succeeded = new ArrayList<>();

    @BeforeEach
    void setUp() {
        petRepository = mock(ZzalPetRepository.class);
        recorder = mock(GenerationRecorder.class);
        seeder = mock(MotionSeeder.class);

        when(recorder.loadSucceeded(anyLong(), any(), anyString())).thenReturn(succeeded);

        PipelineRegistry registry = new PipelineRegistry(
                StepMocks.sheet(), StepMocks.identity(),
                StepMocks.grid(), StepMocks.grid2(), StepMocks.post(),
                mock(MotionGridStep.class), mock(MotionPostStep.class), "v2", "v1", path -> true);

        service = new HatchService(mock(GenerationRunner.class), recorder, mock(GenJobRepository.class),
                registry, petRepository, 2, seeder);
    }

    /** 다섯 단계를 전부 성공시킨 기록 — 굽기가 끝난 상태. */
    private void bakingDone() {
        succeeded.clear();
        List<String> names = List.of("sheet", "identity", "grid", "grid2", "postprocess");
        for (int i = 0; i < names.size(); i++) {
            GenStepRecord rec = GenStepRecord.start(1L, i, names.get(i), T0);
            rec.succeed(names.get(i) + ".png", "identity".equals(names.get(i)) ? "생김새 문단" : null,
                    "m", BigDecimal.ZERO, T0);
            succeeded.add(rec);
        }
    }

    /** 이름 없는 초안. 그림만 올린 상태. */
    private ZzalPet draft() {
        ZzalPet pet = ZzalPet.draft(1L, "images/zzal/src", T0);
        when(petRepository.findById(PET)).thenReturn(Optional.of(pet));
        // markPetAlive 는 기록 담당이 하는 일이라 목이다 — 진짜 펫에 반영되게 이어 준다
        doAnswer(inv -> {
            pet.markAlive(inv.getArgument(1), inv.getArgument(2), inv.getArgument(3));
            return null;
        }).when(recorder).markPetAlive(anyLong(), any(), any(), any());
        return pet;
    }

    @Test
    @DisplayName("★ 이름이 먼저 — 그 뒤 굽기가 끝나는 순간 ALIVE")
    void nameFirstThenBaking() {
        ZzalPet pet = draft();
        pet.character("여울", "왼쪽 눈에 흉터", null, null, null, null, T0);
        assertThat(pet.getPhase()).isEqualTo(PetPhase.HATCHING);

        assertThat(service.completeIfReady(PET, V)).isFalse();      // 아직 굽는 중
        assertThat(pet.getPhase()).isEqualTo(PetPhase.HATCHING);

        bakingDone();
        assertThat(service.completeIfReady(PET, V)).isTrue();
        assertThat(pet.getPhase()).isEqualTo(PetPhase.ALIVE);
        assertThat(pet.getSheetImageKey()).isEqualTo("sheet.png");
        assertThat(pet.getIdentityText()).isEqualTo("생김새 문단");
        verify(seeder).seed(anyLong(), any());
    }

    @Test
    @DisplayName("★ 굽기가 먼저 — 이름이 들어오는 순간 ALIVE (목표 상태)")
    void bakingFirstThenName() {
        ZzalPet pet = draft();
        bakingDone();

        assertThat(service.completeIfReady(PET, V)).isFalse();      // 이름이 아직 없다
        assertThat(pet.getPhase()).isEqualTo(PetPhase.DRAFT);

        pet.character("여울", null, null, null, null, null, T0);
        assertThat(service.completeIfReady(PET, V)).isTrue();
        assertThat(pet.getPhase()).isEqualTo(PetPhase.ALIVE);
    }

    @Test
    @DisplayName("★★ 이름 없이 굽기만 끝난 상태는 ALIVE 가 아니다 — 이름 없는 펫이 방에 나타나면 안 된다")
    void bakedButUnnamedStaysDraft() {
        ZzalPet pet = draft();
        bakingDone();

        assertThat(service.completeIfReady(PET, V)).isFalse();
        assertThat(pet.getPhase()).isEqualTo(PetPhase.DRAFT);
        verify(recorder, never()).markPetAlive(anyLong(), any(), any(), any());
        verify(seeder, never()).seed(anyLong(), any());
    }

    @Test
    @DisplayName("굽기가 한 단계라도 남았으면 이름이 있어도 안 살린다")
    void partialBakingDoesNotComplete() {
        ZzalPet pet = draft();
        pet.character("여울", null, null, null, null, null, T0);
        bakingDone();
        succeeded.removeLast();                                    // postprocess 가 아직

        assertThat(service.completeIfReady(PET, V)).isFalse();
        assertThat(pet.getPhase()).isEqualTo(PetPhase.HATCHING);
    }

    @Test
    @DisplayName("두 번 불려도 두 번 살리지 않는다 — 굽기 끝과 이름 도착이 겹칠 수 있다")
    void completingTwiceIsSafe() {
        ZzalPet pet = draft();
        pet.character("여울", null, null, null, null, null, T0);
        bakingDone();

        assertThat(service.completeIfReady(PET, V)).isTrue();
        service.completeIfReady(PET, V);                           // 두 번째는 markAlive 가 무시한다
        assertThat(pet.getPhase()).isEqualTo(PetPhase.ALIVE);
    }

    @Test
    @DisplayName("★★ 이름이 어긋나면 개수가 맞아도 안 살린다 — sheetKey 가 null 인 채로 ALIVE 되던 것 (P-11)")
    void wrongStepNamesDoNotCompleteEvenWhenCountMatches() {
        ZzalPet pet = draft();
        pet.character("여울", null, null, null, null, null, T0);
        bakingDone();

        // sheet 가 빠지고 엉뚱한 이름이 하나 들어왔다 — 개수는 그대로 다섯이다
        succeeded.removeIf(r -> "sheet".equals(r.getName()));
        GenStepRecord stray = GenStepRecord.start(1L, 9, "unknown", T0);
        stray.succeed("unknown.png", null, "m", BigDecimal.ZERO, T0);
        succeeded.add(stray);
        assertThat(succeeded).hasSize(5);

        assertThat(service.completeIfReady(PET, V)).isFalse();
        assertThat(pet.getPhase()).isEqualTo(PetPhase.HATCHING);
        verify(recorder, never()).markPetAlive(anyLong(), any(), any(), any());
        verify(seeder, never()).seed(anyLong(), any());
    }

    @Test
    @DisplayName("★ 같은 이름이 두 번 성공해도 빠진 단계를 메우지 못한다")
    void duplicateNamesDoNotFillTheGap() {
        ZzalPet pet = draft();
        pet.character("여울", null, null, null, null, null, T0);
        bakingDone();

        succeeded.removeIf(r -> "postprocess".equals(r.getName()));
        GenStepRecord again = GenStepRecord.start(1L, 9, "grid", T0);
        again.succeed("grid-again.png", null, "m", BigDecimal.ZERO, T0);
        succeeded.add(again);

        assertThat(service.completeIfReady(PET, V)).isFalse();
        assertThat(pet.getPhase()).isEqualTo(PetPhase.HATCHING);
    }
}
