package com.lore.zzal.game;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalPetRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * 좌·우 맞히기.
 *
 * <h3>★ 정답은 서버만 안다</h3>
 * 시작할 때 다섯 판의 답을 뽑아 {@link ZzalGame} 에 적어 두고, 한 번에 한 판씩만 공개한다.
 * 화면이 다섯 번을 혼자 치고 "이겼다" 만 보내는 쪽이 왕복이 적지만, 그러면
 * <b>개발자도구로 이겼다고 말하면 그만이다.</b> 보상이 {@code NONE} 인 지금은 무해해 보여도
 * 나중에 보상을 켜는 순간 그게 곧 무한 이득이 된다.
 * <p>
 * 공개도 "그 판의 답을 꺼내 준다" 가 아니라 <b>맞았는지에서 되돌려 만든다</b>
 * ({@link #revealed}). 그래서 남은 판의 답이 응답에 실릴 <b>길 자체가 없다</b> —
 * 이 클래스는 {@code answers} 문자열을 한 번도 읽지 않는다.
 */
@Service
public class GameService {

    /**
     * 하루가 바뀌는 기준.
     *
     * ★ UTC 로 세면 한국 시간 오전 9시에 하루가 바뀐다. 자정에 초기화될 거라 믿고 기다린
     *   사람에게는 그냥 고장으로 보인다. 사용자가 사는 시간대로 센다.
     */
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /**
     * ★ {@code Math.random()} 이 아니라 {@link SecureRandom} 을 쓴다. 값을 맞히면 곧 이득이
     *   되는 자리이고, 일반 난수는 몇 번만 관찰하면 다음 값을 계산할 수 있다. 다섯 판이면
     *   한 판당 32가지뿐이라 더 그렇다.
     */
    private final RandomGenerator random = new SecureRandom();

    private final ZzalGameRepository gameRepository;
    private final ZzalPetRepository petRepository;
    private final RewardService rewardService;
    private final int dailyLimit;

    public GameService(ZzalGameRepository gameRepository,
                       ZzalPetRepository petRepository,
                       RewardService rewardService,
                       @Value("${app.zzal.game.daily-limit:5}") int dailyLimit) {
        this.gameRepository = gameRepository;
        this.petRepository = petRepository;
        this.rewardService = rewardService;
        this.dailyLimit = dailyLimit;
    }

    /** 한 판 친 결과. 방금 친 판의 답만 들어 있다. */
    public record GuessResult(ZzalGame game, int round, char pick, char answer, boolean hit) {
    }

    /**
     * 새 판을 시작한다.
     *
     * ★ 진행 중인 판이 있으면 <b>새로 만들지 않고 그것을 그대로 돌려준다.</b> 두 번 눌렀거나
     *   새로고침한 사람에게 새 판을 내주면, 치던 판이 버려진 채 남아 하루 횟수만 먹는다.
     *   덤으로 이 API 가 두 번 눌러도 안전해진다.
     */
    @Transactional
    public ZzalGame start(Long userId, Long petId, Instant now) {
        // ★ 펫을 잠그고 시작한다 — 안 잠그면 버튼을 빠르게 두 번 눌렀을 때 두 요청이
        //   <b>둘 다</b> "진행 중인 판 없음" 과 "하루 제한 미만" 을 통과해, 판이 둘 생기고
        //   하루 횟수도 두 번 먹는다. 검사와 저장 사이의 틈을 없앤다.
        ZzalPet pet = findMyPetForUpdate(userId, petId);

        Optional<ZzalGame> playing = gameRepository.findFirstByPetIdAndFinishedAtIsNullOrderByIdDesc(pet.getId());
        if (playing.isPresent()) {
            return playing.get();
        }

        if (playedToday(userId, now) >= dailyLimit) {
            throw new BusinessException(ErrorCode.ZZAL_GAME_DAILY_LIMIT);
        }

        return gameRepository.save(ZzalGame.start(userId, pet.getId(), drawAnswers(), now));
    }

    /**
     * 한 판 친다.
     *
     * ★ 화면이 보낸 gameId 를 믿지 않고 <b>펫과 사람이 모두 맞는지</b> 확인한다. 남의 판
     *   번호를 넣어 부르면 그 판의 답이 한 글자씩 새어 나가고, 다섯 번이면 통째로 새는데
     *   그걸 막는 것이 이 검사다.
     */
    @Transactional
    public GuessResult guess(Long userId, Long petId, Long gameId, char pick, Instant now) {
        ZzalPet pet = findMyPet(userId, petId);

        // ★ 잠그고 꺼낸다 — 버튼을 빠르게 두 번 누르면 두 요청이 동시에 "안 끝났다" 를
        //   통과해 한 판으로 보상을 두 번 받을 수 있다(지금은 NONE 이라 티가 안 날 뿐이다).
        ZzalGame game = gameRepository.findByIdForUpdate(gameId)
                .filter(g -> g.getUserId().equals(userId) && g.getPetId().equals(pet.getId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.ZZAL_GAME_NOT_FOUND));

        // ★ 끝난 판에 계속 치는 것을 막는다. 안 막으면 이긴 판을 다시 쳐서 보상을 반복해
        //   받을 수 있다(지금은 NONE 이지만 켜는 순간 그대로 구멍이 된다).
        if (game.isFinished()) {
            throw new BusinessException(ErrorCode.ZZAL_GAME_FINISHED);
        }

        int round = game.round();
        boolean hit = game.guess(pick, now);

        // 이긴 순간에만, 그리고 판이 끝난 그 한 번에만 부른다.
        if (game.isFinished() && game.isWin()) {
            rewardService.forGameWin(pet.getId(), now);
        }

        return new GuessResult(game, round, pick, revealed(pick, hit), hit);
    }

    /**
     * 지금 치던 판. 없으면 비어 있다.
     *
     * ★ 최소형에 굳이 이걸 넣은 이유 — 다섯 왕복이라 <b>중간에 새로고침이 반드시 일어난다.</b>
     *   이게 없으면 그 사람은 판을 다시 못 잡고, 새로 시작하려 해도 위의 "진행 중이면 그걸
     *   돌려준다" 에 걸려 같은 판이 나오는데 몇 번째인지를 모른다.
     */
    @Transactional(readOnly = true)
    public Optional<ZzalGame> current(Long userId, Long petId) {
        ZzalPet pet = findMyPet(userId, petId);
        return gameRepository.findFirstByPetIdAndFinishedAtIsNullOrderByIdDesc(pet.getId());
    }

    /** 오늘 몇 판 더 할 수 있나. 화면이 "오늘은 여기까지" 를 미리 보여주는 값. */
    @Transactional(readOnly = true)
    public int remainingToday(Long userId, Instant now) {
        return (int) Math.max(0, dailyLimit - playedToday(userId, now));
    }

    // ── 안쪽 ──────────────────────────────────────────────────────────────

    /**
     * 내 펫을 꺼낸다.
     *
     * ★ 남의 펫이면 403 이 아니라 404 다 — 펫 API 와 같은 판정이다. 403 은 "그 번호의 펫이
     *   있다" 는 사실을 알려주는 셈이라 번호를 훑어 남의 펫을 셀 수 있게 된다.
     */
    /** 잠그고 꺼낸다. 한 펫에 대해 "동시에 하나만" 이어야 하는 일을 시작할 때. */
    private ZzalPet findMyPetForUpdate(Long userId, Long petId) {
        return petRepository.findByIdForUpdate(petId)
                .filter(p -> p.isOwnedBy(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND));
    }

    private ZzalPet findMyPet(Long userId, Long petId) {
        return petRepository.findById(petId)
                .filter(p -> p.isOwnedBy(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND));
    }

    private long playedToday(Long userId, Instant now) {
        Instant dayStart = now.atZone(ZONE).toLocalDate().atStartOfDay(ZONE).toInstant();
        return gameRepository.countByUserIdAndStartedAtGreaterThanEqual(userId, dayStart);
    }

    private String drawAnswers() {
        StringBuilder sb = new StringBuilder(ZzalGame.ROUNDS);
        for (int i = 0; i < ZzalGame.ROUNDS; i++) {
            sb.append(random.nextBoolean() ? 'L' : 'R');
        }
        return sb.toString();
    }

    /**
     * 방금 친 판의 답을 되돌려 만든다.
     *
     * ★★ 답을 저장소에서 꺼내지 않는 것이 핵심이다. 꺼내 오는 코드가 한 줄이라도 있으면
     *    언젠가 "편하니까" 다섯 글자를 통째로 응답에 실어 보내는 일이 생긴다. 맞았는지와
     *    내가 고른 것만으로 답이 정해지므로, 그 줄이 아예 필요 없다.
     */
    private static char revealed(char pick, boolean hit) {
        if (hit) {
            return pick;
        }
        return pick == 'L' ? 'R' : 'L';
    }
}
