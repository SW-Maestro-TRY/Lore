package com.lore.zzal.game;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.pet.PetService;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalRules;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * 미니게임 v2(정본 7·16장) — 좌·우 맞히기 + 달리기, 합쳐 하루 3판, 잠들 때 리셋.
 *
 * <h3>★ 정답은 서버만 안다(좌우)</h3>
 * 시작할 때 다섯 판의 답을 뽑아 저장하고 한 번에 한 판씩만 공개한다. 공개도 "맞았는지에서 되돌려 만든다" —
 * 이 클래스는 {@code answers} 를 한 번도 읽지 않아 남은 판의 답이 응답에 실릴 길이 없다.
 *
 * <h3>달리기는 화면 물리</h3>
 * 서버는 살아남은 시간의 상한(60초)만 검증한다. 치팅해도 얻는 것은 행복 +1 뿐이다(결정기록 B7).
 *
 * <h3>하루 3판은 펫의 카운터</h3>
 * v1 은 달력일·사용자 기준으로 표를 세었다. v2 는 하루의 경계가 잠드는 순간이라 {@code ZzalPet.todayGames}
 * 가 정본이고, 잠들 때 0 이 된다. 시작한 판 기준(지는 판을 버리고 다시 시작하는 것을 막는다).
 */
@Service
public class GameService {

    private final RandomGenerator random = new SecureRandom();

    private final ZzalGameRepository gameRepository;
    private final PetService petService;
    private final RewardService rewardService;
    private final int dailyLimit;

    public GameService(ZzalGameRepository gameRepository,
                       PetService petService,
                       RewardService rewardService,
                       @Value("${app.zzal.game.daily-limit:3}") int dailyLimit) {
        this.gameRepository = gameRepository;
        this.petService = petService;
        this.rewardService = rewardService;
        this.dailyLimit = dailyLimit;
    }

    /** 시작 결과 — 판 + 이번 행동으로 열린 2층(13번 놀라기) + 달리기 해금 여부. 행동 응답 = 상태(리뷰 반영). */
    public record Started(ZzalGame game, List<Integer> justUnlocked, boolean runUnlocked) {
    }

    public record GuessResult(ZzalGame game, int round, char pick, char answer, boolean hit,
                              List<Integer> justUnlocked, boolean runUnlocked) {
    }

    public record RunResult(ZzalGame game, boolean win, List<Integer> justUnlocked, boolean runUnlocked) {
    }

    /**
     * 새 판. 진행 중인 판이 있으면 그것을 돌려준다(두 번 눌러도 안전, 하루 횟수도 안 먹는다).
     * 펫은 {@link PetService#awake} 로 잠근다 — 검사와 저장 사이에 다른 요청이 끼면 판이 둘 생긴다.
     */
    @Transactional
    public Started start(Long userId, Long petId, GameKind kind, Instant realNow) {
        ZzalPet pet = petService.awake(userId, petId, realNow);
        Instant now = pet.now(realNow);

        Optional<ZzalGame> playing = gameRepository.findFirstByPetIdAndFinishedAtIsNullOrderByIdDesc(pet.getId());
        if (playing.isPresent()) {
            ZzalGame old = playing.get();
            // ★ 어제 판은 잇지 않는다 — 밤잠을 넘긴 미완료 판을 그대로 돌려주면 익일 첫 시작이 어제 좌우 판이 되고
            //   달리기도 못 연다(리뷰 실측). 오늘 기상 전에 시작한 판은 접고(패) 새로 시작한다.
            Instant woke = pet.getWokeAt() == null ? pet.getHatchedAt() : pet.getWokeAt();
            if (!old.getStartedAt().isBefore(woke)) {
                return new Started(old, List.of(), runUnlocked(pet));
            }
            old.abandon(now);
        }
        if (pet.isSick()) {
            throw new BusinessException(ErrorCode.ZZAL_SICK_REFUSES);
        }
        if (kind == GameKind.RUN && pet.getLeftRightWins() < ZzalRules.RUN_UNLOCK_LEFT_RIGHT_WINS) {
            throw new BusinessException(ErrorCode.ZZAL_FEATURE_LOCKED,
                    "좌우 맞히기에서 %d번 이기면 달리기가 열려요".formatted(ZzalRules.RUN_UNLOCK_LEFT_RIGHT_WINS));
        }
        if (pet.getTodayGames() >= dailyLimit) {
            throw new BusinessException(ErrorCode.ZZAL_GAME_DAILY_LIMIT);
        }
        PetService.Action a = petService.withUnlockDiff(pet, pet::startGame);   // 13번 놀라기(3판)가 여기서 열린다
        String answers = kind == GameKind.LEFT_RIGHT ? drawAnswers() : "";
        ZzalGame game = gameRepository.save(ZzalGame.start(userId, pet.getId(), kind, answers, now));
        return new Started(game, a.justUnlocked(), runUnlocked(pet));
    }

