package com.lore.zzal.game;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.night.BakeTrigger;
import com.lore.zzal.pet.PetService;
import com.lore.zzal.piece.PieceEvent;
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
    private final com.lore.zzal.piece.PieceService pieceService;
    private final BakeTrigger bakeTrigger;
    private final int dailyLimit;

    public GameService(ZzalGameRepository gameRepository,
                       PetService petService,
                       RewardService rewardService,
                       com.lore.zzal.piece.PieceService pieceService,
                       BakeTrigger bakeTrigger,
                       @Value("${app.zzal.game.daily-limit:3}") int dailyLimit) {
        this.gameRepository = gameRepository;
        this.petService = petService;
        this.rewardService = rewardService;
        this.pieceService = pieceService;
        this.bakeTrigger = bakeTrigger;
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

    /** 기권 결과 — 접은 판. 기권으로 열리는 것이 없어 {@code justUnlocked} 는 늘 비어 있다. */
    public record Abandoned(ZzalGame game, List<Integer> justUnlocked, boolean runUnlocked) {
    }

    /**
     * 새 판. <b>이어치기는 없다</b> — 남아 있던 미완료 판은 그 자리에서 접고(패) 새 판을 연다.
     * 펫은 {@link PetService#awake} 로 잠근다 — 검사와 저장 사이에 다른 요청이 끼면 판이 둘 생긴다.
     *
     * <h3>★★ 왜 이어치기를 지웠나 (2026-09-22 상훈님 결정 · 정본 7-A)</h3>
     * <b>게임은 중간에 나가면 끝이다.</b> 옛 코드는 "오늘 기상 뒤에 시작한 미완료 판이면 그것을 돌려준다" 로
     * 나갔다 온 사람에게 같은 판을 이어 줬는데, <b>정본 어디에도 그런 규정이 없었다</b>(1.11 확인) —
     * 나가기 버튼으로 접은 판만 재개를 금지한다고 읽었던 것이 빈자리를 그렇게 메운 것이다.
     * 이어치기가 있으면 지고 있는 판을 새로고침으로 버리고 다시 칠 수 있어, 하루 3판이
     * <b>이길 때까지 3판</b>이 된다 — {@link #abandon} 이 막으려던 바로 그 일이다.
     *
     * <h3>★ 접는 자리가 <b>검사 뒤</b>인 이유</h3>
     * 아픔·달리기 잠김·하루 3판 초과로 거절될 요청은 <b>남의 판을 건드리지 않는다.</b> 접기를 검사 위로
     * 올리면, 좌우 판을 치던 중에 잠긴 달리기 버튼을 한 번 누른 것만으로 치던 판이 죽는다(거절인데
     * 손해가 남는다). 그래서 "새 판이 확실히 열린다" 가 확정된 뒤에만 옛 판을 접는다.
     *
     * <h3>★ 하루 3판과 튜토리얼 칸</h3>
     * 이제 모든 {@code start} 가 새 판이라 {@code startGame()} 하나가 세 가지를 한다 —
     * {@code todayGames}(하루 3판) · {@code gameStarts}(2층 15번 놀람) · 튜토리얼 GAME 칸.
     * 나갔다 온 사람도 <b>새 판을 시작하므로</b> 한도가 정상 차감된다(옛 이어치기는 공짜였다).
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

        // ★★ 아픔이 맨 위다 — 이 검사가 옛 이어치기 return 보다 아래에 있었을 때,
        //   건강할 때 시작한 판을 병든 뒤에도 계속 눌러 5판을 다 치고 <b>승리 보상(행복 +1)</b>까지 받았다.
        //   그 행복이 병든 상태를 스스로 풀어 "아프면 놀지 않는다"(정본 16장)가 통째로 무력화됐다.
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
        // ★ 남아 있던 미완료 판을 접는다(패) — 나가기 버튼을 안 눌렀어도 마찬가지다.
        //   보상·달리기 해금·두 번째 선물·놀이 조각은 어느 것도 타지 않는다({@code abandon} 과 같은 이유로,
        //   여기서 <b>부르지 않는 것</b>이 규칙이다). 깎인 기회도 돌려주지 않는다 — 시작할 때 이미 깎였다.
        gameRepository.findFirstByPetIdAndFinishedAtIsNullOrderByIdDesc(pet.getId())
                .ifPresent(old -> old.abandon(now));
        // 15번 놀람(게임 시작 4판)·하루 3판·튜토리얼 GAME 칸이 전부 여기서 오른다
        PetService.Action a = petService.withUnlockDiff(pet, pet::startGame);
        // ★★ 놀이 조각은 <b>여기서 세지 않는다</b> — 끝까지 친 매치만 센다({@link #guess}·{@link #finish}).
        //   시작에서 세면 기권·강제 종료한 판의 조각이 그대로 남아 "기권 매치는 조각을 안 낸다"
        //   (정본 7-A)가 무너진다. 시작만 하고 나가기를 되풀이해 조각을 채울 수 있었다(연결 감사 F7).
        // ★ 2층 15번(놀람)은 그대로 <b>시작</b> 기준이다 — gameStarts 는 startGame() 이 올린다.
        //   정본 7-A가 한정한 것은 조각뿐이고, 해금 조건은 건드리지 않았다.
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
        // ★★ 놀이 조각은 <b>완주한 매치</b>만 센다 — 다섯 라운드를 다 친 그 순간, 승패는 안 본다
        //   (정본 6·16장 "승패 무관" + 7-A "기권 매치는 조각을 안 낸다"를 함께 만족하는 자리).
        // ★ 이 자리를 고른 이유가 곧 안전장치다 — 시작에서 세면 기권한 판의 조각이 남고,
        //   승리에서만 세면 "승패 무관" 이 깨진다. 접은 판(abandon)은 여기 오지 않는다.
        if (game.isFinished()) {
            pieceService.count(pet, PieceEvent.GAME);
        }
        // ★★ 두 번째 선물(뒤로 넘어짐)은 <b>여기</b>서 열린다 — 한 판을 다 치고 못 이긴 순간.
        //
        // ★ 한 판이 곧 3선승 시리즈(5라운드)라 한 라운드 패배는 패배가 아니다.
        // ★ 이 자리를 고른 이유가 곧 안전장치다 — guess() 안에만 두면 달리기(finish)도,
        //   밤을 넘겨 접은 판(abandon)도 여기 안 온다. 접은 판이 패배로 세면 한 판도 끝까지 안 친
        //   사람이 선물을 받고, 진 적이 없어 그 선물의 이유를 모른다.
        // ★ 튜토리얼을 끝낸 뒤부터다 — 튜토리얼 안에서는 지는 것도 배우는 과정이다.
        if (game.isFinished() && !game.isWin() && !pet.isInTutorial()) {
            bakeTrigger.onFirstGameLoss(pet, now);
        }
        // 달리기 해금(5승)은 동작이 아니라 기능이라 justUnlocked 에 안 실린다 → runUnlocked 로 "이번에 열렸다" 를 알린다
        return new GuessResult(game, round, pick, revealed(pick, hit), hit, a.justUnlocked(), runUnlocked(pet));
    }

    /** 달리기 끝. 30초 이상이면 승리. */
    @Transactional(noRollbackFor = BusinessException.class)
    public RunResult finish(Long userId, Long petId, Long gameId, long survivedMs, Instant realNow) {
        ZzalPet pet = petService.awake(userId, petId, realNow);
        Instant now = pet.now(realNow);
        ZzalGame game = myGame(userId, pet, gameId);
        // ★ 달리기는 시작 한 번·끝내기 한 번이라, start 만 막으면 <b>끝내기가 통째로 빠져나간다</b> —
        //   건강할 때 시작한 달리기를 병든 뒤에 끝내 승리 보상(행복 +1)으로 병을 스스로 푸는 길이 남는다.
        //   guess 와 같은 이유·같은 오류로 거절한다(정본 16장 "아프면 놀지 않는다").
        // ★ 잠근 뒤·상태를 바꾸기 전에 본다. 이미 시작된 판은 <b>그대로 둔다</b> — 여기서 접어 버리면
        //   깎인 하루 한 판이 사라지고, 나으면 이어서 끝내는 길도 함께 사라진다.
        if (pet.isSick()) {
            throw new BusinessException(ErrorCode.ZZAL_SICK_REFUSES);
        }
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
        // ★ 달리기도 이 주소를 밟으면 완주다 — 30초를 못 버텨 져도 조각 하나를 센다(승패 무관).
        //   나가서 접힌 달리기는 여기 오지 않아 자연히 빠진다.
        pieceService.count(pet, PieceEvent.GAME);
        return new RunResult(game, game.isWin(), a.justUnlocked(), runUnlocked(pet));
    }

    /**
     * 기권 — 치던 판을 그 자리에서 접는다. 진행 중이던 판은 <b>패배로 확정</b>되고 다시 못 친다.
     *
     * <h3>★ 왜 "나가면 끝" 인가</h3>
     * 지고 있는 판을 나갔다가 다시 들어와 이어 치면 하루 3판이 <b>이길 때까지 3판</b>이 된다.
     * 하루 횟수를 끝난 판이 아니라 <b>시작한 판</b>으로 세는 것(정본 7장)과 같은 이유다.
     * 그래서 기회는 깎지도 돌려주지도 않는다 — 시작할 때 이미 깎였다.
     *
     * <h3>★★ 여기서 <b>부르지 않는 것</b>들이 이 메서드의 본체다</h3>
     * <ul>
     *   <li>{@code pet.winLeftRight()} · {@code rewardService.forGameWin} — 기권은 승리가 아니다.
     *       접는 순간 이미 3승을 쌓았더라도 마찬가지다(그 판은 끝까지 안 친 판이다).</li>
     *   <li>{@code bakeTrigger.onFirstGameLoss} — 접은 판이 패배로 세면 <b>한 판도 끝까지 안 친 사람이
     *       선물을 받고, 진 적이 없어 그 선물의 이유를 모른다.</b> {@code guess} 에 적어 둔 금지와 같다.</li>
     *   <li>{@code pieceService.count} — <b>놀이 조각은 완주한 매치만 센다</b>(정본 7-A). 시작할 때
     *       세지 않았으므로 여기서 되돌릴 것도 없다. 접은 판은 조각을 한 칸도 못 낸다.</li>
     *   <li>{@code pet.startGame} — 하루 3판·2층 15번(놀람)은 <b>시작할 때 이미 셌다.</b>
     *       접었다고 또 세지도, 돌려주지도 않는다.</li>
     * </ul>
     *
     * <h3>★ 아픔은 여기서만 안 본다 — 빠뜨린 게 아니라 일부러다</h3>
     * {@code start}·{@code guess}·{@code finish} 의 {@code isSick} 검사는 "아프면 놀지 않는다"(정본 16장)를
     * 지키는 것이라 <b>노는 쪽</b>에 건다. 기권은 노는 게 아니라 그만두는 것이다. 여기서까지 거절하면
     * 병든 사람은 열어 둔 판을 닫지도 못한 채 나을 때까지 갇히고, 그 판이 {@code current} 로 계속 돌아와
     * 화면도 못 닫는다 — 병이 벌이 되어 버린다.
     *
     * ★ 거절이 나도 정산은 되돌리지 않는다 — {@code start} 주석과 같은 규약이다(#225 리뷰 하-1).
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public Abandoned abandon(Long userId, Long petId, Long gameId, Instant realNow) {
        ZzalPet pet = petService.awake(userId, petId, realNow);
        Instant now = pet.now(realNow);
        ZzalGame game = myGame(userId, pet, gameId);
        if (game.isFinished()) {
            throw new BusinessException(ErrorCode.ZZAL_GAME_FINISHED);
        }
        // ★ 좌우·달리기를 가리지 않는다 — 달리기도 나가면 그 판은 끝이다(kind 검사 없음이 의도다).
        game.abandon(now);      // finishedAt 만 찍는다
        return new Abandoned(game, List.of(), runUnlocked(pet));
    }

    /**
     * 진행 중인 판 — <b>이제 늘 비어 있다</b>(2026-09-22 상훈님 결정 "게임은 중간에 나가면 끝").
     *
     * <h3>★★ 복구용이 아니다 — 이 주소로는 판을 되찾을 수 없다</h3>
     * 옛 구현은 미완료 판을 돌려줘 새로고침 복구에 썼다. 그것이 곧 이어치기였다 —
     * 지고 있는 판을 새로고침으로 버리고 이길 때까지 다시 칠 수 있었다. 판을 되돌려 주는 자리를
     * {@code start} 에서만 지우고 여기를 남겨 두면, 화면이 이 주소로 같은 판을 다시 집어
     * <b>{@code guess} 로 계속 칠 수 있다</b> — 뒷문이 열린 채로 앞문만 닫는 셈이다.
     *
     * <h3>★ 그런데 왜 주소를 지우지 않나</h3>
     * 화면이 이미 부르고 있다. 없애면 404 가 나가 "게임 탭이 고장" 으로 보인다. 그래서 주소는
     * 남기고 <b>늘 "진행 중인 판 없음"</b>으로 답한다 — 화면은 이 답을 받아 새 판 버튼을 보이면 된다.
     *
     * ★ 정산({@link PetService#alive})은 그대로 돈다 — 응답에 함께 실리는 <b>오늘 남은 판수</b>가
     *   최신이어야 하고(#리뷰), 자는 중이어도 조회 자체는 된다.
     * ★ 아직 안 끝난 옛 판 줄은 DB 에 남을 수 있다(나간 것을 서버가 알 길이 없다). 그 줄은 아무것도
     *   막지 않고, 다음 {@code start} 가 접는다. 조회가 남의 줄을 고치지는 않는다(GET 은 읽기만).
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public Optional<ZzalGame> current(Long userId, Long petId, Instant realNow) {
        petService.alive(userId, petId, realNow);
        return Optional.empty();
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
