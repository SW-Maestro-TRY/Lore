package com.lore.zzal.game;

import com.lore.zzal.PetFixture;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.pet.PetService;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalPetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.lore.zzal.pet.AwakeClockTest.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 미니게임 v2 — 합산 3판·잠들 때 리셋·달리기 해금·30초 승리(정본 7·16장).
 *
 * ★ 하루 판수의 정본이 표(달력일)가 아니라 펫 카운터라는 것이 v1 과의 차이다. 자정이 아니라
 *   잠드는 순간에 0 이 된다.
 */
@DisplayName("미니게임 v2 — 합산 3판·달리기")
class GameServiceTest {

    private static final Instant T0 = kst("2026-09-05 12:00");
    private static final Long USER = 1L;
    private static final Long PET = 7L;
    private static final com.lore.zzal.motion.MotionCatalog CATALOG = new com.lore.zzal.motion.MotionCatalog("", "", "v1");

    private ZzalGameRepository gameRepository;
    private PetService petService;
    private GameService service;
    private ZzalPet pet;
    private com.lore.zzal.night.BakeTrigger bakeTrigger;

    @BeforeEach
    void setUp() {
        gameRepository = mock(ZzalGameRepository.class);
        when(gameRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(gameRepository.findFirstByPetIdAndFinishedAtIsNullOrderByIdDesc(anyLong())).thenReturn(Optional.empty());

        pet = PetFixture.hatching(USER, "여울", null, "k", T0);
        pet.markAlive("s", "i", T0);
        pet.skipTutorial(T0);
        ReflectionTestUtils.setField(pet, "id", PET);   // JPA 가 줄 번호를 테스트가 대신 준다
        petService = mock(PetService.class);
        when(petService.awake(any(), any(), any())).thenAnswer(inv -> {
            pet.settle(pet.now(inv.getArgument(2)));
            return pet;
        });
        when(petService.withUnlockDiff(any(), any())).thenAnswer(inv -> {
            java.util.Set<String> before = java.util.Set.copyOf(com.lore.zzal.pet.UnlockRules.unlockedKeys(pet, CATALOG));
            ((Runnable) inv.getArgument(1)).run();
            List<Integer> opened = com.lore.zzal.pet.UnlockRules.unlockedKeys(pet, CATALOG).stream()
                    .filter(k -> !before.contains(k)).map(k -> CATALOG.byKey(k).orElseThrow().seq()).sorted().toList();
            return new PetService.Action(pet, opened);
        });

        RewardService rewards = new RewardService(mock(ZzalPetRepository.class), RewardKind.NONE, RewardKind.HAPPINESS);
        bakeTrigger = mock(com.lore.zzal.night.BakeTrigger.class);
        service = new GameService(gameRepository, petService, rewards,
                com.lore.zzal.PieceFixture.inMemory(), bakeTrigger, 3);
    }

    @Test
    @DisplayName("★ 두 게임 합쳐 하루 3판 — 4번째는 ZZAL_GAME_DAILY_LIMIT, 시작한 판 기준")
    void threePerDayCombined() {
        // ★ 놀라기(15)는 네 판이라 하루 상한(3) 안에서는 안 열린다 — 이튿날 첫 판에 열린다.
        assertThat(service.start(USER, PET, GameKind.LEFT_RIGHT, T0).justUnlocked()).isEmpty();
        service.start(USER, PET, GameKind.LEFT_RIGHT, T0);
        assertThat(service.start(USER, PET, GameKind.LEFT_RIGHT, T0).justUnlocked()).isEmpty();
        assertThat(pet.getTodayGames()).isEqualTo(3);
        assertThat(pet.getGameStarts()).isEqualTo(3);
        assertThat(service.remainingToday(pet)).isZero();
        assertThatThrownBy(() -> service.start(USER, PET, GameKind.LEFT_RIGHT, T0))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_GAME_DAILY_LIMIT);
    }

