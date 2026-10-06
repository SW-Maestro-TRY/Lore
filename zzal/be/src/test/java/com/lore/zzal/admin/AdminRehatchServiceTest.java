package com.lore.zzal.admin;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.user.User;
import com.lore.common.user.UserRepository;
import com.lore.zzal.generation.GenJob;
import com.lore.zzal.generation.GenJobRepository;
import com.lore.zzal.generation.GenKind;
import com.lore.zzal.generation.GenStepRecord;
import com.lore.zzal.generation.GenStepRecordRepository;
import com.lore.zzal.generation.HatchService;
import com.lore.zzal.generation.PetHatchRequested;
import com.lore.zzal.generation.steps.GridStep;
import com.lore.zzal.generation.steps.PostProcessStep;
import com.lore.zzal.pet.PetPhase;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalPetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 관리자 재굽기(2026-10-07) — <b>실패한 알만</b>, <b>격자만 버리고</b>, <b>부화와 같은 길로</b> 다시 굽는가.
 */
@DisplayName("관리자 재굽기 — 실패한 알을 새 시도로")
class AdminRehatchServiceTest {

    private static final Long ADMIN = 1L;
    private static final Long OWNER = 9L;
    private static final Long PET = 7L;
    private static final Instant T0 = Instant.parse("2026-10-06T03:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-07T03:00:00Z");

    private AdminGuard guard;
    private ZzalPetRepository petRepository;
    private UserRepository userRepository;
    private GenJobRepository jobRepository;
    private GenStepRecordRepository stepRepository;
    private ApplicationEventPublisher events;
    private AdminRehatchService service;
    private final List<GenJob> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        guard = mock(AdminGuard.class);
        petRepository = mock(ZzalPetRepository.class);
        userRepository = mock(UserRepository.class);
        jobRepository = mock(GenJobRepository.class);
        stepRepository = mock(GenStepRecordRepository.class);
        events = mock(ApplicationEventPublisher.class);
        HatchService hatch = mock(HatchService.class);
        when(hatch.currentVersion()).thenReturn("v1");

        User owner = User.signUp("rehatch-owner@example.invalid");
        ReflectionTestUtils.setField(owner, "id", OWNER);
        when(userRepository.findById(OWNER)).thenReturn(Optional.of(owner));
        when(jobRepository.save(any())).thenAnswer(inv -> {
            GenJob j = inv.getArgument(0);
            ReflectionTestUtils.setField(j, "id", 100L + saved.size());
            saved.add(j);
            return j;
        });
        service = new AdminRehatchService(guard, petRepository, userRepository, jobRepository,
                stepRepository, hatch, events);
    }

    private ZzalPet failedPet(String name) {
        ZzalPet pet = ZzalPet.draft(OWNER, "images/zzal/src", T0);
        ReflectionTestUtils.setField(pet, "id", PET);
        if (name != null) {
            pet.character(name, null, null, null, null, null, T0);
        }
        pet.markHatchFailed();
        when(petRepository.findById(PET)).thenReturn(Optional.of(pet));
        return pet;
    }

    private static GenStepRecord succeeded(String name) {
        GenStepRecord r = GenStepRecord.start(1L, 0, name, T0);
        r.succeed("k/" + name, null, null, java.math.BigDecimal.ZERO, T0);
        return r;
    }

    @Test
    @DisplayName("★★ 이름 지은 실패 알 → HATCHING · 격자 두 장만 폐기 · attempt=0(재굽기 표식) job · 부화 이벤트")
    void namedFailedPetGoesBackToHatching() {
        ZzalPet pet = failedPet("루나");
        GenStepRecord sheet = succeeded("sheet");
        GenStepRecord grid = succeeded(GridStep.NAME);
        GenStepRecord grid2 = succeeded(PostProcessStep.GRID2);
        when(stepRepository.findSucceededByPet(PET, GenKind.HATCH)).thenReturn(List.of(sheet, grid, grid2));

        AdminRehatchService.Rehatch r = service.rehatch(ADMIN, PET, NOW);

        assertThat(pet.getPhase()).isEqualTo(PetPhase.HATCHING);
        assertThat(pet.getDeathReason()).isNull();
        assertThat(r.discardedSteps()).isEqualTo(2);
        verify(stepRepository).delete(grid);
        verify(stepRepository).delete(grid2);
        verify(stepRepository, never()).delete(sheet);
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getAttempt())
                .as("1 이면 사람 상한(attempt=1 만 셈)을 깎는다 — 재굽기는 서비스 사정이다")
                .isEqualTo(GenJob.ADMIN_REHATCH_ATTEMPT);
        verify(events).publishEvent(new PetHatchRequested(saved.get(0).getId(), PET, "v1"));
    }

    @Test
    @DisplayName("★ 이름 없는 실패 알 → DRAFT (HATCHING 이면 이름도 못 짓고 살아나지도 못한다)")
    void unnamedFailedPetGoesBackToDraft() {
        ZzalPet pet = failedPet(null);
        when(stepRepository.findSucceededByPet(PET, GenKind.HATCH)).thenReturn(List.of());

        service.rehatch(ADMIN, PET, NOW);

        assertThat(pet.getPhase()).isEqualTo(PetPhase.DRAFT);
    }

    @Test
    @DisplayName("실패한 알이 아니면 409 — 살아 있는 아이를 다시 굽지 않는다")
    void onlyFailedPets() {
        ZzalPet pet = ZzalPet.draft(OWNER, "images/zzal/src", T0);
        pet.character("여울", null, null, null, null, null, T0);
        when(petRepository.findById(PET)).thenReturn(Optional.of(pet));

        assertThatThrownBy(() -> service.rehatch(ADMIN, PET, NOW))
                .isInstanceOf(BusinessException.class);
        assertThat(saved).isEmpty();
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("주인 자리가 차 있으면 409 — 실패 뒤 새 아이를 들인 사람(펫4→펫6)")
    void ownerSlotFull() {
        failedPet("루나");
        when(petRepository.countByUserIdAndPhaseIn(eq(OWNER), any())).thenReturn(1L);

        assertThatThrownBy(() -> service.rehatch(ADMIN, PET, NOW))
                .isInstanceOf(BusinessException.class);
        assertThat(saved).isEmpty();
    }

    @Test
    @DisplayName("관리자가 아니면 아무것도 안 한다(AdminGuard 재사용)")
    void adminOnly() {
        doThrow(new BusinessException(ErrorCode.ADMIN_ONLY)).when(guard).require(anyLong());

        assertThatThrownBy(() -> service.rehatch(ADMIN, PET, NOW))
                .isInstanceOf(BusinessException.class);
        verify(petRepository, never()).findById(anyLong());
    }
}
