package com.lore.zzal.pet;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.s3.S3Service;
import com.lore.common.user.User;
import com.lore.common.user.UserRepository;
import com.lore.zzal.generation.GenJobRepository;
import com.lore.zzal.generation.GenStepRecordRepository;
import com.lore.zzal.generation.HatchService;
import com.lore.zzal.generation.PipelineRegistry;
import com.lore.zzal.generation.StepLabels;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.ZzalMotionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 펫 서비스 테스트 — 보내기·자리 계산·첫날 순서·완주 판정.
 *
 * ★ 여기서 잡으려는 진짜 버그는 <b>"보냈는데 자리가 안 비는 것"</b> 이다. 예외도 로그도 없이
 *   "더 키울 수 있는 자리가 없어요" 만 뜨는 종류라, 눈으로 보기 전에는 안 드러난다.
 *   실제 SQL 은 스프링이 메서드 이름을 보고 만들므로 단위 테스트로 확인할 수 없다.
 *   대신 <b>서비스가 어떤 단계를 세어 달라고 하는지</b>를 붙잡아 검사한다 —
 *   여기에 DEAD 가 끼어 있으면 그 순간 새로 시작하는 길이 막힌다.
 */
@DisplayName("펫 서비스 — 보내기·자리·튜토리얼·완주 판정")
class PetServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-03T09:00:00Z");
    private static final Long USER_ID = 1L;
    private static final Long PET_ID = 7L;

    private ZzalPetRepository petRepository;
    private UserRepository userRepository;
    private S3Service s3Service;
    private GenJobRepository jobRepository;
    private HatchService hatchService;
    private MotionCatalog catalog;
    private PetService service;

    @BeforeEach
    void setUp() {
        petRepository = mock(ZzalPetRepository.class);
        jobRepository = mock(GenJobRepository.class);
        userRepository = mock(UserRepository.class);
        s3Service = mock(S3Service.class);
        hatchService = mock(HatchService.class);
        catalog = mock(MotionCatalog.class);
        service = new PetService(
                petRepository,
                jobRepository,
                mock(GenStepRecordRepository.class),
                mock(StepLabels.class),
                userRepository,
                s3Service,
                hatchService,
                mock(ZzalMotionRepository.class),
                catalog,
                mock(PipelineRegistry.class),
                mock(ApplicationEventPublisher.class));
    }

    private ZzalPet alivePet() {
        ZzalPet pet = ZzalPet.hatch(USER_ID, "여울", null, "images/zzal/abc", T0);
        pet.markAlive("images/zzal/sheet", "생김새", T0);
        return pet;
    }

    private ZzalPet egg() {
        return ZzalPet.hatch(USER_ID, "여울", null, "images/zzal/abc", T0);
    }

    @Nested
    @DisplayName("보내기")
    class Release {

        @Test
        @DisplayName("보내면 DEAD·RELEASED 가 된다")
        void releases() {
            ZzalPet pet = alivePet();
            when(petRepository.findById(PET_ID)).thenReturn(Optional.of(pet));

            ZzalPet result = service.release(USER_ID, PET_ID, T0);

            assertThat(result.getPhase()).isEqualTo(PetPhase.DEAD);
            assertThat(result.getDeathReason()).isEqualTo(DeathReason.RELEASED);
        }

        @Test
        @DisplayName("★ 부화 중이면 거절한다 — 굽고 있는 작업이 주인을 잃는다")
        void refusesWhileHatching() {
            when(petRepository.findById(PET_ID)).thenReturn(Optional.of(egg()));

            assertThatThrownBy(() -> service.release(USER_ID, PET_ID, T0))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_PET_RELEASE_NOT_ALLOWED);
        }

        @Test
        @DisplayName("남의 펫이면 404 — 403 은 그 번호의 펫이 있다는 사실을 알려주는 셈이다")
        void othersPetIsNotFound() {
            when(petRepository.findById(PET_ID)).thenReturn(Optional.of(alivePet()));

            assertThatThrownBy(() -> service.release(99L, PET_ID, T0))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_PET_NOT_FOUND);
        }

        @Test
        @DisplayName("없는 펫이면 404")
        void missingPetIsNotFound() {
            when(petRepository.findById(PET_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.release(USER_ID, PET_ID, T0))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_PET_NOT_FOUND);
        }

        @Test
        @DisplayName("이미 떠난 아이에게 다시 불러도 성공하고 상태가 안 바뀐다 — 두 번 눌러도 안전")
        void releasingTwiceIsSafe() {
            ZzalPet pet = alivePet();
            pet.release(T0);
            when(petRepository.findById(PET_ID)).thenReturn(Optional.of(pet));

            ZzalPet result = service.release(USER_ID, PET_ID, T0.plusSeconds(60));

            assertThat(result.getPhase()).isEqualTo(PetPhase.DEAD);
            assertThat(result.getDeathReason()).isEqualTo(DeathReason.RELEASED);
        }
    }

    @Nested
    @DisplayName("자리 계산")
    class Slots {

        @SuppressWarnings("unchecked")
        private Collection<PetPhase> capturePhasesAskedOnCreate() {
            User user = mock(User.class);
            when(user.getPetSlots()).thenReturn(1);
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(petRepository.findFirstByUserIdAndPhase(USER_ID, PetPhase.HATCHING))
                    .thenReturn(Optional.empty());
            when(petRepository.countByUserIdAndPhaseIn(anyLong(), any())).thenReturn(0L);
            when(petRepository.save(any(ZzalPet.class))).thenAnswer(i -> i.getArgument(0));
            when(jobRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(hatchService.currentVersion()).thenReturn("test-v1");

            service.create(USER_ID, "여울", null, "images/zzal/abc", T0);

            ArgumentCaptor<Collection<PetPhase>> captor = ArgumentCaptor.forClass(Collection.class);
            verify(petRepository).countByUserIdAndPhaseIn(eq(USER_ID), captor.capture());
            return captor.getValue();
        }

        @Test
        @DisplayName("★★ 자리를 세는 것은 HATCHING·ALIVE 뿐이다 — 여기에 DEAD 가 끼면 보내도 자리가 안 빈다")
        void countsOnlyOccupyingPhases() {
            Collection<PetPhase> asked = capturePhasesAskedOnCreate();

            assertThat(asked).containsExactlyInAnyOrder(PetPhase.HATCHING, PetPhase.ALIVE);
            // 보낸 아이(DEAD)와 태어나지 못한 알(FAILED)은 세지 않는다.
            assertThat(asked).doesNotContain(PetPhase.DEAD, PetPhase.FAILED);
        }

        @Test
        @DisplayName("자리가 다 찼으면 새로 만들 수 없다")
        void limitReached() {
            User user = mock(User.class);
            when(user.getPetSlots()).thenReturn(1);
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(petRepository.findFirstByUserIdAndPhase(USER_ID, PetPhase.HATCHING))
                    .thenReturn(Optional.empty());
            when(petRepository.countByUserIdAndPhaseIn(eq(USER_ID), any())).thenReturn(1L);

            assertThatThrownBy(() -> service.create(USER_ID, "여울", null, "images/zzal/abc", T0))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_PET_LIMIT_REACHED);
        }

        @Test
        @DisplayName("★ 보낸 뒤에는 자리가 비어 새로 만들 수 있다 — 자리 계산이 0을 돌려주는 상황")
        void canCreateAfterRelease() {
            // 떠난 아이의 행은 남아 있지만(findById 로 여전히 조회된다) 자리 계산에는 안 들어간다.
            Collection<PetPhase> asked = capturePhasesAskedOnCreate();

            ZzalPet released = alivePet();
            released.release(T0);
            assertThat(asked).doesNotContain(released.getPhase());

            // 부화가 실제로 시작됐다 = 자리 검사를 통과했다.
            verify(petRepository).save(any(ZzalPet.class));
        }
    }

    @Nested
    @DisplayName("첫날 순서(튜토리얼)")
    class Tutorial {

        @Test
        @DisplayName("★ 완료를 알리면 수치 시계가 켜진다 — 그 전에는 시간이 지나도 안 준다")
        void completingStartsTheClock() {
            ZzalPet pet = alivePet();
            when(petRepository.findById(PET_ID)).thenReturn(Optional.of(pet));

            // 알리기 전 — 사흘이 지나도 그대로다
            service.refresh(USER_ID, PET_ID, T0.plus(Duration.ofDays(3)));
            assertThat(pet.getFullness()).isEqualTo(ZzalRules.WAKE_FULLNESS);
            assertThat(pet.isTutorialDone()).isFalse();

            Instant done = T0.plus(Duration.ofDays(3));
            ZzalPet result = service.completeTutorial(USER_ID, PET_ID, done);
            assertThat(result.isTutorialDone()).isTrue();
            assertThat(result.getCareStartedAt()).isEqualTo(done);

            // 알린 뒤 — 이제 흐른다
            service.refresh(USER_ID, PET_ID, done.plus(Duration.ofHours(6)));
            assertThat(pet.getHappiness()).isEqualTo(ZzalRules.WAKE_HAPPINESS - 1);
        }

        @Test
        @DisplayName("★ 두 번 알려도 에러가 아니라 지금 상태를 돌려준다 — 두 번 눌러도 안전")
        void completingTwiceIsSafe() {
            ZzalPet pet = alivePet();
            when(petRepository.findById(PET_ID)).thenReturn(Optional.of(pet));
            service.completeTutorial(USER_ID, PET_ID, T0);

            ZzalPet again = service.completeTutorial(USER_ID, PET_ID, T0.plus(Duration.ofHours(9)));

            assertThat(again.isTutorialDone()).isTrue();
            // 다시 알려도 출발점은 처음 그대로다 — 밀리면 누를 때마다 수치가 되돌아온다
            assertThat(again.getTutorialDoneAt()).isEqualTo(T0);
            assertThat(again.getCareStartedAt()).isEqualTo(T0);
        }

        @Test
        @DisplayName("남의 펫이면 404")
        void othersPetIsNotFound() {
            when(petRepository.findById(PET_ID)).thenReturn(Optional.of(alivePet()));

            assertThatThrownBy(() -> service.completeTutorial(99L, PET_ID, T0))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_PET_NOT_FOUND);
        }

        @Test
        @DisplayName("아직 부화 중이면 알릴 수 없다")
        void hatchingPetCannotFinishTutorial() {
            when(petRepository.findById(PET_ID)).thenReturn(Optional.of(egg()));

            assertThatThrownBy(() -> service.completeTutorial(USER_ID, PET_ID, T0))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_PET_NOT_ALIVE);
        }
    }

    @Nested
    @DisplayName("완주 판정 — 정본은 동작 목록 하나")
    class Complete {

        /** 목록에 두 개만 있고 그 두 개를 다 배운 펫. */
        private ZzalPet fullyUnlockedWithTwo() {
            ZzalPet pet = alivePet();
            pet.unlockOne();
            pet.unlockOne();
            when(catalog.total()).thenReturn(2);
            when(petRepository.findById(PET_ID)).thenReturn(Optional.of(pet));
            return pet;
        }

        @Test
        @DisplayName("★ 목록이 2개면 2개에서 완주다 — 13을 기다리지 않는다")
        void trainIsBlockedAtCatalogSize() {
            fullyUnlockedWithTwo();

            assertThatThrownBy(() -> service.train(USER_ID, PET_ID, T0))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_ALL_UNLOCKED);
        }

        @Test
        @DisplayName("★ 재우기도 같은 기준으로 막힌다 — 판정이 한 곳에서 나온다")
        void sleepIsBlockedAtCatalogSize() {
            fullyUnlockedWithTwo();

            assertThatThrownBy(() -> service.sleep(USER_ID, PET_ID, T0))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_ALL_UNLOCKED);
        }

        @Test
        @DisplayName("목록이 비어 있으면 완주로 치지 않는다 — 갓 태어난 펫의 연습이 막히면 안 된다")
        void emptyCatalogDoesNotBlockAnything() {
            ZzalPet pet = alivePet();
            when(catalog.total()).thenReturn(0);
            when(petRepository.findById(PET_ID)).thenReturn(Optional.of(pet));

            service.train(USER_ID, PET_ID, T0);

            assertThat(pet.isTraining()).isTrue();
        }
    }

    @Nested
    @DisplayName("시간 당기기(dev)")
    class AdvanceClock {

        @Test
        @DisplayName("당기면 그만큼 시간이 흐른 것이 된다")
        void advancing() {
            ZzalPet pet = alivePet();
            pet.completeTutorial(T0);
            when(petRepository.findById(PET_ID)).thenReturn(Optional.of(pet));

            service.advanceClock(USER_ID, PET_ID, Duration.ofHours(6), T0);

            assertThat(pet.getHappiness()).isEqualTo(ZzalRules.WAKE_HAPPINESS - 1);
        }

        @Test
        @DisplayName("★ 남의 펫은 못 당긴다 — 돌봄 API 와 같은 판정을 탄다")
        void cannotAdvanceOthersPet() {
            when(petRepository.findById(PET_ID)).thenReturn(Optional.of(alivePet()));

            assertThatThrownBy(() -> service.advanceClock(99L, PET_ID, Duration.ofHours(6), T0))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_PET_NOT_FOUND);
        }
    }
}
