package com.lore.zzal.pet;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.s3.S3Service;
import com.lore.common.user.User;
import com.lore.common.user.UserRepository;
import com.lore.zzal.PetFixture;
import com.lore.zzal.PieceFixture;
import com.lore.zzal.generation.GenJobRepository;
import com.lore.zzal.generation.GenStepRecordRepository;
import com.lore.zzal.generation.HatchService;
import com.lore.zzal.generation.StepLabels;
import com.lore.zzal.leave.LeaveService;
import com.lore.zzal.leave.ZzalPostcardRepository;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.MotionSeeder;
import com.lore.zzal.motion.ZzalMotionRepository;
import com.lore.zzal.night.BakeTrigger;
import com.lore.zzal.piece.PieceCompleted;
import com.lore.zzal.piece.PieceEvent;
import com.lore.zzal.piece.PieceKind;
import com.lore.zzal.piece.PieceService;
import com.lore.zzal.piece.ZzalPiece;
import com.lore.zzal.scene.SceneService;
import com.lore.zzal.scene.ZzalScene;
import com.lore.zzal.scene.ZzalSceneRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.lore.zzal.pet.AwakeClockTest.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 펫 서비스가 <b>밖으로 넘기는 것들</b> — 굽기 트리거 · 초안 재사용 · 조각 입구 · 기상 정산 순서.
 *
 * <h3>★ 왜 따로 있나</h3>
 * {@code PetServiceTest} 는 {@link BakeTrigger} 와 조각을 목으로 넣어 두고 <b>한 번도 안 본다.</b>
 * 그래서 "규칙은 맞는데 <b>아무한테도 안 넘긴다</b>" 는 종류가 통째로 사각지대였다 —
 * 재웠는데 아침에 아무것도 안 배워 오거나, 조각이 다 찼는데 굽기가 안 시작되는 모양이다.
 * 여기서는 그 둘을 <b>붙잡아 두고</b> 실제로 불리는지 센다.
 */
@DisplayName("펫 서비스 — 밖으로 넘기는 것들")
class PetServiceWiringTest {

    private static final Instant T0 = kst("2026-09-05 12:00");
    private static final Long USER_ID = 1L;
    private static final Long PET_ID = 7L;

    private ZzalPetRepository petRepository;
    private UserRepository userRepository;
    private S3Service s3Service;
    private GenJobRepository jobRepository;
    private HatchService hatchService;
    private ApplicationEventPublisher events;

    /** ★ 목으로 죽여 두지 않고 붙잡아 둔다 — 이 시험이 보려는 것이 "정말 불리는가" 다. */
    private BakeTrigger bakeTrigger;
    /** 조각은 진짜 서비스 + 메모리 저장소. 발행까지 세려고 알림판도 붙잡는다. */
    private final Map<Long, ZzalPiece> pieces = new HashMap<>();
    private ApplicationEventPublisher pieceEvents;

    private PetService service;

    @BeforeEach
    void setUp() {
        petRepository = mock(ZzalPetRepository.class);
        userRepository = mock(UserRepository.class);
        s3Service = mock(S3Service.class);
        jobRepository = mock(GenJobRepository.class);
        hatchService = mock(HatchService.class);
        events = mock(ApplicationEventPublisher.class);
        bakeTrigger = mock(BakeTrigger.class);
        pieceEvents = mock(ApplicationEventPublisher.class);
        pieces.clear();

        ZzalMotionRepository motionRepository = mock(ZzalMotionRepository.class);
        when(motionRepository.findByPetIdOrderBySeqAsc(anyLong())).thenReturn(List.of());

        service = new PetService(
                petRepository, jobRepository, mock(GenStepRecordRepository.class), mock(StepLabels.class),
                userRepository, s3Service, hatchService, events,
                new MotionCatalog("", "", "v1"), motionRepository, mock(MotionSeeder.class),
                bakeTrigger, sceneService(), new LeaveService(postcards()),
                PieceFixture.inMemory(pieces, pieceEvents));
    }

