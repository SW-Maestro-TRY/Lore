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
import com.lore.zzal.motion.MotionSeeder;
import com.lore.zzal.motion.ZzalMotionRepository;
import com.lore.zzal.pet.dto.PetResponses;
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
    private ZzalMotionRepository motionRepository;
    private MotionSeeder seeder;
    private com.lore.zzal.scene.ZzalSceneRepository sceneRepository;
    private final java.util.List<com.lore.zzal.scene.ZzalScene> scenes = new java.util.ArrayList<>();
    private PetService service;

    @BeforeEach
    void setUp() {
        petRepository = mock(ZzalPetRepository.class);
        jobRepository = mock(GenJobRepository.class);
        userRepository = mock(UserRepository.class);
        s3Service = mock(S3Service.class);
        hatchService = mock(HatchService.class);
        motionRepository = mock(ZzalMotionRepository.class);
        seeder = mock(MotionSeeder.class);
        sceneRepository = mock(com.lore.zzal.scene.ZzalSceneRepository.class);
        scenes.clear();
        when(sceneRepository.save(any())).thenAnswer(i -> {
            com.lore.zzal.scene.ZzalScene sc = i.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(sc, "id", (long) (scenes.size() + 1));
            scenes.add(sc);
            return sc;
        });
        when(sceneRepository.findByPetIdOrderBySceneAtDescIdDesc(any())).thenAnswer(i -> scenes.stream()
                .sorted(java.util.Comparator.comparing(com.lore.zzal.scene.ZzalScene::getSceneAt)
                        .thenComparing(com.lore.zzal.scene.ZzalScene::getId).reversed())
                .toList());
        org.mockito.Mockito.doAnswer(i -> {
            scenes.remove(i.<com.lore.zzal.scene.ZzalScene>getArgument(0));
            return null;
        }).when(sceneRepository).delete(any());
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
                motionRepository,
                seeder,
                mock(com.lore.zzal.night.NightPlanner.class),
                new com.lore.zzal.scene.SceneService(sceneRepository, new MotionCatalog("", "", "v1")));
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
            assertThat(pet.isSick()).isTrue();                    // ★ 5개째에 배탈(정본 5장)
            assertThat(pet.getSickKind()).isEqualTo(SickKind.UPSET);
            assertCode(() -> service.care(USER_ID, PET_ID, CareAction.SNACK, T0), ErrorCode.ZZAL_SICK_REFUSES);

            service.care(USER_ID, PET_ID, CareAction.MEDICINE, T0);
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
    @DisplayName("동작 행 자가 치유 (#218 리뷰)")
    class SelfHeal {

        @Test
        @DisplayName("★ ALIVE 인데 행이 0개면 조회 때 18행을 채운다(멱등 seed) — 부화 완료 때 저장이 실패한 펫")
        void seedsWhenRowsMissing() {
            ZzalPet pet = baby();
            when(motionRepository.findByPetIdOrderBySeqAsc(PET_ID)).thenReturn(List.of());
            when(seeder.seed(PET_ID, pet.getHatchedAt())).thenReturn(18);

            service.motionRows(PET_ID);

            verify(seeder).seed(PET_ID, pet.getHatchedAt());
        }

        @Test
        @DisplayName("행이 18개면 seed 를 부르지 않는다 · 부화 중(HATCHING)이면 부르지 않는다")
        void noSeedWhenComplete() {
            ZzalPet pet = baby();
            MotionCatalog catalog = new MotionCatalog("", "", "v1");
            when(motionRepository.findByPetIdOrderBySeqAsc(PET_ID)).thenReturn(
                    catalog.all().stream().map(sp -> com.lore.zzal.motion.ZzalMotion.forCatalog(PET_ID, sp, T0)).toList());
            service.motionRows(PET_ID);
            verify(seeder, org.mockito.Mockito.never()).seed(anyLong(), any());

            egg();
            when(motionRepository.findByPetIdOrderBySeqAsc(PET_ID)).thenReturn(List.of());
            service.motionRows(PET_ID);
            verify(seeder, org.mockito.Mockito.never()).seed(anyLong(), any());
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

    /**
     * 혼자 논 장면 — 실패 주입(verify-failure-paths). ★ 여기는 <b>진짜 조회 경로</b>(touch)를 탄다.
     *
     * SceneServiceTest 는 서비스만 따로 보지만, 이 두 가지는 조회·행동이 지나는 길에서만 드러난다 —
     * "계속 보고 있으면 안 남는다"(부재의 정의)와 "밤새 안 열고 아침에 열어도 밤 장면이 온다".
     */
    @Nested
    @DisplayName("혼자 논 장면 — 조회 경로에서")
    class Scenes {

        private ZzalPet withId() {
            ZzalPet pet = child();
            org.springframework.test.util.ReflectionTestUtils.setField(pet, "id", PET_ID);
            return pet;
        }

        @Test
        @DisplayName("★★ 계속 보고 있으면 장면이 안 남는다 — 30분마다 12번 조회(6시간)해도 컷 0")
        void watchingDoesNotCountAsAbsence() {
            ZzalPet pet = withId();

            for (int i = 1; i <= 12; i++) {
                service.refresh(USER_ID, PET_ID, T0.plus(Duration.ofMinutes(30L * i)));
            }

            assertThat(scenes).as("부재가 아니라 '깨어 있던 시간' 을 세면 여기서 컷이 생긴다").isEmpty();
            assertThat(pet.getAbsenceAwakeSec()).isZero();     // 볼 때마다 끊긴다
            assertThat(pet.isScenesEnabled()).isFalse();
        }

        @Test
        @DisplayName("★ 4시간을 비우면 한 컷 — 그러나 그 직후 다시 4시간을 비워야 다음 컷이다(남은 초는 안 넘어간다)")
        void absenceStartsOverEachVisit() {
            withId();
            service.refresh(USER_ID, PET_ID, T0);                             // 부재 시계를 여기서 0으로
            service.refresh(USER_ID, PET_ID, T0.plus(Duration.ofHours(7)));   // 7시간 비움 → 한 컷(3시간 남음)
            assertThat(scenes).hasSize(1);

            service.refresh(USER_ID, PET_ID, T0.plus(Duration.ofHours(9)));   // 2시간 더 → 아직 아님
            assertThat(scenes).as("남은 3시간이 넘어왔다면 여기서 두 번째 컷이 생긴다").hasSize(1);
        }

        @Test
        @DisplayName("★★ 밤새 안 열고 아침에 열어도 밤 연습 장면이 온다 — 22:00 재우기 → 다음 날 11:00 조회")
        void nightSceneSurvivesUntilMorning() {
            ZzalPet pet = withId();
            org.springframework.test.util.ReflectionTestUtils.setField(pet, "scenesEnabledAt", T0);
            service.refresh(USER_ID, PET_ID, kst("2026-09-05 19:30"));
            // 아프면 연습 장면을 안 남기는 것이 규칙이라(정본 16장), 이 테스트에서는 낫게 해 둔다
            if (pet.isSick()) {
                service.care(USER_ID, PET_ID, CareAction.MEDICINE, kst("2026-09-05 19:30"));
            }
            service.sleep(USER_ID, PET_ID, kst("2026-09-05 22:00"));
            int afterSleep = scenes.size();

            // 그 뒤로 앱을 안 열다가 다음 날 11:00(10:00 자동 기상을 지난 뒤)에 연다
            service.refresh(USER_ID, PET_ID, kst("2026-09-06 11:00"));

            assertThat(scenes).as("재우는 응답에서든 아침 첫 조회에서든 한 번은 남아야 한다").isNotEmpty();
            assertThat(scenes.stream().filter(com.lore.zzal.scene.ZzalScene::isNight))
                    .singleElement()
                    .satisfies(sc -> {
                        assertThat(sc.getMotionKey()).isEqualTo("practice");
                        assertThat(sc.getSceneAt()).isEqualTo(kst("2026-09-05 22:00"));
                    });
            assertThat(afterSleep).isPositive();               // 재우는 응답에 이미 실렸다
            assertThat(pet.needsNightScene()).isFalse();       // 쪽지는 소비됐다
        }
    }

    /**
     * 조각 등장 시점 — 실패 주입(verify-failure-paths).
     *
     * ★ 여기서 지키는 것은 <b>"하루 늦지 않는다"</b> 하나다. 정상 흐름(23:00 자동 취침 판정으로 마지막 칸이
     *   열리고 다음 날 아침에 조회)에서 하루가 밀려도 예외도 로그도 안 나고, 사용자만 하루를 더 기다린다.
     */
    @Nested
    @DisplayName("조각 등장 — 관측 시각이 아니라 해금 시각으로")
    class PiecesGate {

        /** 2층 8종이 다 열린 상태의 어린이(카운터를 채워 해금 규칙을 만족시킨다). */
        private ZzalPet layerTwoDone() {
            ZzalPet pet = child();
            org.springframework.test.util.ReflectionTestUtils.setField(pet, "id", PET_ID);
            for (String f : List.of("chatAnswers", "bathCount", "gameStarts", "sleepWakeCount", "zeroMissDays")) {
                org.springframework.test.util.ReflectionTestUtils.setField(pet, f, 12);
            }
            return pet;
        }

        private void rowsFor(ZzalPet pet) {
            MotionCatalog cat = new MotionCatalog("", "", "v1");
            List<com.lore.zzal.motion.ZzalMotion> rows = cat.all().stream()
                    .map(spec -> com.lore.zzal.motion.ZzalMotion.forCatalog(PET_ID, spec, T0)).toList();
            when(motionRepository.findByPetIdOrderBySeqAsc(PET_ID)).thenReturn(rows);
        }

        @Test
        @DisplayName("★★ 밤에 완성되면 다음 날 아침 첫 조회에 조각이 등장한다 — 하루 더 기다리지 않는다")
        void appearsOnTheVeryNextMorning() {
            ZzalPet pet = layerTwoDone();
            rowsFor(pet);

            // 09-05 낮에 이미 8종이 다 열린 상태로 한 번 정산(완성 시각이 그때로 적힌다)
            service.refresh(USER_ID, PET_ID, kst("2026-09-05 14:00"));
            assertThat(pet.isPiecesEnabled()).as("같은 날 낮에는 아직").isFalse();

            // 그 뒤 앱을 안 열다가 다음 날 12:00 에 연다 — 그 사이 23:00 취침·10:00 기상이 지나갔다
            service.refresh(USER_ID, PET_ID, kst("2026-09-06 12:00"));

            assertThat(pet.isPiecesEnabled()).as("그 기상을 놓치면 하루 늦는다").isTrue();
        }

        @Test
        @DisplayName("★ 완성된 그 자리에서는 안 열린다 — 기상을 한 번 지나야 한다")
        void notOnTheSameDay() {
            ZzalPet pet = layerTwoDone();
            rowsFor(pet);

            service.refresh(USER_ID, PET_ID, kst("2026-09-05 14:00"));
            service.refresh(USER_ID, PET_ID, kst("2026-09-05 20:00"));

            assertThat(pet.isPiecesEnabled()).isFalse();
        }
    }

    /**
     * 병 — 실패 주입(verify-failure-paths).
     *
     * ★ 여기서 지키는 것 셋 — (1) <b>케어 미스는 어디에도 안 내려간다</b>(정본 4장 "숨은 수치"),
     *   (2) 아플 때 거절되는 것과 되는 것이 정본 그대로인가, (3) 나은 연출이 <b>한 번만</b> 나오는가.
     */
    @Nested
    @DisplayName("병 — 거절·연출·미노출")
    class Sickness {

        /** 흔적을 6시간 방치해 아프게 만든다(정본 5장 100%). */
        private ZzalPet sickPet() {
            ZzalPet pet = child();
            org.springframework.test.util.ReflectionTestUtils.setField(pet, "id", PET_ID);
            service.refresh(USER_ID, PET_ID, kst("2026-09-06 15:00"));
            service.refresh(USER_ID, PET_ID, kst("2026-09-06 21:00"));
            assertThat(pet.isSick()).isTrue();
            return pet;
        }

        @Test
        @DisplayName("★★ 케어 미스는 응답 어디에도 없다 — 보이는 신호는 짐 가방뿐(정본 4장)")
        void careMissNeverLeaks() throws Exception {
            ZzalPet pet = sickPet();
            assertThat(pet.getCareMiss()).isPositive();          // 실제로 쌓여 있는데도

            PetResponses.Detail detail = PetResponses.Detail.from(
                    pet, null, kst("2026-09-06 21:00"), new MotionCatalog("", "", "v1"));
            String json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                    .writeValueAsString(detail);

            // 이름으로도 값으로도 안 나간다 — 이 검사가 곧 "숨은 수치" 의 정의다
            assertThat(json).doesNotContain("careMiss")
                    .doesNotContain("zeroMissDays")
                    .doesNotContain("MissArmed")
                    .doesNotContain("ZeroSec")
                    .doesNotContain("naturalSick");
            assertThat(json).contains("\"sick\"");             // 아픈 것 자체는 내려간다
        }

        @Test
        @DisplayName("★★ 아플 때 — 간식·게임은 거절, 밥·청소·목욕·약·쓰다듬기는 된다(정본 5·16장)")
        void refusalsWhileSick() {
            ZzalPet pet = sickPet();
            Instant now = kst("2026-09-06 21:00");

            assertCode(() -> service.care(USER_ID, PET_ID, CareAction.SNACK, now), ErrorCode.ZZAL_SICK_REFUSES);

            service.care(USER_ID, PET_ID, CareAction.CLEAN, now);        // 흔적이 있으니 된다
            service.care(USER_ID, PET_ID, CareAction.PET, now);
            pet.grantFood(now);
            service.care(USER_ID, PET_ID, CareAction.FEED, now);
            service.care(USER_ID, PET_ID, CareAction.BATH, now);
            assertThat(pet.isSick()).isTrue();                            // 여기까지는 여전히 아프다

            service.care(USER_ID, PET_ID, CareAction.MEDICINE, now);
            assertThat(pet.isSick()).isFalse();
        }

        @Test
        @DisplayName("★★ 나은 연출은 약을 먹은 그 응답에만 — 다음 조회에는 안 실린다")
        void justHealedOnlyOnce() {
            sickPet();
            Instant now = kst("2026-09-06 21:00");

            PetService.Action healed = service.care(USER_ID, PET_ID, CareAction.MEDICINE, now);
            assertThat(healed.justHealed()).isTrue();

            PetService.Action next = service.care(USER_ID, PET_ID, CareAction.PET, now);
            assertThat(next.justHealed()).isFalse();
        }

        @Test
        @DisplayName("안 아픈데 약을 주면 ZZAL_CARE_NOT_NEEDED — 연출도 없다")
        void medicineWhenHealthy() {
            child();
            assertCode(() -> service.care(USER_ID, PET_ID, CareAction.MEDICINE, T0), ErrorCode.ZZAL_CARE_NOT_NEEDED);
        }

        @Test
        @DisplayName("★ 자연 발병은 심화 행동이 도착할 때 예약된다 — 도착 전에는 예약이 없다")
        void naturalSicknessScheduledOnArrival() {
            ZzalPet pet = child();
            org.springframework.test.util.ReflectionTestUtils.setField(pet, "id", PET_ID);
            when(motionRepository.findByPetIdAndStatusAndRevealedAtIsNull(any(), any())).thenReturn(List.of());
            service.refresh(USER_ID, PET_ID, T0);
            assertThat(pet.getNaturalSickDueAwakeSec()).isNull();

            com.lore.zzal.motion.ZzalMotion gift = com.lore.zzal.motion.ZzalMotion.forCatalog(
                    PET_ID, new MotionCatalog("", "", "v1").bySeq(101).orElseThrow(), T0);
            gift.toReview("k", com.lore.zzal.motion.MotionSource.API,
                    com.lore.zzal.motion.GateVerdict.REVIEW, "n", "g0");
            gift.approve(T0);
            when(motionRepository.findByPetIdAndStatusAndRevealedAtIsNull(
                    eq(PET_ID), eq(com.lore.zzal.motion.MotionStatus.OPEN)))
                    .thenAnswer(i -> gift.getRevealedAt() == null ? List.of(gift) : List.of());

            service.refresh(USER_ID, PET_ID, T0);

            assertThat(gift.getRevealedAt()).isNotNull();
            assertThat(pet.getNaturalSickDueAwakeSec()).isNotNull();      // 그때 예약된다
        }
    }

    /**
     * 아침 공개 — 실패 주입(verify-failure-paths).
     *
     * ★ 이 분기는 정상 경로에서 <b>한 번도 안 도는</b> 종류다: "자는 중이면 안 준다" 는 자는 펫을 만들어야 돌고,
     *   "10:00 을 넘겨 판정된 것은 낮에 준다" 는 늦은 판정을 만들어야 돈다.
     */
    @Nested
    @DisplayName("아침 공개 — 검수 통과분이 언제 도착하나")
    class Reveal {

        /** 정산 대상 펫 — 조회가 동작 행을 찾으려면 id 가 있어야 한다(실제 DB 행에는 늘 있다). */
        private ZzalPet childWithId() {
            ZzalPet pet = child();
            org.springframework.test.util.ReflectionTestUtils.setField(pet, "id", PET_ID);
            return pet;
        }

        /** 검수까지 통과한(OPEN) 선물 1 행. 아직 도착 전. */
        private com.lore.zzal.motion.ZzalMotion approvedGift() {
            com.lore.zzal.motion.ZzalMotion m = com.lore.zzal.motion.ZzalMotion.forCatalog(
                    PET_ID, new MotionCatalog("", "", "v1").bySeq(101).orElseThrow(), T0);
            m.toReview("images/zzal/pets/7/motions/9/motion.webp", com.lore.zzal.motion.MotionSource.API,
                    com.lore.zzal.motion.GateVerdict.REVIEW, "n", "g0");
            m.approve(T0);
            when(motionRepository.findByPetIdAndStatusAndRevealedAtIsNull(
                    eq(PET_ID), eq(com.lore.zzal.motion.MotionStatus.OPEN)))
                    .thenAnswer(i -> m.getRevealedAt() == null ? List.of(m) : List.of());
            return m;
        }

        @Test
        @DisplayName("★ 잠든 채 검수를 통과하면 안 준다 — 깨어 있는 첫 조회에서 준다")
        void notWhileSleeping() {
            ZzalPet pet = childWithId();
            com.lore.zzal.motion.ZzalMotion gift = approvedGift();

            // 23:00 자동 취침을 지나 새벽 — 자는 중이다
            service.refresh(USER_ID, PET_ID, kst("2026-09-06 02:00"));
            assertThat(pet.isSleeping()).isTrue();
            assertThat(gift.getRevealedAt()).isNull();

            // 10:00 자동 기상을 지난 첫 조회 — 그때 도착한다
            service.refresh(USER_ID, PET_ID, kst("2026-09-06 10:30"));
            assertThat(pet.isSleeping()).isFalse();
            assertThat(gift.getRevealedAt()).isNotNull();
            assertThat(gift.advancedImageKey()).endsWith("/motion.webp");
        }

        @Test
        @DisplayName("★ 10시를 넘겨 판정돼도 그날 낮 조회에서 도착한다(늦잠 강제 없음, 정본 16장)")
        void arrivesInTheAfternoon() {
            childWithId();
            com.lore.zzal.motion.ZzalMotion gift = approvedGift();

            service.refresh(USER_ID, PET_ID, kst("2026-09-06 15:00"));

            assertThat(gift.getRevealedAt()).isNotNull();
        }

        @Test
        @DisplayName("★★ 깨우기 응답에 아침 도착이 실린다 — 다음 조회를 기다리게 하면 안 된다")
        void arrivesInTheWakeResponse() {
            ZzalPet pet = childWithId();
            com.lore.zzal.motion.ZzalMotion gift = approvedGift();

            // 23:00 자동 취침을 지나 아침 07:30 — 자는 중이라 아직 안 왔다
            service.refresh(USER_ID, PET_ID, kst("2026-09-06 07:30"));
            assertThat(pet.isSleeping()).isTrue();
            assertThat(gift.getRevealedAt()).isNull();

            service.wake(USER_ID, PET_ID, kst("2026-09-06 07:30"));

            // ★ 깨우는 그 응답에서 도착해야 한다("행동 응답 = 최신 상태")
            assertThat(pet.isSleeping()).isFalse();
            assertThat(gift.getRevealedAt()).isNotNull();
            assertThat(gift.advancedImageKey()).endsWith("/motion.webp");
        }

        @Test
        @DisplayName("★ 도착은 한 번만 찍힌다 — 다시 조회해도 시각이 바뀌지 않는다")
        void revealOnce() {
            childWithId();
            com.lore.zzal.motion.ZzalMotion gift = approvedGift();

            service.refresh(USER_ID, PET_ID, kst("2026-09-06 15:00"));
            Instant first = gift.getRevealedAt();
            service.refresh(USER_ID, PET_ID, kst("2026-09-06 16:00"));

            assertThat(gift.getRevealedAt()).isEqualTo(first);
        }

        @Test
        @DisplayName("★ 도착하지 않은 동작에 \"확인\" 을 누르면 ZZAL_MOTION_NOT_OPEN")
        void seenBeforeArrival() {
            childWithId();
            com.lore.zzal.motion.ZzalMotion gift = com.lore.zzal.motion.ZzalMotion.forCatalog(
                    PET_ID, new MotionCatalog("", "", "v1").bySeq(101).orElseThrow(), T0);
            when(motionRepository.findByPetIdAndSeq(PET_ID, 101)).thenReturn(Optional.of(gift));
            when(motionRepository.findByPetIdAndStatusAndRevealedAtIsNull(any(), any())).thenReturn(List.of());

            assertCode(() -> service.markSeen(USER_ID, PET_ID, 101, T0), ErrorCode.ZZAL_MOTION_NOT_OPEN);
            assertThat(gift.getSeenAt()).isNull();
        }
    }
}