    private static boolean runUnlocked(ZzalPet pet) {
        return pet.getLeftRightWins() >= ZzalRules.RUN_UNLOCK_LEFT_RIGHT_WINS;
    }

    /** 좌우 한 판. 화면이 보낸 gameId 를 믿지 않고 펫과 사람이 모두 맞는지 확인한다. */
    @Transactional
    public GuessResult guess(Long userId, Long petId, Long gameId, char pick, Instant realNow) {
        ZzalPet pet = petService.awake(userId, petId, realNow);
        Instant now = pet.now(realNow);
        ZzalGame game = myGame(userId, pet, gameId);
        if (game.getKind() != GameKind.LEFT_RIGHT) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "달리기는 finish 로 끝내요");
        }
        if (game.isFinished()) {
            throw new BusinessException(ErrorCode.ZZAL_GAME_FINISHED);
        }
        int round = game.round();
        boolean hit = game.guess(pick, now);
        PetService.Action a = petService.withUnlockDiff(pet, () -> {
            if (game.isFinished() && game.isWin()) {
                pet.winLeftRight();
                rewardService.forGameWin(pet, now);
            }
        });
        // 달리기 해금(5승)은 동작이 아니라 기능이라 justUnlocked 에 안 실린다 → runUnlocked 로 "이번에 열렸다" 를 알린다
        return new GuessResult(game, round, pick, revealed(pick, hit), hit, a.justUnlocked(), runUnlocked(pet));
    }

    /** 달리기 끝. 30초 이상이면 승리. */
    @Transactional
    public RunResult finish(Long userId, Long petId, Long gameId, long survivedMs, Instant realNow) {
        ZzalPet pet = petService.awake(userId, petId, realNow);
        Instant now = pet.now(realNow);
        ZzalGame game = myGame(userId, pet, gameId);
        if (game.getKind() != GameKind.RUN) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "좌우 맞히기는 guess 로 쳐요");
        }
        if (game.isFinished()) {
            throw new BusinessException(ErrorCode.ZZAL_GAME_FINISHED);
        }
        game.finishRun(survivedMs, now);
        PetService.Action a = petService.withUnlockDiff(pet, () -> {
            if (game.isWin()) {
                rewardService.forGameWin(pet, now);
            }
        });
        return new RunResult(game, game.isWin(), a.justUnlocked(), runUnlocked(pet));
    }

    /** 치던 판. 새로고침 복구용. 자는 중이어도 조회는 된다. */
    @Transactional
    public Optional<ZzalGame> current(Long userId, Long petId, Instant realNow) {
        ZzalPet pet = petService.alive(userId, petId, realNow);
        return gameRepository.findFirstByPetIdAndFinishedAtIsNullOrderByIdDesc(pet.getId());
    }

    /** 오늘 더 할 수 있는 판. 정산된 펫에서 읽는다. */
    public int remainingToday(ZzalPet pet) {
        return Math.max(0, dailyLimit - pet.getTodayGames());
    }

    private ZzalGame myGame(Long userId, ZzalPet pet, Long gameId) {
        return gameRepository.findByIdForUpdate(gameId)
                .filter(g -> g.getUserId().equals(userId) && g.getPetId().equals(pet.getId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.ZZAL_GAME_NOT_FOUND));
    }

    private String drawAnswers() {
        StringBuilder sb = new StringBuilder(ZzalGame.ROUNDS);
        for (int i = 0; i < ZzalGame.ROUNDS; i++) {
            sb.append(random.nextBoolean() ? 'L' : 'R');
        }
        return sb.toString();
    }

    /** 방금 친 판의 답을 되돌려 만든다 — 저장소의 답을 꺼내는 코드가 한 줄도 없다. */
    private static char revealed(char pick, boolean hit) {
        return hit ? pick : (pick == 'L' ? 'R' : 'L');
    }
}