    private SceneService sceneService() {
        ZzalSceneRepository repository = mock(ZzalSceneRepository.class);
        List<ZzalScene> stored = new ArrayList<>();
        when(repository.save(any())).thenAnswer(i -> {
            ZzalScene s = i.getArgument(0);
            ReflectionTestUtils.setField(s, "id", (long) (stored.size() + 1));
            stored.add(s);
            return s;
        });
        when(repository.findByPetIdOrderBySceneAtDescIdDesc(any())).thenAnswer(i -> stored.stream()
                .sorted(Comparator.comparing(ZzalScene::getSceneAt).thenComparing(ZzalScene::getId).reversed())
                .toList());
        org.mockito.Mockito.doAnswer(i -> {
            stored.remove(i.<ZzalScene>getArgument(0));
            return null;
        }).when(repository).delete(any());
        return new SceneService(repository, new MotionCatalog("", "", "v1"));
    }

    private ZzalPostcardRepository postcards() {
        ZzalPostcardRepository repository = mock(ZzalPostcardRepository.class);
        when(repository.findByPetIdOrderByWrittenAtAscIdAsc(any())).thenReturn(List.of());
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        return repository;
    }

    /** 튜토리얼을 건너뛴(시계가 켜진) 펫. */
    private ZzalPet baby() {
        ZzalPet pet = PetFixture.hatching(USER_ID, "여울", null, "images/zzal/abc", T0);
        pet.markAlive("images/zzal/sheet", "생김새", T0);
        pet.skipTutorial(T0);
        register(pet);
        return pet;
    }

    /** 튜토리얼 중인 펫 — 시계가 아직 안 켜졌다. */
    private ZzalPet inTutorial() {
        ZzalPet pet = PetFixture.hatching(USER_ID, "여울", null, "images/zzal/abc", T0);
        pet.markAlive("images/zzal/sheet", "생김새", T0);
        register(pet);
        return pet;
    }

    private void register(ZzalPet pet) {
        ReflectionTestUtils.setField(pet, "id", PET_ID);
        when(petRepository.findById(PET_ID)).thenReturn(Optional.of(pet));
        when(petRepository.findByIdForUpdate(PET_ID)).thenReturn(Optional.of(pet));
    }

    private static void assertCode(Runnable r, ErrorCode code) {
        assertThatThrownBy(r::run)
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", code);
    }

    // ══ M-2b. 굽기 트리거가 정말 불리는가 ═════════════════════════════════

    @Nested
    @DisplayName("굽기 트리거 — 끊기면 아침에 아무것도 안 배워 온다")
    class BakeHandoff {

        @Test
        @DisplayName("★★ 밤잠에 들면 굽기 트리거가 정확히 1회 — 이 줄이 끊기면 재웠는데 아무 일도 안 일어난다")
        void nightSleepTriggersTheBakeOnce() {
            ZzalPet pet = baby();
            Instant at = kst("2026-09-05 19:00");

            service.sleep(USER_ID, PET_ID, at);

            assertThat(pet.getSleepKind()).isEqualTo(SleepKind.NIGHT);
            verify(bakeTrigger, times(1)).onSleep(eq(pet), any());
            verify(bakeTrigger, never()).onTutorialDone(any(), any());
        }

        @Test
        @DisplayName("★★ 낮잠에는 안 불린다 — 불리면 하루에 여러 번 굽기가 나가 돈이 샌다")
        void napDoesNotTriggerTheBake() {
            ZzalPet pet = inTutorial();
            PetFixture.readyForNap(pet);

            service.sleep(USER_ID, PET_ID, T0.plus(Duration.ofMinutes(40)));

            assertThat(pet.getSleepKind()).isEqualTo(SleepKind.NAP);
            verify(bakeTrigger, never()).onSleep(any(), any());
        }

        @Test
        @DisplayName("★ 재우기가 거절되면 굽기도 안 나간다 — 창 밖에서 눌러도 돈이 안 나가야 한다")
        void refusedSleepTriggersNothing() {
            baby();

            assertCode(() -> service.sleep(USER_ID, PET_ID, kst("2026-09-05 15:00")),
                    ErrorCode.ZZAL_NOT_SLEEP_TIME);

            verify(bakeTrigger, never()).onSleep(any(), any());
        }
    }

    // ══ M-17(가). 튜토리얼 마지막 칸 ═════════════════════════════════════

    @Nested
    @DisplayName("튜토리얼 마지막 칸 — 시계를 켜고 구르기를 굽는다")
    class TutorialDone {