    @Test
    @DisplayName("★ 잠들 때 리셋 — 밤잠에 들면 다시 3판")
    void resetAtSleep() {
        for (int i = 0; i < 3; i++) {
            service.start(USER, PET, GameKind.LEFT_RIGHT, T0);
        }
        pet.settle(kst("2026-09-05 19:00"));
        pet.sleep(kst("2026-09-05 19:00"));
        assertThat(pet.getTodayGames()).isZero();
        assertThat(service.remainingToday(pet)).isEqualTo(3);
        assertThat(pet.getGameStarts()).isEqualTo(3);           // 누적(2층 놀라기 조건)은 남는다
    }

    @Test
    @DisplayName("★★ 네 번째 판을 시작하는 그 자리에서 놀라기(15)가 열린다 — 하루 3판이라 이튿날이다")
    void theFourthGameOpensStartle() {
        for (int i = 0; i < 3; i++) {
            service.start(USER, PET, GameKind.LEFT_RIGHT, T0);
        }
        pet.settle(kst("2026-09-05 19:00"));
        pet.sleep(kst("2026-09-05 19:00"));
        pet.wake(kst("2026-09-06 08:00"));

        GameService.Started fourth = service.start(USER, PET, GameKind.LEFT_RIGHT, kst("2026-09-06 11:00"));

        assertThat(pet.getGameStarts()).isEqualTo(4);
        assertThat(fourth.justUnlocked())
                .as("네 판째가 조건이다 — 폭죽은 그 행동의 응답에 실려야 한다")
                .containsExactly(15);
    }

