package com.lore.zzal.game;

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
import static org.mockito.Mockito.mock;
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

    @BeforeEach
    void setUp() {
        gameRepository = mock(ZzalGameRepository.class);
        when(gameRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(gameRepository.findFirstByPetIdAndFinishedAtIsNullOrderByIdDesc(anyLong())).thenReturn(Optional.empty());

        pet = ZzalPet.hatch(USER, "여울", null, "k", T0);
        pet.markAlive("s", "i", T0);
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
        service = new GameService(gameRepository, petService, rewards, 3);
    }

    @Test
    @DisplayName("★ 두 게임 합쳐 하루 3판 — 4번째는 ZZAL_GAME_DAILY_LIMIT, 시작한 판 기준. 3번째 시작에 놀라기(13) 폭죽")
    void threePerDayCombined() {
        assertThat(service.start(USER, PET, GameKind.LEFT_RIGHT, T0).justUnlocked()).isEmpty();
        service.start(USER, PET, GameKind.LEFT_RIGHT, T0);
        assertThat(service.start(USER, PET, GameKind.LEFT_RIGHT, T0).justUnlocked()).containsExactly(13);
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
        assertThat(pet.getGameStarts()).isEqualTo(3);           // 누적(2층 13번 조건)은 남는다
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
    }
}
