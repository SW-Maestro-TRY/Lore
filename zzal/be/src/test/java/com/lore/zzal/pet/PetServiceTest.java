package com.lore.zzal.pet;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.s3.S3Service;
import com.lore.common.user.User;
import com.lore.common.user.UserRepository;
import com.lore.zzal.generation.GenJobRepository;
import com.lore.zzal.generation.GenStepRecordRepository;
import com.lore.zzal.generation.HatchService;
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

import static com.lore.zzal.pet.AwakeClockTest.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 펫 서비스 테스트 — 거절 이유·창·자리 계산.
 *
 * ★ 여기서 잡으려는 것은 "규칙은 맞는데 <b>말이 틀린</b>" 종류다 — 창 밖에서 재우기를 누르면
 *   409 인 것은 엔티티가 보장하지만, 그때 코드가 {@code ZZAL_NOT_SLEEP_TIME} 인지 {@code ZZAL_PET_SLEEPING} 인지는
 *   화면이 띄우는 문구를 가른다. 그리고 "보냈는데 자리가 안 비는 것" — 예외도 로그도 없이 "자리 없음" 만 뜬다.
 */
@DisplayName("펫 서비스 — 거절 이유·창·자리")
class PetServiceTest {

    private static final Instant T0 = kst("2026-09-05 12:00");
    private static final Long USER_ID = 1L;
    private static final Long PET_ID = 7L;

    private ZzalPetRepository petRepository;
    private UserRepository userRepository;
    private S3Service s3Service;
    private GenJobRepository jobRepository;
    private HatchService hatchService;
    private PetService service;

    @BeforeEach
    void setUp() {
        petRepository = mock(ZzalPetRepository.class);
        jobRepository = mock(GenJobRepository.class);
        userRepository = mock(UserRepository.class);
        s3Service = mock(S3Service.class);
        hatchService = mock(HatchService.class);
        service = new PetService(
                petRepository,
                jobRepository,
                mock(GenStepRecordRepository.class),
                mock(StepLabels.class),
                userRepository,
                s3Service,
                hatchService,
                mock(ApplicationEventPublisher.class),
                new MotionCatalog("", "", "v1"),
                mock(ZzalMotionRepository.class));
    }

    /** T0(정오) 에 부화한 아기. */
    private ZzalPet baby() {
        ZzalPet pet = ZzalPet.hatch(USER_ID, "여울", null, "images/zzal/abc", T0);
        pet.markAlive("images/zzal/sheet", "생김새", T0);
        when(petRepository.findById(PET_ID)).thenReturn(Optional.of(pet));
        when(petRepository.findByIdForUpdate(PET_ID)).thenReturn(Optional.of(pet));
        return pet;
    }

    /** 11:00 에 부화해 정오에 어린이가 된 펫. 배부름 0·행복 0·흔적 4 인 채다(돌봄 테스트용). */
    private ZzalPet child() {
        Instant hatched = T0.minus(Duration.ofMinutes(60));
        ZzalPet pet = ZzalPet.hatch(USER_ID, "여울", null, "images/zzal/abc", hatched);
        pet.markAlive("images/zzal/sheet", "생김새", hatched);
        pet.settle(T0);
        when(petRepository.findById(PET_ID)).thenReturn(Optional.of(pet));
        when(petRepository.findByIdForUpdate(PET_ID)).thenReturn(Optional.of(pet));
        return pet;
    }

    private ZzalPet egg() {
        ZzalPet pet = ZzalPet.hatch(USER_ID, "여울", null, "images/zzal/abc", T0);
        when(petRepository.findById(PET_ID)).thenReturn(Optional.of(pet));
        when(petRepository.findByIdForUpdate(PET_ID)).thenReturn(Optional.of(pet));
        return pet;
    }

    private static void assertCode(Runnable r, ErrorCode code) {
        assertThatThrownBy(r::run)
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", code);
    }

    @Nested
    @DisplayName("돌봄 6종 — 거절 이유")
    class Care {