    @Test
    @DisplayName("달리기는 좌우 5승 뒤 — 그 전엔 ZZAL_FEATURE_LOCKED")
    void runLockedUntilFiveWins() {
        assertThatThrownBy(() -> service.start(USER, PET, GameKind.RUN, T0))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_FEATURE_LOCKED);
        for (int i = 0; i < 5; i++) {
            pet.winLeftRight();
        }
        GameService.Started run = service.start(USER, PET, GameKind.RUN, T0);
        assertThat(run.game().getKind()).isEqualTo(GameKind.RUN);
        assertThat(run.runUnlocked()).isTrue();
    }

    @Test
    @DisplayName("달리기 — 30초 이상이면 승리·행복 +1, 상한 60초로 잘림, 두 번 끝내면 ZZAL_GAME_FINISHED")
    void runFinish() {
        for (int i = 0; i < 5; i++) {
            pet.winLeftRight();
        }
        ZzalGame run = service.start(USER, PET, GameKind.RUN, T0).game();
        when(gameRepository.findByIdForUpdate(any())).thenReturn(Optional.of(run));
        int happiness = pet.getHappiness();

        GameService.RunResult r = service.finish(USER, PET, 99L, 70_000, T0);
        assertThat(r.win()).isTrue();
        assertThat(r.runUnlocked()).isTrue();
        assertThat(run.getSurvivedMs()).isEqualTo(60_000);
        assertThat(pet.getHappiness()).isEqualTo(Math.min(4, happiness + 1));
        assertThatThrownBy(() -> service.finish(USER, PET, 99L, 1_000, T0))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_GAME_FINISHED);

        ZzalGame lost = ZzalGame.start(USER, PET, GameKind.RUN, "", T0);
        when(gameRepository.findByIdForUpdate(any())).thenReturn(Optional.of(lost));
        assertThat(service.finish(USER, PET, 100L, 29_999, T0).win()).isFalse();
    }

    @Test
    @DisplayName("좌우 5판 3승이면 leftRightWins +1·행복 +1. 답은 응답에 없고 되돌려 만든다. 5승째 응답에서 runUnlocked 가 true 로")
    void leftRightWinCounts() {
        ZzalGame game = ZzalGame.start(USER, PET, GameKind.LEFT_RIGHT, "LLLRR", T0);
        when(gameRepository.findByIdForUpdate(any())).thenReturn(Optional.of(game));
        pet.settle(T0);
        int happiness = pet.getHappiness();
        GameService.GuessResult last = null;
        for (char c : "LLLLL".toCharArray()) {
            last = service.guess(USER, PET, 1L, c, T0);
        }
        assertThat(game.isFinished()).isTrue();
        assertThat(game.isWin()).isTrue();
        assertThat(pet.getLeftRightWins()).isEqualTo(1);
        assertThat(pet.getHappiness()).isEqualTo(Math.min(4, happiness + 1));
        assertThat(last.runUnlocked()).isFalse();

        for (int i = 0; i < 3; i++) {
            pet.winLeftRight();
        }
        ZzalGame fifth = ZzalGame.start(USER, PET, GameKind.LEFT_RIGHT, "LLLRR", T0);
        when(gameRepository.findByIdForUpdate(any())).thenReturn(Optional.of(fifth));
        for (char c : "LLLLL".toCharArray()) {
            last = service.guess(USER, PET, 2L, c, T0);
        }
        assertThat(pet.getLeftRightWins()).isEqualTo(5);
        assertThat(last.runUnlocked()).isTrue();
    }

    // ── 두 번째 선물(뒤로 넘어짐) — 좌우 맞히기 첫 패배 ──────────────────

    @Test
    @DisplayName("★★ 한 판을 다 치고 못 이기면 그 자리에서 두 번째 선물을 굽는다")
    void losingAFullSeriesOpensTheSecondGift() {
        playLeftRight("LLLRR", "RRRRR");        // 5라운드 · 2승 — 못 이겼다

        verify(bakeTrigger).onFirstGameLoss(eq(pet), any());
    }

    @Test
    @DisplayName("★★ 한 라운드를 져도 판을 이기면 안 준다 — 한 판이 곧 3선승 시리즈다")
    void losingRoundsButWinningTheSeriesGivesNothing() {
        playLeftRight("LLLRR", "LLLLL");        // 4·5라운드는 틀렸지만 3승

        verify(bakeTrigger, never()).onFirstGameLoss(any(), any());
    }

    @Test
    @DisplayName("★★ 판이 안 끝났으면 안 준다 — 네 라운드까지 다 틀려도 마지막을 쳐야 패배다")
    void anUnfinishedSeriesGivesNothing() {
        ZzalGame game = ZzalGame.start(USER, PET, GameKind.LEFT_RIGHT, "LLLLL", T0);
        when(gameRepository.findByIdForUpdate(any())).thenReturn(Optional.of(game));
        pet.settle(T0);
        for (char c : "RRRR".toCharArray()) {
            service.guess(USER, PET, 1L, c, T0);
        }

        assertThat(game.isFinished()).isFalse();
        verify(bakeTrigger, never()).onFirstGameLoss(any(), any());
    }

    @Test
    @DisplayName("★★ 달리기 패배는 안 센다 — 넘어짐은 좌우 맞히기의 패배 리액션이다")
    void losingTheRunGivesNothing() {
        ZzalGame run = ZzalGame.start(USER, PET, GameKind.RUN, "", T0);
        when(gameRepository.findByIdForUpdate(any())).thenReturn(Optional.of(run));
        when(petService.alive(any(), any(), any())).thenAnswer(inv -> pet);
        for (int i = 0; i < 5; i++) {
            pet.winLeftRight();
        }

        assertThat(service.finish(USER, PET, 1L, 1_000, T0).win()).isFalse();

        verify(bakeTrigger, never()).onFirstGameLoss(any(), any());
    }

    @Test
    @DisplayName("★★★ 포기한 판은 패배가 아니다 — 밤을 넘겨 접은 판이 선물을 당겨 오면 안 된다")
    void anAbandonedGameIsNotALoss() {
        ZzalGame yesterday = ZzalGame.start(USER, PET, GameKind.LEFT_RIGHT, "LRLRL", kst("2026-09-05 18:00"));
        when(gameRepository.findFirstByPetIdAndFinishedAtIsNullOrderByIdDesc(anyLong())).thenReturn(Optional.of(yesterday));
        Instant nextMorning = kst("2026-09-06 11:00");
        service.start(USER, PET, GameKind.LEFT_RIGHT, nextMorning);

        assertThat(yesterday.isFinished()).as("접힌 판은 끝난 것으로 기록된다").isTrue();
        assertThat(yesterday.isWin()).as("그리고 이긴 판도 아니다").isFalse();
        verify(bakeTrigger, never()).onFirstGameLoss(any(), any());
    }

    @Test
    @DisplayName("★ 튜토리얼 중에 진 판은 안 센다 — 배우는 자리에서 지는 것은 과정이다")
    void losingDuringTheTutorialGivesNothing() {
        ReflectionTestUtils.setField(pet, "clockStartedAt", null);   // 튜토리얼로 되돌린다
        assertThat(pet.isInTutorial()).isTrue();

        playLeftRight("LLLRR", "RRRRR");

        verify(bakeTrigger, never()).onFirstGameLoss(any(), any());
    }

    /** 좌우 한 판을 끝까지 친다. {@code answers} 가 정답, {@code picks} 가 고른 것. */
    private void playLeftRight(String answers, String picks) {
        ZzalGame game = ZzalGame.start(USER, PET, GameKind.LEFT_RIGHT, answers, T0);
        when(gameRepository.findByIdForUpdate(any())).thenReturn(Optional.of(game));
        pet.settle(T0);
        for (char c : picks.toCharArray()) {
            service.guess(USER, PET, 1L, c, T0);
        }
        assertThat(game.isFinished()).as("다섯 라운드를 다 쳐야 한 판이 끝난다").isTrue();
    }

    @Test
    @DisplayName("★ 밤잠 뒤 어제 판은 잇지 않는다 — 접고(패) 새 판, 달리기도 열린다 (리뷰 중-1)")
    void yesterdaysGameIsAbandoned() {
        ZzalGame yesterday = ZzalGame.start(USER, PET, GameKind.LEFT_RIGHT, "LRLRL", kst("2026-09-05 18:00"));
        when(gameRepository.findFirstByPetIdAndFinishedAtIsNullOrderByIdDesc(anyLong())).thenReturn(Optional.of(yesterday));
        for (int i = 0; i < 5; i++) {
            pet.winLeftRight();
        }
        Instant nextMorning = kst("2026-09-06 11:00");          // 23:00 자동 취침 → 10:00 자동 기상 뒤
        // ★ 밤을 넘기며 게이지가 바닥나 병이 날 수 있다(PR-8) — 이 테스트의 관심사가 아니므로 낫게 해 둔다
        pet.settle(nextMorning);
        pet.medicine(nextMorning);
        GameService.Started s = service.start(USER, PET, GameKind.RUN, nextMorning);
        assertThat(yesterday.isFinished()).isTrue();
        assertThat(yesterday.isWin()).isFalse();
        assertThat(s.game()).isNotSameAs(yesterday);
        assertThat(s.game().getKind()).isEqualTo(GameKind.RUN);
        assertThat(pet.getTodayGames()).isEqualTo(1);
    }

    @Test
    @DisplayName("진행 중인 판이 있으면 새로 만들지 않고 그것을 돌려준다 — 하루 횟수도 안 먹는다")
    void resumesPlaying() {
        ZzalGame playing = ZzalGame.start(USER, PET, GameKind.LEFT_RIGHT, "LRLRL", T0);
        when(gameRepository.findFirstByPetIdAndFinishedAtIsNullOrderByIdDesc(anyLong())).thenReturn(Optional.of(playing));
        assertThat(service.start(USER, PET, GameKind.LEFT_RIGHT, T0).game()).isSameAs(playing);
        assertThat(pet.getTodayGames()).isZero();
        assertThat(pet.getGameStarts()).isZero();
    }

    @Test
    @DisplayName("★★ 이어치기에서도 튜토리얼 6칸은 넘어간다 — 하루 횟수·2층 조건·조각은 그대로")
    void resumingAdvancesTutorialOnly() {
        ZzalPet baby = PetFixture.hatching(USER, "여울", null, "k", T0);
        baby.markAlive("s", "i", T0);
        PetFixture.atTutorialStep(baby, com.lore.zzal.pet.TutorialSchedule.Step.GAME);
        ReflectionTestUtils.setField(baby, "id", PET);
        swapPet(baby);

        ZzalGame playing = ZzalGame.start(USER, PET, GameKind.LEFT_RIGHT, "LRLRL", T0);
        when(gameRepository.findFirstByPetIdAndFinishedAtIsNullOrderByIdDesc(anyLong())).thenReturn(Optional.of(playing));

        // 판을 시작했다가 나갔다 온 사람이 버튼을 다시 누른 것
        assertThat(service.start(USER, PET, GameKind.LEFT_RIGHT, T0).game()).isSameAs(playing);

        assertThat(com.lore.zzal.pet.TutorialSchedule.currentOf(baby.getTutorialStep()))
                .isEqualTo(com.lore.zzal.pet.TutorialSchedule.Step.SHARE);   // ★ 칸은 넘어갔다
        assertThat(baby.getTodayGames()).isZero();                            // ★ 하루 3판은 안 깎였다
        assertThat(baby.getGameStarts()).isZero();                            // ★ 2층 13번도 안 올랐다

        // 이미 넘어간 칸을 또 누른다고 더 가지 않는다
        service.start(USER, PET, GameKind.LEFT_RIGHT, T0);
        assertThat(com.lore.zzal.pet.TutorialSchedule.currentOf(baby.getTutorialStep()))
                .isEqualTo(com.lore.zzal.pet.TutorialSchedule.Step.SHARE);
    }

    @Test
    @DisplayName("놀이 조각은 새 판을 시작할 때만 오른다 — 이어치기는 안 센다")
    void resumingDoesNotCountPiece() {
        java.util.Map<Long, com.lore.zzal.piece.ZzalPiece> store = new java.util.HashMap<>();
        service = new GameService(gameRepository, petService,
                new RewardService(mock(ZzalPetRepository.class), RewardKind.NONE, RewardKind.HAPPINESS),
                com.lore.zzal.PieceFixture.inMemory(store), bakeTrigger, 3);
        ReflectionTestUtils.setField(pet, "piecesEnabledAt", T0);   // 3층부터만 센다

        service.start(USER, PET, GameKind.LEFT_RIGHT, T0);          // 새 판 — 센다
        int counted = store.get(PET).countOf(com.lore.zzal.piece.PieceEvent.GAME);
        assertThat(counted).isEqualTo(1);

        ZzalGame playing = ZzalGame.start(USER, PET, GameKind.LEFT_RIGHT, "LRLRL", T0);
        when(gameRepository.findFirstByPetIdAndFinishedAtIsNullOrderByIdDesc(anyLong())).thenReturn(Optional.of(playing));
        service.start(USER, PET, GameKind.LEFT_RIGHT, T0);          // 이어치기 — 안 센다
        assertThat(store.get(PET).countOf(com.lore.zzal.piece.PieceEvent.GAME)).isEqualTo(counted);
    }

    @Test
    @DisplayName("★★ 아픈 펫은 이어치기도 못 한다 — start·guess 둘 다 ZZAL_SICK_REFUSES (P-4)")
    void sickRefusesEvenWhenResuming() {
        ZzalGame playing = ZzalGame.start(USER, PET, GameKind.LEFT_RIGHT, "LRLRL", T0);
        when(gameRepository.findFirstByPetIdAndFinishedAtIsNullOrderByIdDesc(anyLong())).thenReturn(Optional.of(playing));
        when(gameRepository.findByIdForUpdate(any())).thenReturn(Optional.of(playing));
        fallSick();
        int happiness = pet.getHappiness();

        // 건강할 때 시작한 판을 병든 뒤에 다시 누른다 — 예전에는 그대로 돌려줬다
        assertThatThrownBy(() -> service.start(USER, PET, GameKind.LEFT_RIGHT, T0))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_SICK_REFUSES);

        // 그 판을 계속 친다 — 예전에는 다섯 판을 다 치고 승리 보상까지 받았다
        assertThatThrownBy(() -> service.guess(USER, PET, 1L, 'L', T0))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_SICK_REFUSES);

        assertThat(playing.isFinished()).isFalse();
        assertThat(pet.getHappiness()).isEqualTo(happiness);   // ★ 승리 행복이 병을 스스로 풀지 못한다
        assertThat(pet.isSick()).isTrue();
    }

    @Test
    @DisplayName("★★ 아픈 펫은 달리기를 끝내지도 못 한다 — finish 도 ZZAL_SICK_REFUSES, 판은 그대로 남는다 (P-4)")
    void sickRefusesFinishingRun() {
        for (int i = 0; i < 5; i++) {
            pet.winLeftRight();
        }
        ZzalGame run = service.start(USER, PET, GameKind.RUN, T0).game();   // 건강할 때 시작한 달리기
        when(gameRepository.findByIdForUpdate(any())).thenReturn(Optional.of(run));
        fallSick();
        int happiness = pet.getHappiness();

        // 병든 뒤에 그 판을 끝낸다 — 예전에는 그대로 받아 주고 승리 보상까지 줬다
        assertThatThrownBy(() -> service.finish(USER, PET, 99L, 60_000, T0))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_SICK_REFUSES);

        assertThat(run.isFinished()).isFalse();                // ★ 판은 접지 않는다 — 나으면 이어서 끝낸다
        assertThat(pet.getHappiness()).isEqualTo(happiness);   // ★ 승리 행복이 병을 스스로 풀지 못한다
        assertThat(pet.isSick()).isTrue();
    }

    // ── 거절 경로 전수 (M-14) ─────────────────────────────────────────────

    @Test
    @DisplayName("★★ 달리기가 잠긴 채 kind=RUN 을 보내도, 진행 중 좌우 판이 있으면 <b>그 좌우 판</b>이 돌아온다")
    void resumingIgnoresTheRequestedKind() {
        ZzalGame playing = ZzalGame.start(USER, PET, GameKind.LEFT_RIGHT, "LRLRL", T0);
        when(gameRepository.findFirstByPetIdAndFinishedAtIsNullOrderByIdDesc(anyLong())).thenReturn(Optional.of(playing));
        assertThat(pet.getLeftRightWins()).isZero();      // 달리기는 아직 잠겨 있다

        GameService.Started started = service.start(USER, PET, GameKind.RUN, T0);

        // ★ 이어치기 판정이 해금 검사보다 <b>위</b>에 있어서, 요청한 종류와 다른 판이 200 으로 나간다.
        //   시작한 판을 못 끝내게 하지 않으려는 것이지만, 화면은 달리기를 기대하고 좌우 판을 받는다.
        assertThat(started.game()).isSameAs(playing);
        assertThat(started.game().getKind()).isEqualTo(GameKind.LEFT_RIGHT);
        assertThat(started.runUnlocked()).isFalse();
        assertThat(pet.getTodayGames()).isZero();
    }

    @Test
    @DisplayName("★ 자는 중 · 여행 중이면 펫 쪽 거절이 그대로 올라오고 판은 한 글자도 안 바뀐다")
    void sleepingAndTravelingRefusalsPassThrough() {
        ZzalGame playing = ZzalGame.start(USER, PET, GameKind.LEFT_RIGHT, "LRLRL", T0);
        when(gameRepository.findFirstByPetIdAndFinishedAtIsNullOrderByIdDesc(anyLong())).thenReturn(Optional.of(playing));
        when(gameRepository.findByIdForUpdate(any())).thenReturn(Optional.of(playing));

        for (ErrorCode refusal : List.of(ErrorCode.ZZAL_PET_SLEEPING, ErrorCode.ZZAL_TRAVELING)) {
            org.mockito.Mockito.doThrow(new BusinessException(refusal))
                    .when(petService).awake(any(), any(), any());

            assertThatThrownBy(() -> service.start(USER, PET, GameKind.LEFT_RIGHT, T0))
                    .hasFieldOrPropertyWithValue("errorCode", refusal);
            assertThatThrownBy(() -> service.guess(USER, PET, 1L, 'L', T0))
                    .hasFieldOrPropertyWithValue("errorCode", refusal);
            assertThatThrownBy(() -> service.finish(USER, PET, 1L, 30_000, T0))
                    .hasFieldOrPropertyWithValue("errorCode", refusal);

            assertThat(playing.isFinished()).isFalse();
            assertThat(playing.round()).isZero();
            assertThat(pet.getTodayGames()).isZero();
        }
    }

    @Test
    @DisplayName("★ 없는 판 번호 · 남의 판이면 ZZAL_GAME_NOT_FOUND — 화면이 보낸 gameId 를 믿지 않는다")
    void someoneElsesGameIsNotFound() {
        ZzalGame mine = ZzalGame.start(USER, PET, GameKind.LEFT_RIGHT, "LRLRL", T0);
        ZzalGame theirs = ZzalGame.start(999L, PET, GameKind.LEFT_RIGHT, "LRLRL", T0);

        when(gameRepository.findByIdForUpdate(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.guess(USER, PET, 404L, 'L', T0))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_GAME_NOT_FOUND);

        when(gameRepository.findByIdForUpdate(any())).thenReturn(Optional.of(theirs));
        assertThatThrownBy(() -> service.guess(USER, PET, 1L, 'L', T0))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_GAME_NOT_FOUND);

        assertThat(theirs.round()).as("남의 판은 한 글자도 안 바뀐다").isZero();
        assertThat(mine.isFinished()).isFalse();
    }

    @Test
    @DisplayName("★ 종류가 어긋난 호출은 INVALID_INPUT — 좌우 판에 finish, 달리기에 guess")
    void wrongKindIsRefused() {
        ZzalGame leftRight = ZzalGame.start(USER, PET, GameKind.LEFT_RIGHT, "LRLRL", T0);
        when(gameRepository.findByIdForUpdate(any())).thenReturn(Optional.of(leftRight));

        assertThatThrownBy(() -> service.finish(USER, PET, 1L, 30_000, T0))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);
        assertThat(leftRight.isFinished()).isFalse();

        for (int i = 0; i < 5; i++) {
            pet.winLeftRight();
        }
        ZzalGame run = ZzalGame.start(USER, PET, GameKind.RUN, "", T0);
        when(gameRepository.findByIdForUpdate(any())).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service.guess(USER, PET, 2L, 'L', T0))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);
        assertThat(run.isFinished()).isFalse();
    }

    @Test
    @DisplayName("★ 하루 3판을 다 쓴 뒤의 거절은 펫도 조각도 안 건드린다")
    void dailyLimitRefusalChangesNothing() {
        java.util.Map<Long, com.lore.zzal.piece.ZzalPiece> store = new java.util.HashMap<>();
        service = new GameService(gameRepository, petService,
                new RewardService(mock(ZzalPetRepository.class), RewardKind.NONE, RewardKind.HAPPINESS),
                com.lore.zzal.PieceFixture.inMemory(store), bakeTrigger, 3);
        ReflectionTestUtils.setField(pet, "piecesEnabledAt", T0);
        for (int i = 0; i < 3; i++) {
            service.start(USER, PET, GameKind.LEFT_RIGHT, T0);
        }
        int counted = store.get(PET).countOf(com.lore.zzal.piece.PieceEvent.GAME);
        int starts = pet.getGameStarts();

        assertThatThrownBy(() -> service.start(USER, PET, GameKind.LEFT_RIGHT, T0))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_GAME_DAILY_LIMIT);

        assertThat(pet.getTodayGames()).isEqualTo(3);
        assertThat(pet.getGameStarts()).isEqualTo(starts);
        assertThat(store.get(PET).countOf(com.lore.zzal.piece.PieceEvent.GAME)).isEqualTo(counted);
    }

    private void fallSick() {
        ReflectionTestUtils.setField(pet, "sickSince", T0);
        ReflectionTestUtils.setField(pet, "sickKind", com.lore.zzal.pet.SickKind.NEGLECT);
    }

    /**
     * 이 시험만 다른 펫을 보게 한다 — setUp 의 펫은 튜토리얼을 이미 지났다.
     *
     * ★ {@code when(mock.foo(..))} 로 다시 스텁하면 <b>그 순간 앞의 스텁이 한 번 돈다</b>(인자가 전부 null 인 채로).
     *   그래서 doAnswer 로 건다.
     */
    private void swapPet(ZzalPet other) {
        org.mockito.Mockito.doAnswer(inv -> {
            other.settle(other.now(inv.getArgument(2)));
            return other;
        }).when(petService).awake(any(), any(), any());
        org.mockito.Mockito.doAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return new PetService.Action(other, List.of());
        }).when(petService).withUnlockDiff(any(), any());
    }
}