        @Test
        @DisplayName("★ 8칸까지만 채우고 누르면 ZZAL_TUTORIAL_NOT_FINISHED — 시계도 굽기도 안 켜진다")
        void eightStepsIsNotEnough() {
            ZzalPet pet = inTutorial();
            PetFixture.atTutorialStep(pet, TutorialSchedule.Step.NAP);      // 8칸째에 서 있다

            assertCode(() -> service.tutorialDone(USER_ID, PET_ID, T0), ErrorCode.ZZAL_TUTORIAL_NOT_FINISHED);

            assertThat(pet.isInTutorial()).isTrue();
            verify(bakeTrigger, never()).onTutorialDone(any(), any());
        }

        @Test
        @DisplayName("★★ 9칸을 채우고 누르면 시계가 켜지고 구르기가 그 자리에서 굽기로 넘어간다")
        void ninthStepStartsTheClockAndTheGift() {
            ZzalPet pet = inTutorial();
            PetFixture.atTutorialStep(pet, TutorialSchedule.Step.DONE);     // 9칸째 = 마지막

            service.tutorialDone(USER_ID, PET_ID, T0);

            assertThat(pet.isInTutorial()).as("시계가 켜졌다").isFalse();
            assertThat(pet.getClockStartedAt()).isNotNull();
            verify(bakeTrigger, times(1)).onTutorialDone(eq(pet), any());
        }

        @Test
        @DisplayName("★ 이미 끝난 뒤 또 누르면 ZZAL_TUTORIAL_ALREADY_DONE — 구르기를 두 번 굽지 않는다")
        void pressingAgainIsRefused() {
            ZzalPet pet = inTutorial();
            PetFixture.atTutorialStep(pet, TutorialSchedule.Step.DONE);
            service.tutorialDone(USER_ID, PET_ID, T0);

            assertCode(() -> service.tutorialDone(USER_ID, PET_ID, T0), ErrorCode.ZZAL_TUTORIAL_ALREADY_DONE);

            verify(bakeTrigger, times(1)).onTutorialDone(any(), any());
        }
    }

    // ══ M-5(가). 기존 초안 재사용 ════════════════════════════════════════

    @Nested
    @DisplayName("기존 초안 — 나갔다 오면 또 굽는가")
    class DraftReuse {