        @Test
        @DisplayName("밥 — 재고 0 이면 ZZAL_NO_FOOD, 가득이면 ZZAL_CARE_NOT_NEEDED")
        void feed() {
            ZzalPet pet = child();                                  // 밥 3, 배부름 0
            service.care(USER_ID, PET_ID, CareAction.FEED, T0);
            service.care(USER_ID, PET_ID, CareAction.FEED, T0);
            service.care(USER_ID, PET_ID, CareAction.FEED, T0);
            assertThat(pet.getFullness()).isEqualTo(3);
            assertCode(() -> service.care(USER_ID, PET_ID, CareAction.FEED, T0), ErrorCode.ZZAL_NO_FOOD);

            pet.grantFood(T0);
            pet.grantFood(T0);
            service.care(USER_ID, PET_ID, CareAction.FEED, T0);     // 4
            assertCode(() -> service.care(USER_ID, PET_ID, CareAction.FEED, T0), ErrorCode.ZZAL_CARE_NOT_NEEDED);
        }

        @Test
        @DisplayName("목욕 — 하루 두 번째는 ZZAL_BATH_DONE_TODAY")
        void bathOncePerDay() {
            ZzalPet pet = child();
            service.care(USER_ID, PET_ID, CareAction.BATH, T0);
            assertThat(pet.getTrash()).isZero();
            assertCode(() -> service.care(USER_ID, PET_ID, CareAction.BATH, T0), ErrorCode.ZZAL_BATH_DONE_TODAY);
        }

        @Test
        @DisplayName("청소 — 깨끗하면 ZZAL_CARE_NOT_NEEDED · 약 — 안 아프면 ZZAL_CARE_NOT_NEEDED")
        void cleanAndMedicine() {
            child();
            service.care(USER_ID, PET_ID, CareAction.CLEAN, T0);
            assertCode(() -> service.care(USER_ID, PET_ID, CareAction.CLEAN, T0), ErrorCode.ZZAL_CARE_NOT_NEEDED);
            assertCode(() -> service.care(USER_ID, PET_ID, CareAction.MEDICINE, T0), ErrorCode.ZZAL_CARE_NOT_NEEDED);
        }

