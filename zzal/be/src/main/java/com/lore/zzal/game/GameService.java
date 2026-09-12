package com.lore.zzal.game;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.pet.PetService;
import com.lore.zzal.piece.PieceEvent;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.TutorialSchedule;
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
    private final com.lore.zzal.piece.PieceService pieceService;
    private final int dailyLimit;

    public GameService(ZzalGameRepository gameRepository,
                       PetService petService,
                       RewardService rewardService,
                       com.lore.zzal.piece.PieceService pieceService,
                       @Value("${app.zzal.game.daily-limit:3}") int dailyLimit) {
        this.gameRepository = gameRepository;
        this.petService = petService;
        this.rewardService = rewardService;
        this.pieceService = pieceService;
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
     *
     * ★★ 거절이 나도 <b>정산은 되돌리지 않는다</b> — {@code PetService} 12개 메서드와 같은 규약이다(#225 리뷰 하-1).
     *   이 클래스의 네 메서드는 전부 {@code petService.awake}/{@code alive} 를 부르고, 그 안의 {@code touch()} 가
     *   흐른 시간을 반영하며 <b>장면을 저장하고 엽서를 채우고 도착을 찍는다.</b> 그러고 나서 "할 수 있나" 를 묻는다.
     *   기본값대로 롤백하면 하루 3판 초과·아픔으로 거절될 때마다 그 요청이 만든 장면 행·엽서 행·{@code revealedAt}
     *   이 통째로 사라진다 — 사용자 눈에는 "거절당했더니 시간이 되감겼다" 로 보인다.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public Started start(Long userId, Long petId, GameKind kind, Instant realNow) {
        ZzalPet pet = petService.awake(userId, petId, realNow);
        Instant now = pet.now(realNow);

        // ★★ 아픔은 <b>이어치기에도</b> 걸린다 — 이 검사가 아래 이어치기 return 보다 아래에 있었을 때,
        //   건강할 때 시작한 판을 병든 뒤에도 계속 눌러 5판을 다 치고 <b>승리 보상(행복 +1)</b>까지 받았다.
        //   그 행복이 병든 상태를 스스로 풀어 "아프면 놀지 않는다"(정본 16장)가 통째로 무력화됐다.
        // ★ 하루 3판·달리기 해금은 아래에 그대로 둔다 — 그 둘은 <b>새 판을 시작하는 것</b>에 걸리는 조건이고,
        //   이어치기는 이미 깎인 판을 잇는 것이라 다시 걸면 시작한 판을 못 끝낸다.
        if (pet.isSick()) {
            throw new BusinessException(ErrorCode.ZZAL_SICK_REFUSES);
        }

        Optional<ZzalGame> playing = gameRepository.findFirstByPetIdAndFinishedAtIsNullOrderByIdDesc(pet.getId());
        if (playing.isPresent()) {
            ZzalGame old = playing.get();
            // ★ 어제 판은 잇지 않는다 — 밤잠을 넘긴 미완료 판을 그대로 돌려주면 익일 첫 시작이 어제 좌우 판이 되고
            //   달리기도 못 연다(리뷰 실측). 오늘 기상 전에 시작한 판은 접고(패) 새로 시작한다.
            Instant woke = pet.getWokeAt() == null ? pet.getHatchedAt() : pet.getWokeAt();
            if (!old.getStartedAt().isBefore(woke)) {
                // ★★ 이어치기에서도 튜토리얼 6칸(GAME)은 넘어간다.
                //   이 return 이 아래 startGame() 보다 위에 있어서, 판을 시작했다가 나갔다 온 사람은
                //   버튼을 눌러도 칸이 안 넘어갔다(프론트 실측: 여덟 번을 불러도 GAME 에 머물렀다).
                //   "게임 1판" 은 <b>새 판을 시작해야</b>가 아니라 <b>놀았으면 된다</b>는 뜻이다.
                // ★ 다만 칸 넘기기만 떼어낸다 — todayGames(하루 3판)·gameStarts(2층 13번)·놀이 조각은
                //   startGame() 안에 그대로 두어 <b>새 판을 시작할 때만</b> 오른다. 여기서 또 세면
                //   하루 한도가 잘못 깎이고 조각이 공짜로 찬다.
                pet.advanceTutorial(TutorialSchedule.Step.GAME);
                return new Started(old, List.of(), runUnlocked(pet));
            }
            old.abandon(now);
        }
        if (kind == GameKind.RUN && pet.getLeftRightWins() < ZzalRules.RUN_UNLOCK_LEFT_RIGHT_WINS) {
            throw new BusinessException(ErrorCode.ZZAL_FEATURE_LOCKED,
                    "좌우 맞히기에서 %d번 이기면 달리기가 열려요".formatted(ZzalRules.RUN_UNLOCK_LEFT_RIGHT_WINS));
        }
        if (pet.getTodayGames() >= dailyLimit) {
            throw new BusinessException(ErrorCode.ZZAL_GAME_DAILY_LIMIT);
        }
        PetService.Action a = petService.withUnlockDiff(pet, pet::startGame);   // 13번 놀라기(3판)가 여기서 열린다
        // ★ 놀이 조각은 <b>시작한 매치</b>로 센다(승패 무관) — 2층 13번과 같은 기준(정본 6·16장).
        pieceService.count(pet, PieceEvent.GAME);
        String answers = kind == GameKind.LEFT_RIGHT ? drawAnswers() : "";
        ZzalGame game = gameRepository.save(ZzalGame.start(userId, pet.getId(), kind, answers, now));
        return new Started(game, a.justUnlocked(), runUnlocked(pet));
    }

    private static boolean runUnlocked(ZzalPet pet) {
        return pet.getLeftRightWins() >= ZzalRules.RUN_UNLOCK_LEFT_RIGHT_WINS;
    }

    /** 좌우 한 판. 화면이 보낸 gameId 를 믿지 않고 펫과 사람이 모두 맞는지 확인한다. */
    @Transactional(noRollbackFor = BusinessException.class)
    public GuessResult guess(Long userId, Long petId, Long gameId, char pick, Instant realNow) {
        ZzalPet pet = petService.awake(userId, petId, realNow);
        Instant now = pet.now(realNow);
        // ★ 시작만 막으면 소용이 없다 — 판은 한 번 시작하고 다섯 번 친다. 치는 자리에서도 봐야
        //   건강할 때 시작한 판이 병든 뒤에 끝까지 굴러가 승리 보상으로 병을 푸는 길이 막힌다.
        if (pet.isSick()) {
            throw new BusinessException(ErrorCode.ZZAL_SICK_REFUSES);
        }
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
    @Transactional(noRollbackFor = BusinessException.class)
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
    @Transactional(noRollbackFor = BusinessException.class)
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