        private void allowCreate() {
            User user = mock(User.class);
            when(user.getPetSlots()).thenReturn(1);
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(hatchService.currentVersion()).thenReturn("v1");
            when(petRepository.countByUserIdAndPhaseIn(eq(USER_ID), any())).thenReturn(0L);
            when(petRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        }

        @Test
        @DisplayName("★★ 이름을 안 짓고 나간 사람이 그림을 또 올리면 그 초안을 그대로 준다 — 새로 구우면 한 판 값이 그냥 나간다")
        void existingDraftIsReturnedAsIs() {
            allowCreate();
            ZzalPet existing = ZzalPet.draft(USER_ID, "images/zzal/first", T0);
            ReflectionTestUtils.setField(existing, "id", 99L);
            when(petRepository.findFirstByUserIdAndPhase(USER_ID, PetPhase.DRAFT))
                    .thenReturn(Optional.of(existing));

            ZzalPet result = service.draft(USER_ID, "images/zzal/second", T0.plusSeconds(600));

            assertThat(result).isSameAs(existing);
            assertThat(result.getSourceImageKey()).as("먼저 올린 그림을 그대로 쓴다").isEqualTo("images/zzal/first");
            verify(jobRepository, never()).save(any());              // 굽기 작업이 새로 안 생긴다
            verify(petRepository, never()).save(any());
            verify(s3Service, never()).consume(anyLong(), any(), any());
            verify(events, never()).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("초안이 없으면 평소대로 만들고 굽기를 시작한다 — 위 시험이 '아무것도 안 한다' 로 통과하지 않게")
        void withoutAnExistingDraftItStillCreates() {
            allowCreate();
            when(petRepository.findFirstByUserIdAndPhase(eq(USER_ID), any())).thenReturn(Optional.empty());

            service.draft(USER_ID, "images/zzal/first", T0);

            verify(jobRepository, times(1)).save(any());
            verify(s3Service, times(1)).consume(eq(USER_ID), eq("images/zzal/first"), any());
            verify(events, times(1)).publishEvent(any(Object.class));
        }
    }

    // ══ M-16. 이름을 낼 수 없는 상태 ═════════════════════════════════════

    @Nested
    @DisplayName("이름 제출 — 초안이 아닐 때")
    class Naming {

        @Test
        @DisplayName("★★ 굽기가 실패한 펫에는 ZZAL_PET_HATCH_FAILED — '이미 이름을 지었다' 가 아니다")
        void hatchFailedIsToldAsItIs() {
            ZzalPet pet = ZzalPet.draft(USER_ID, "images/zzal/abc", T0);
            register(pet);
            pet.markHatchFailed();
            assertThat(pet.getPhase()).isEqualTo(PetPhase.FAILED);

            assertCode(() -> service.character(USER_ID, PET_ID, "여울", null, null, null, null, null, T0),
                    ErrorCode.ZZAL_PET_HATCH_FAILED);

            verify(events, never()).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("★ 이미 살아난 펫에는 ZZAL_PET_NOT_DRAFT — 이름을 덮어쓸 수 없다")
        void aliveIsNotADraft() {
            baby();

            assertCode(() -> service.character(USER_ID, PET_ID, "다른이름", null, null, null, null, null, T0),
                    ErrorCode.ZZAL_PET_NOT_DRAFT);

            verify(events, never()).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("초안이면 이름이 들어가고 알림이 정확히 1회 — 위 둘이 '언제나 거절' 로 통과하지 않게")
        void draftIsNamedAndAnnouncedOnce() {
            ZzalPet pet = ZzalPet.draft(USER_ID, "images/zzal/abc", T0);
            register(pet);

            service.character(USER_ID, PET_ID, "여울", null, null, null, null, null, T0);

            assertThat(pet.getName()).isEqualTo("여울");
            assertThat(pet.getPhase()).isEqualTo(PetPhase.HATCHING);
            verify(events, times(1)).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("★ 말투·장르도 같이 저장된다 — 컨트롤러에서 여기까지 오는 길이 끊기면 조용히 비어 있다")
        void toneAndGenreAreStored() {
            ZzalPet pet = ZzalPet.draft(USER_ID, "images/zzal/abc", T0);
            register(pet);

            service.character(USER_ID, PET_ID, "여울", null, null,
                    "비 오는 도시의 탐정", "무뚝뚝한 존댓말", "느와르", T0);

            assertThat(pet.getWorld()).isEqualTo("비 오는 도시의 탐정");
            assertThat(pet.getTone()).isEqualTo("무뚝뚝한 존댓말");
            assertThat(pet.getGenre()).isEqualTo("느와르");
        }
    }

    // ══ M-18. 조각을 올리는 입구 — 돌보기 쪽 다섯 ════════════════════════

    @Nested
    @DisplayName("조각 입구 — 돌보기 다섯이 정말 올리는가")
    class CareEntrances {

        /** 3층이 열린 펫. 게이지를 비워 돌보기가 거절되지 않게 둔다. */
        private ZzalPet tierThree() {
            Instant hatched = T0.minus(Duration.ofHours(24));
            ZzalPet pet = PetFixture.hatching(USER_ID, "여울", null, "images/zzal/abc", hatched);
            pet.markAlive("images/zzal/sheet", "생김새", hatched);
            pet.skipTutorial(hatched);
            pet.settle(T0);                       // 배부름 0 · 행복 0 · 흔적 4
            register(pet);
            pet.enablePieces(T0);
            return pet;
        }

        private ZzalPiece row() {
            return pieces.get(PET_ID);
        }

        @Test
        @DisplayName("★★ 밥 — 요구량에 닿는 그 한 번이 밥 칸을 찍는다(거절된 밥은 안 센다)")
        void feedingStampsOnlyWhenItSucceeds() {
            ZzalPet pet = tierThree();
            int need = ZzalRules.PIECE_FEEDS;

            for (int i = 0; i < need; i++) {
                pet.grantFood(T0);                                  // 재고를 채워 가며
                service.care(USER_ID, PET_ID, CareAction.FEED, T0);
                if (i < need - 1) {
                    assertThat(row().isDone(PieceKind.FOOD)).as("%d 번째까지는 아직", i + 1).isFalse();
                }
                ReflectionTestUtils.setField(pet, "fullness", 0);   // 다음 밥이 거절되지 않게 비운다
            }
            assertThat(row().isDone(PieceKind.FOOD)).isTrue();

            // 배부른 상태에서 또 먹이면 거절이고, 거절은 조각에 닿지 않는다.
            ReflectionTestUtils.setField(pet, "fullness", ZzalRules.GAUGE_MAX);
            pet.grantFood(T0);
            int before = row().countOf(PieceEvent.FEED);
            assertCode(() -> service.care(USER_ID, PET_ID, CareAction.FEED, T0), ErrorCode.ZZAL_CARE_NOT_NEEDED);
            assertThat(row().countOf(PieceEvent.FEED)).isEqualTo(before);
        }

        @Test
        @DisplayName("★ 청소 — 흔적이 있을 때만 세고, 바닥이 깨끗하면 거절이라 안 센다")
        void cleaningCountsOnlyWithTrash() {
            ZzalPet pet = tierThree();

            service.care(USER_ID, PET_ID, CareAction.CLEAN, T0);
            assertThat(row().countOf(PieceEvent.CLEAN)).isEqualTo(1);

            ReflectionTestUtils.setField(pet, "trash", 0);
            assertCode(() -> service.care(USER_ID, PET_ID, CareAction.CLEAN, T0), ErrorCode.ZZAL_CARE_NOT_NEEDED);
            assertThat(row().countOf(PieceEvent.CLEAN)).isEqualTo(1);
        }

        @Test
        @DisplayName("★ 목욕 — 하루 한 번만 세고 두 번째는 거절이라 안 센다")
        void bathingCountsOncePerDay() {
            tierThree();

            service.care(USER_ID, PET_ID, CareAction.BATH, T0);
            assertThat(row().countOf(PieceEvent.BATH)).isEqualTo(1);

            assertCode(() -> service.care(USER_ID, PET_ID, CareAction.BATH, T0), ErrorCode.ZZAL_BATH_DONE_TODAY);
            assertThat(row().countOf(PieceEvent.BATH)).isEqualTo(1);
        }

        @Test
        @DisplayName("★★ 쓰다듬기 — 하루 3회까지만 센다. 거절이 없어 그대로 두면 연타로 채울 수 있다")
        void pettingStopsCountingAfterThreePerDay() {
            tierThree();

            for (int i = 0; i < 10; i++) {
                service.care(USER_ID, PET_ID, CareAction.PET, T0);
            }

            assertThat(row().countOf(PieceEvent.PET))
                    .as("친밀도가 멈추는 선과 같은 선에서 멈춘다")
                    .isEqualTo(ZzalRules.PET_INTIMACY_PER_DAY);
            assertThat(row().isDone(PieceKind.BOND)).isFalse();
        }

        @Test
        @DisplayName("★★ 배탈이 나는 그 간식(그날 5개째부터)은 조각에 안 센다")
        void theSnackThatUpsetsDoesNotCount() {
            ZzalPet pet = tierThree();
            int allowed = ZzalRules.SNACK_DAILY_SICK_AT - 1;

            for (int i = 0; i < allowed; i++) {
                service.care(USER_ID, PET_ID, CareAction.SNACK, T0);
            }
            assertThat(row().countOf(PieceEvent.SNACK)).isEqualTo(allowed);

            service.care(USER_ID, PET_ID, CareAction.SNACK, T0);      // 배탈이 나는 그 한 개
            assertThat(row().countOf(PieceEvent.SNACK))
                    .as("배탈 간식으로 놀이 조각을 채울 수는 없다")
                    .isEqualTo(allowed);
            assertThat(pet.isSick()).isTrue();
        }

        @Test
        @DisplayName("★ 3층 전에는 어느 돌보기도 조각 줄을 만들지 않는다")
        void beforeTierThreeCareCountsNothing() {
            Instant hatched = T0.minus(Duration.ofHours(24));
            ZzalPet pet = PetFixture.hatching(USER_ID, "여울", null, "images/zzal/abc", hatched);
            pet.markAlive("images/zzal/sheet", "생김새", hatched);
            pet.skipTutorial(hatched);
            pet.settle(T0);
            register(pet);
            assertThat(pet.isPiecesEnabled()).isFalse();

            pet.grantFood(T0);
            service.care(USER_ID, PET_ID, CareAction.FEED, T0);
            service.care(USER_ID, PET_ID, CareAction.PET, T0);
            service.care(USER_ID, PET_ID, CareAction.CLEAN, T0);

            assertThat(pieces).isEmpty();
        }
    }

    // ══ M-25. 기상 아침의 정산 순서 ══════════════════════════════════════

    @Nested
    @DisplayName("기상 정산 — 되돌린 뒤에 선물")
    class WakeSettleOrder {

        /** 어젯밤에 네 칸을 다 채우고 잠든 펫 — 기분 좋은 날 쪽지까지 달려 있다. */
        private ZzalPet asleepWithFullPieces() {
            Instant hatched = kst("2026-09-04 12:00");
            ZzalPet pet = PetFixture.hatching(USER_ID, "여울", null, "images/zzal/abc", hatched);
            pet.markAlive("images/zzal/sheet", "생김새", hatched);
            pet.skipTutorial(hatched);
            register(pet);
            pet.settle(kst("2026-09-05 19:00"));
            pet.enablePieces(kst("2026-09-05 19:00"));

            ZzalPiece row = ZzalPiece.of(PET_ID);
            for (PieceKind kind : PieceKind.values()) {
                row.grant(kind);
            }
            // ★ 되돌리기는 <b>쓰인</b> 완성에만 일어난다 — 굽기가 가져가지 않은 판을 비우면
            //   이틀 걸려 채운 조각이 아무것도 남기지 않고 사라진다(ZzalPiece.resetOnWakeIfComplete).
            assertThat(row.isComplete()).isTrue();
            assertThat(row.consume()).as("그날 밤 굽기가 이 완성을 가져갔다").isTrue();
            pieces.put(PET_ID, row);

            pet.sleep(kst("2026-09-05 19:00"));
            ReflectionTestUtils.setField(pet, "goodDayPending", true);
            return pet;
        }

        @Test
        @DisplayName("★★ 되돌린 뒤에 선물 — 다 찬 판이 0 으로 돌아가고 <b>앞선 칸 하나</b>만 남는다")
        void resetThenBonus() {
            ZzalPet pet = asleepWithFullPieces();

            // 10:00 을 지나 조회하면 자동 기상이 그 정산 안에서 일어나고, 조각 쪽지도 같은 호출에서 처리된다.
            service.refresh(USER_ID, PET_ID, kst("2026-09-06 11:00"));

            ZzalPiece row = pieces.get(PET_ID);
            assertThat(row.isDone(PieceKind.FOOD))
                    .as("선물은 아직 안 찬 것 중 앞선 칸 — 반대 순서였다면 이 도장이 지워진다")
                    .isTrue();
            assertThat(row.isDone(PieceKind.PLAY)).isFalse();
            assertThat(row.isDone(PieceKind.CLEAN)).isFalse();
            assertThat(row.isDone(PieceKind.BOND)).isFalse();
            assertThat(row.doneCount()).isEqualTo(1);
            assertThat(row.isComplete()).isFalse();
        }

        @Test
        @DisplayName("★ 그 아침에는 굽기 알림이 안 나간다 — 선물 한 칸으로는 판이 안 찬다")
        void oneBonusDoesNotCompleteTheBoard() {
            asleepWithFullPieces();

            service.refresh(USER_ID, PET_ID, kst("2026-09-06 11:00"));

            verify(pieceEvents, never()).publishEvent(any(PieceCompleted.class));
        }

        @Test
        @DisplayName("★ 쪽지는 한 번만 쓰인다 — 다음 조회에서 또 되돌리거나 또 선물하지 않는다")
        void theNoteIsConsumedOnce() {
            ZzalPet pet = asleepWithFullPieces();
            service.refresh(USER_ID, PET_ID, kst("2026-09-06 11:00"));

            assertThat(pet.isPieceResetPending()).isFalse();
            assertThat(pet.isPieceBonusPending()).isFalse();

            service.refresh(USER_ID, PET_ID, kst("2026-09-06 11:30"));

            assertThat(pieces.get(PET_ID).doneCount()).as("두 번째 조회가 또 선물하지 않는다").isEqualTo(1);
        }

        @Test
        @DisplayName("★ 기분 좋은 날이 아니면 되돌리기만 — 선물 없이 네 칸이 0 이다")
        void resetWithoutBonus() {
            ZzalPet pet = asleepWithFullPieces();
            ReflectionTestUtils.setField(pet, "goodDayPending", false);

            service.refresh(USER_ID, PET_ID, kst("2026-09-06 11:00"));

            assertThat(pieces.get(PET_ID).doneCount()).isZero();
        }
    }
}