        @Test
        @DisplayName("★ 간식은 행복이 가득이어도 받는다(상훈님 9/5 결정) · 쓰다듬기 — 거절 없음(4번째도 200)")
        void snackAndPet() {
            ZzalPet pet = child();
            for (int i = 0; i < 5; i++) {
                service.care(USER_ID, PET_ID, CareAction.SNACK, T0);
            }
            assertThat(pet.getHappiness()).isEqualTo(4);          // 상한에서 멈추되 거절은 없다
            assertThat(pet.getSnackStreak()).isEqualTo(5);        // 배탈(PR-8)의 재료

            for (int i = 0; i < 4; i++) {
                service.care(USER_ID, PET_ID, CareAction.PET, T0);
            }
            assertThat(pet.getTodayPetCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("★ 자는 중엔 전부 ZZAL_PET_SLEEPING — 23:00 이 지나 저절로 잠든 뒤에도")
        void refusesWhileSleeping() {
            child();
            Instant midnight = kst("2026-09-06 00:00");
            for (CareAction a : CareAction.values()) {
                assertCode(() -> service.care(USER_ID, PET_ID, a, midnight), ErrorCode.ZZAL_PET_SLEEPING);
            }
        }

        @Test
        @DisplayName("부화 중·남의 펫")
        void notAliveOrNotMine() {
            egg();
            assertCode(() -> service.care(USER_ID, PET_ID, CareAction.FEED, T0), ErrorCode.ZZAL_PET_NOT_ALIVE);
            assertCode(() -> service.care(99L, PET_ID, CareAction.FEED, T0), ErrorCode.ZZAL_PET_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("재우기·깨우기 — 창 (정본 2·12장)")
    class SleepWake {

        @Test
        @DisplayName("★ 18:59 재우기 → ZZAL_NOT_SLEEP_TIME, 19:00 → 잠듦, 다시 누르면 ZZAL_PET_SLEEPING")
        void sleepWindow() {
            ZzalPet pet = child();
            assertCode(() -> service.sleep(USER_ID, PET_ID, kst("2026-09-05 18:59")), ErrorCode.ZZAL_NOT_SLEEP_TIME);
            service.sleep(USER_ID, PET_ID, kst("2026-09-05 19:00"));
            assertThat(pet.getSleepKind()).isEqualTo(SleepKind.NIGHT);
            assertCode(() -> service.sleep(USER_ID, PET_ID, kst("2026-09-05 19:01")), ErrorCode.ZZAL_PET_SLEEPING);
        }

        @Test
        @DisplayName("★ 06:59 깨우기 → ZZAL_NOT_WAKE_TIME, 07:00 → 깸, 10:01 은 이미 깨어 ZZAL_PET_NOT_SLEEPING")
        void wakeWindow() {
            ZzalPet pet = child();
            service.sleep(USER_ID, PET_ID, kst("2026-09-05 19:00"));
            assertCode(() -> service.wake(USER_ID, PET_ID, kst("2026-09-06 06:59")), ErrorCode.ZZAL_NOT_WAKE_TIME);
            service.wake(USER_ID, PET_ID, kst("2026-09-06 07:00"));
            assertThat(pet.isSleeping()).isFalse();

            service.sleep(USER_ID, PET_ID, kst("2026-09-06 19:00"));
            assertCode(() -> service.wake(USER_ID, PET_ID, kst("2026-09-07 10:01")), ErrorCode.ZZAL_PET_NOT_SLEEPING);
            assertThat(pet.isOverslept()).isTrue();
        }

        @Test
        @DisplayName("아기 때 낮잠 — 창 밖이어도 한 번 되고, 4분 뒤 깨우기는 ZZAL_NOT_WAKE_TIME")
        void nap() {
            ZzalPet pet = baby();
            Instant t = T0.plus(Duration.ofMinutes(40));
            service.sleep(USER_ID, PET_ID, t);
            assertThat(pet.getSleepKind()).isEqualTo(SleepKind.NAP);
            assertCode(() -> service.wake(USER_ID, PET_ID, t.plus(Duration.ofMinutes(4))), ErrorCode.ZZAL_NOT_WAKE_TIME);
            service.wake(USER_ID, PET_ID, t.plus(Duration.ofMinutes(5)));
            assertThat(pet.getNapCount()).isEqualTo(1);
            assertCode(() -> service.sleep(USER_ID, PET_ID, t.plus(Duration.ofMinutes(6))), ErrorCode.ZZAL_NOT_SLEEP_TIME);
        }
    }

    @Nested
    @DisplayName("개발용 시계 — 오프셋")
    class DevClock {

        @Test
        @DisplayName("★ 11시간 당기면 이 펫은 23:00 — 자동 취침이 실제 규칙으로 돈다")
        void advanceMakesItEleven() {
            ZzalPet pet = child();
            ZzalPet result = service.advanceClock(USER_ID, PET_ID, Duration.ofHours(11), T0);
            assertThat(result.now(T0)).isEqualTo(kst("2026-09-05 23:00"));
            assertThat(result.isSleeping()).isTrue();
            // 그 뒤의 돌봄도 밀린 시계로 판정된다
            assertCode(() -> service.care(USER_ID, PET_ID, CareAction.FEED, T0), ErrorCode.ZZAL_PET_SLEEPING);
        }

        @Test
        @DisplayName("맞추기 — 19:00 으로 두면 재우기가 된다")
        void setClock() {
            ZzalPet pet = child();
            service.setClock(USER_ID, PET_ID, kst("2026-09-05 19:00"), T0);
            service.sleep(USER_ID, PET_ID, T0);
            assertThat(pet.isSleeping()).isTrue();
        }

        @Test
        @DisplayName("남의 펫은 못 당긴다 — 돌봄과 같은 판정(404)")
        void notMine() {
            child();
            assertCode(() -> service.advanceClock(99L, PET_ID, Duration.ofHours(1), T0), ErrorCode.ZZAL_PET_NOT_FOUND);
        }
    }


    @Nested
    @DisplayName("함께한 날 — 앱을 연 날만 +1 (정본 3·16장)")
    class Visits {

        @Test
        @DisplayName("★ 부화한 날이 1일째. 다음 날 처음 열면 2, 같은 날 또 열어도 2. 기상 전(자는 중)이라도 센다")
        void countsCalendarDaysOpened() {
            ZzalPet pet = baby();
            assertThat(pet.getDaysTogether()).isEqualTo(1);
            service.refresh(USER_ID, PET_ID, kst("2026-09-05 20:00"));
            assertThat(pet.getDaysTogether()).isEqualTo(1);
            service.refresh(USER_ID, PET_ID, kst("2026-09-06 08:00"));   // 자는 중(23:00 자동 취침)
            assertThat(pet.isSleeping()).isTrue();
            assertThat(pet.getDaysTogether()).isEqualTo(2);
            service.care(USER_ID, PET_ID, CareAction.PET, kst("2026-09-06 11:00"));
            assertThat(pet.getDaysTogether()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("성격·배경·공유")
    class PersonalityBackgroundShare {

        @Test
        @DisplayName("성격은 언제든, 자는 중에도")
        void personalityAnytime() {
            ZzalPet pet = baby();
            service.choosePersonality(USER_ID, PET_ID, Personality.LIVELY, "구름 위 마을", kst("2026-09-06 00:00"));
            assertThat(pet.isSleeping()).isTrue();
            assertThat(pet.getPersonality()).isEqualTo(Personality.LIVELY);
            assertThat(pet.getWorld()).isEqualTo("구름 위 마을");
            service.choosePersonality(USER_ID, PET_ID, Personality.COOL, "  ", kst("2026-09-06 00:01"));
            assertThat(pet.getWorld()).isNull();
        }

        @Test
        @DisplayName("배경은 2층 4종 전엔 ZZAL_FEATURE_LOCKED")
        void backgroundLocked() {
            ZzalPet pet = baby();
            assertCode(() -> service.changeBackground(USER_ID, PET_ID, "window_day", T0), ErrorCode.ZZAL_FEATURE_LOCKED);
            assertThat(pet.getBackground()).isEqualTo("room");
        }

        @Test
        @DisplayName("공유 — 열린 동작만. 잠긴 2층·모르는 key 는 둘 다 ZZAL_MOTION_NOT_OPEN(구분해 주면 key 를 훑는 수단)")
        void shareOnlyUnlocked() {
            ZzalPet pet = baby();
            service.share(USER_ID, PET_ID, "base", T0);
            assertThat(pet.getShares()).isEqualTo(1);
            assertCode(() -> service.share(USER_ID, PET_ID, "tilt", T0), ErrorCode.ZZAL_MOTION_NOT_OPEN);
            assertCode(() -> service.share(USER_ID, PET_ID, "nope", T0), ErrorCode.ZZAL_MOTION_NOT_OPEN);
        }
    }

    @Nested
    @DisplayName("즉시 해금 — 행동 응답의 justUnlocked (정본 6장)")
    class JustUnlocked {

        @Test
        @DisplayName("★ 재우기·깨우기 합쳐 3회가 되는 그 행동에 '자기'(11)가 실린다. 그 전엔 비어 있다")
        void sleepWakeThreeTimes() {
            ZzalPet pet = baby();
            Instant t40 = T0.plus(Duration.ofMinutes(40));
            PetService.Action a1 = service.sleep(USER_ID, PET_ID, t40);                       // 1
            assertThat(a1.justUnlocked()).isEmpty();
            PetService.Action a2 = service.wake(USER_ID, PET_ID, t40.plus(Duration.ofMinutes(5)));   // 2
            assertThat(a2.justUnlocked()).isEmpty();
            PetService.Action a3 = service.sleep(USER_ID, PET_ID, kst("2026-09-05 19:00"));  // 3 → 자기
            assertThat(a3.justUnlocked()).containsExactly(11);
            assertThat(UnlockRules.isUnlocked(pet, new MotionCatalog("", "", "v1").bySeq(11).orElseThrow(),
                    new MotionCatalog("", "", "v1"))).isTrue();
        }
    }

    @Nested
    @DisplayName("동시 요청 — 같은 펫은 잠그고 직렬화 (리뷰 상-1)")
    class Serialization {

        @Test
        @DisplayName("★ 상태를 바꾸는 길은 전부 FOR UPDATE 로 읽는다 — FEED·SNACK 연속 두 건이 둘 다 반영")
        void mutatingPathsLockTheRow() {
            ZzalPet pet = child();
            pet.grantFood(T0);
            service.care(USER_ID, PET_ID, CareAction.FEED, T0);
            service.care(USER_ID, PET_ID, CareAction.SNACK, T0);
            assertThat(pet.getFullness()).isEqualTo(1);
            assertThat(pet.getHappiness()).isEqualTo(1);
            assertThat(pet.getFeeds()).isEqualTo(1);
            verify(petRepository, org.mockito.Mockito.times(2)).findByIdForUpdate(PET_ID);
            verify(petRepository, org.mockito.Mockito.never()).findById(PET_ID);
        }

        @Test
        @DisplayName("조회(refresh)·재우기·dev 시계도 잠근다. 읽기 전용 get 만 잠그지 않는다")
        void refreshSleepDevLockToo() {
            child();
            service.refresh(USER_ID, PET_ID, T0);
            service.sleep(USER_ID, PET_ID, kst("2026-09-05 19:00"));
            service.advanceClock(USER_ID, PET_ID, Duration.ofMinutes(1), kst("2026-09-05 19:00"));
            verify(petRepository, org.mockito.Mockito.times(3)).findByIdForUpdate(PET_ID);
            service.get(USER_ID, PET_ID);
            verify(petRepository, org.mockito.Mockito.times(1)).findById(PET_ID);
        }
    }

    @Nested
    @DisplayName("보내기")
    class Release {

        @Test
        @DisplayName("보내면 DEAD·RELEASED")
        void releases() {
            ZzalPet pet = baby();
            ZzalPet result = service.release(USER_ID, PET_ID, T0);
            assertThat(result.getPhase()).isEqualTo(PetPhase.DEAD);
            assertThat(result.getDeathReason()).isEqualTo(DeathReason.RELEASED);
        }

        @Test
        @DisplayName("★ 부화 중이면 거절한다 — 굽고 있는 작업이 주인을 잃는다")
        void refusesWhileHatching() {
            egg();
            assertCode(() -> service.release(USER_ID, PET_ID, T0), ErrorCode.ZZAL_PET_RELEASE_NOT_ALLOWED);
        }

        @Test
        @DisplayName("이미 떠난 아이는 조용히 넘어간다")
        void idempotent() {
            ZzalPet pet = baby();
            service.release(USER_ID, PET_ID, T0);
            service.release(USER_ID, PET_ID, T0);
            assertThat(pet.getPhase()).isEqualTo(PetPhase.DEAD);
        }
    }

    @Nested
    @DisplayName("자리")
    class Slots {

        private void allowCreate() {
            User user = mock(User.class);
            when(user.getPetSlots()).thenReturn(1);
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(petRepository.findFirstByUserIdAndPhase(eq(USER_ID), any())).thenReturn(Optional.empty());
            when(hatchService.currentVersion()).thenReturn("v1");
            when(petRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        }

        @Test
        @DisplayName("★ 자리를 셀 때 HATCHING·ALIVE 만 센다 — DEAD 가 끼면 보내도 자리가 안 빈다")
        void countsOnlyOccupyingPhases() {
            allowCreate();
            when(petRepository.countByUserIdAndPhaseIn(eq(USER_ID), any())).thenReturn(0L);

            service.create(USER_ID, "여울", null, "images/zzal/abc", T0);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<PetPhase>> phases = ArgumentCaptor.forClass(Collection.class);
            verify(petRepository).countByUserIdAndPhaseIn(eq(USER_ID), phases.capture());
            assertThat(phases.getValue()).containsExactlyInAnyOrder(PetPhase.HATCHING, PetPhase.ALIVE);
            assertThat(phases.getValue()).doesNotContain(PetPhase.DEAD, PetPhase.FAILED);
        }

        @Test
        @DisplayName("자리가 없으면 ZZAL_PET_LIMIT_REACHED, 키는 소모하지 않는다")
        void limitReached() {
            allowCreate();
            when(petRepository.countByUserIdAndPhaseIn(eq(USER_ID), any())).thenReturn(1L);

            assertCode(() -> service.create(USER_ID, "여울", null, "images/zzal/abc", T0), ErrorCode.ZZAL_PET_LIMIT_REACHED);
            verify(s3Service, org.mockito.Mockito.never()).consume(anyLong(), any(), any());
        }

        @Test
        @DisplayName("부화 중이면 ZZAL_PET_ALREADY_HATCHING")
        void alreadyHatching() {
            ZzalPet hatching = ZzalPet.hatch(USER_ID, "알", null, "k", T0);
            when(petRepository.findFirstByUserIdAndPhase(eq(USER_ID), eq(PetPhase.HATCHING)))
                    .thenReturn(Optional.of(hatching));
            assertCode(() -> service.create(USER_ID, "여울", null, "images/zzal/abc", T0), ErrorCode.ZZAL_PET_ALREADY_HATCHING);
        }

        @Test
        @DisplayName("목록 정산 — 여러 마리를 한 번에")
        void refreshAll() {
            ZzalPet pet = child();
            when(petRepository.findByUserIdOrderByIdDesc(USER_ID)).thenReturn(List.of(pet));
            List<ZzalPet> pets = service.refreshAll(USER_ID, kst("2026-09-06 00:00"));
            assertThat(pets.get(0).isSleeping()).isTrue();
        }
    }
}
