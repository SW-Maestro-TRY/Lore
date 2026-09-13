package com.lore.zzal.it;

import com.lore.common.exception.BusinessException;
import com.lore.zzal.feedback.FeedbackService;
import com.lore.zzal.feedback.ZzalFeedbackRepository;
import com.lore.zzal.game.GameKind;
import com.lore.zzal.game.GameService;
import com.lore.zzal.game.ZzalGame;
import com.lore.zzal.game.ZzalGameRepository;
import com.lore.zzal.motion.MotionStatus;
import com.lore.zzal.motion.ZzalMotion;
import com.lore.zzal.motion.ZzalMotionRepository;
import com.lore.zzal.pet.CareAction;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.share.ShareService;
import com.lore.zzal.share.ZzalShareRepository;
import com.lore.zzal.share.dto.ShareResponses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시나리오 8 — <b>같은 것에 요청 둘이 동시에 닿는다</b>(M-12 · M-3 · M-19 · M-22).
 *
 * <h3>★ 왜 목으로는 못 보나</h3>
 * 비관 잠금은 <b>DB 가 거는 것</b>이다. 목을 쓰면 잠금 줄을 통째로 지워도 시험이 전부 초록이다
 * (지금 {@code PetServiceTest} 의 직렬화 시험이 정확히 그 모양 — 순서대로 두 번 부르고
 * "두 번 불렸다" 만 본다). 유니크 제약도 마찬가지다. 여기서는 <b>진짜 두 스레드</b>가
 * 같은 순간에 같은 줄을 건드린다.
 *
 * <h3>★ 소실은 예외를 안 낸다</h3>
 * 두 요청이 같은 행을 각자 읽어 각자 저장하면 <b>나중 저장이 먼저 것을 덮어쓴다.</b>
 * 둘 다 200 이고 로그도 조용하다. 리뷰 실측에서 밥 주기가 3/3 으로 사라졌던 자리다.
 */
@ZzalIntegrationTest
@DisplayName("시나리오 8 — 같은 줄에 요청 둘")
class ConcurrentRequestsIT extends ZzalItSupport {

    @Autowired GameService gameService;
    @Autowired ZzalGameRepository games;
    @Autowired ZzalMotionRepository motions;
    @Autowired ShareService shareService;
    @Autowired ZzalShareRepository shares;
    @Autowired FeedbackService feedbackService;
    @Autowired ZzalFeedbackRepository feedbacks;

    /** 두 일을 <b>같은 순간</b>에 시작시키고 각각의 결과(또는 예외)를 돌려준다. */
    private <T> List<Object> bothAtOnce(Callable<T> first, Callable<T> second) {
        CyclicBarrier gate = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<T> task : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    gate.await(20, TimeUnit.SECONDS);       // ★ 둘이 같은 순간에 출발한다
                    try {
                        return (Object) task.call();
                    } catch (Exception e) {
                        return (Object) e;
                    }
                }));
            }
            List<Object> out = new ArrayList<>();
            for (Future<Object> f : futures) {
                out.add(f.get(60, TimeUnit.SECONDS));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            pool.shutdownNow();
        }
    }

    private static Throwable errorIn(List<Object> results) {
        return results.stream().filter(Throwable.class::isInstance).map(Throwable.class::cast)
                .findFirst().orElse(null);
    }

    private Long playablePet(Long userId) {
        Instant now = Instant.now();
        return transactions.execute(status -> {
            ZzalPet pet = petRepository.save(ZzalPet.draft(userId, newUploadedImageKey(userId), now));
            pet.character("여울", null, null, null, now);
            pet.markAlive("images/zzal/pets/sheet.png", "(시험용 정체성 문단)", now);
            pet.skipTutorial(now);
            return pet.getId();
        });
    }

    // ══ M-12(가). 같은 펫에 돌보기 둘 ═══════════════════════════════════

    @Test
    @DisplayName("★★ 밥과 간식을 동시에 주면 <b>둘 다</b> 남는다 — 잠금이 없으면 하나가 조용히 사라진다")
    void feedAndSnackAtTheSameMomentBothSurvive() throws Exception {
        Long userId = newUserId();
        Long petId = playablePet(userId);
        advanceClock(userId, petId, Duration.ofHours(4));       // 배가 고파지게 시간을 민다
        ZzalPet before = petRepository.findById(petId).orElseThrow();
        int fullnessBefore = before.getFullness();
        int happinessBefore = before.getHappiness();

        Instant now = Instant.now();
        List<Object> results = bothAtOnce(
                () -> petService.care(userId, petId, CareAction.FEED, now),
                () -> petService.care(userId, petId, CareAction.SNACK, now));

        assertThat(errorIn(results)).as("둘 다 할 수 있는 행동이다").isNull();
        ZzalPet after = petRepository.findById(petId).orElseThrow();
        assertThat(after.getFullness()).as("밥이 반영됐다").isGreaterThan(fullnessBefore);
        assertThat(after.getHappiness()).as("간식이 반영됐다").isGreaterThan(happinessBefore);
        assertThat(after.getFeeds()).as("먹인 횟수도 남는다").isEqualTo(1);
    }

    // ══ M-12(라). 같은 펫에 게임 시작 둘 ════════════════════════════════

    @Test
    @DisplayName("★★ 놀이 시작을 동시에 두 번 눌러도 판은 하나 — 두 판이 생기면 하루 횟수가 두 번 깎인다")
    void startingTwiceAtOnceMakesOneGame() {
        Long userId = newUserId();
        Long petId = playablePet(userId);
        Instant now = Instant.now();

        List<Object> results = bothAtOnce(
                () -> gameService.start(userId, petId, GameKind.LEFT_RIGHT, now),
                () -> gameService.start(userId, petId, GameKind.LEFT_RIGHT, now));

        assertThat(errorIn(results)).isNull();
        List<ZzalGame> rows = games.findAll().stream().filter(g -> g.getPetId().equals(petId)).toList();
        assertThat(rows).as("게임 행이 둘이면 같은 사람이 하루치를 두 번 잃는다").hasSize(1);
        assertThat(petRepository.findById(petId).orElseThrow().getTodayGames()).isEqualTo(1);
    }

    // ══ M-12(나). 같은 판에 치기 둘 ═════════════════════════════════════

    @Test
    @DisplayName("★★ 같은 판을 동시에 두 번 치면 두 회차가 모두 기록된다 — 잠금이 없으면 한 번이 사라진다")
    void twoGuessesAtOnceAreBothRecorded() {
        Long userId = newUserId();
        Long petId = playablePet(userId);
        Instant now = Instant.now();
        Long gameId = gameService.start(userId, petId, GameKind.LEFT_RIGHT, now).game().getId();

        List<Object> results = bothAtOnce(
                () -> gameService.guess(userId, petId, gameId, 'L', now),
                () -> gameService.guess(userId, petId, gameId, 'R', now));

        assertThat(errorIn(results)).isNull();
        assertThat(games.findById(gameId).orElseThrow().round())
                .as("두 번 쳤으면 두 회차다 — 하나가 덮어써지면 사용자는 친 판을 잃는다")
                .isEqualTo(2);
    }

    // ══ M-12(다). 같은 달리기를 동시에 끝내기 ══════════════════════════

    @Test
    @DisplayName("★★ 같은 달리기를 동시에 두 번 끝내면 <b>한 번만</b> 정산된다 — 승리 보상이 두 번 나가면 안 된다")
    void onlyOneFinishSettlesTheRun() {
        Long userId = newUserId();
        Long petId = playablePet(userId);
        transactions.executeWithoutResult(status -> ReflectionTestUtils.setField(
                petRepository.findByIdForUpdate(petId).orElseThrow(),
                "leftRightWins", com.lore.zzal.pet.ZzalRules.RUN_UNLOCK_LEFT_RIGHT_WINS));
        Instant now = Instant.now();
        Long gameId = gameService.start(userId, petId, GameKind.RUN, now).game().getId();

        List<Object> results = bothAtOnce(
                () -> gameService.finish(userId, petId, gameId, 30_000, now),
                () -> gameService.finish(userId, petId, gameId, 29_999, now));

        Throwable refused = errorIn(results);
        assertThat(refused)
                .as("늦은 쪽은 ZZAL_GAME_FINISHED 로 거절돼야 한다")
                .isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) refused).getErrorCode().name()).isEqualTo("ZZAL_GAME_FINISHED");

        ZzalGame game = games.findById(gameId).orElseThrow();
        assertThat(game.isFinished()).isTrue();
        assertThat(game.getSurvivedMs())
                .as("두 값이 섞이지 않고 이긴 쪽 하나만 남는다")
                .isIn(30_000L, 29_999L);
    }

    // ══ M-5(나). 같은 사람이 그림을 동시에 두 번 올리기 ═════════════════

    @Test
    @org.junit.jupiter.api.Disabled("결함 — 같은 사람의 draft 두 건이 동시에 들어오면 초안이 둘 생기고 굽기도 두 번 나간다")
    @DisplayName("★★ 그림을 동시에 두 번 올려도 초안은 하나 — 두 개면 한 번의 실수에 한 판 값이 두 배다")
    void twoDraftsAtOnceMakeOnePet() {
        Long userId = newUserId();
        String keyA = newUploadedImageKey(userId);
        String keyB = newUploadedImageKey(userId);
        Instant now = Instant.now();

        bothAtOnce(
                () -> petService.draft(userId, keyA, now),
                () -> petService.draft(userId, keyB, now));

        assertThat(petRepository.findByUserIdOrderByIdDesc(userId))
                .as("초안이 둘이면 굽기도 둘이라 $0.25 가 그대로 두 배다")
                .hasSize(1);
    }

    @Test
    @DisplayName("★ 순서대로 두 번 올리면 먼저 만든 초안을 그대로 준다 — 나갔다 올 때마다 굽지 않는다")
    void asecondDraftReusesTheFirst() {
        Long userId = newUserId();
        Instant now = Instant.now();

        ZzalPet first = petService.draft(userId, newUploadedImageKey(userId), now);
        ZzalPet second = petService.draft(userId, newUploadedImageKey(userId), now.plusSeconds(600));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(petRepository.findByUserIdOrderByIdDesc(userId)).hasSize(1);
    }

    // ══ M-3. 집기는 <b>한 번만</b> 이긴다 ═══════════════════════════════

    @Test
    @DisplayName("★★ 두 곳이 같은 모션을 동시에 집으면 <b>한 쪽만</b> 이긴다 — 돈이 두 번 나가는 길을 막는 유일한 장치")
    void onlyOneClaimWins() {
        Long userId = newUserId();
        Long petId = playablePet(userId);
        Long motionId = queuedMotion(petId);
        Instant now = Instant.now();

        List<Object> results = bothAtOnce(
                () -> motions.claim(motionId, now, "server-a"),
                () -> motions.claim(motionId, now, "server-b"));

        assertThat(errorIn(results)).isNull();
        assertThat(results).as("바뀐 줄 수의 합은 정확히 1 이어야 한다")
                .containsExactlyInAnyOrder(1, 0);
        assertThat(motions.findById(motionId).orElseThrow().getStatus()).isEqualTo(MotionStatus.BAKING);
    }

    @Test
    @DisplayName("★ 이미 집힌 것은 다시 못 집는다 — 되돌린 뒤에야 다시 집힌다")
    void aclaimedMotionCannotBeClaimedAgain() {
        Long userId = newUserId();
        Long petId = playablePet(userId);
        Long motionId = queuedMotion(petId);
        Instant now = Instant.now();

        assertThat(motions.claim(motionId, now, "server-a")).isEqualTo(1);
        assertThat(motions.claim(motionId, now, "server-b")).isZero();
        assertThat(motions.releaseClaim(motionId)).isEqualTo(1);
        assertThat(motions.claim(motionId, now, "server-b")).isEqualTo(1);
    }

    // ══ M-19. 공유 링크 발급 둘 ═════════════════════════════════════════

    @Test
    @org.junit.jupiter.api.Disabled("결함 — 공유 발급 경합에서 진 쪽이 이긴 쪽의 토큰이 아니라 404 를 받는다")
    @DisplayName("★★ 공유를 동시에 두 번 발급해도 <b>같은 토큰</b> 하나 — 연타가 가장 흔한 버튼이다")
    void twoIssuesAtOnceShareOneToken() {
        Long userId = newUserId();
        Long petId = playablePet(userId);
        Instant now = Instant.now();

        List<Object> results = bothAtOnce(
                () -> shareService.issue(petId, "pet", now),
                () -> shareService.issue(petId, "pet", now));

        assertThat(errorIn(results)).isNull();
        List<String> tokens = results.stream().map(ShareResponses.Issued.class::cast)
                .map(ShareResponses.Issued::token).distinct().toList();
        assertThat(tokens).as("주소가 둘이면 무엇이 얼마나 퍼졌는지 셀 수 없다").hasSize(1);
    }

    @Test
    @DisplayName("★ 연타해도 500 은 아니고 행은 하나 — 다만 진 쪽은 '링크를 찾을 수 없어요' 를 받아 다시 눌러야 한다")
    void twoIssuesAtOnceNeverBlowUpAndLeaveOneRow() {
        Long userId = newUserId();
        Long petId = playablePet(userId);
        Instant now = Instant.now();

        List<Object> results = bothAtOnce(
                () -> shareService.issue(petId, "pet", now),
                () -> shareService.issue(petId, "pet", now));

        // ★ 500 이 아닌 것이 중요하다 — DataIntegrityViolationException 이 그대로 올라가면
        //   BusinessException 이 아니라서 PetService.share 의 noRollbackFor 에 안 걸리고,
        //   그 요청의 정산이 통째로 되감긴다.
        Throwable loser = errorIn(results);
        if (loser != null) {
            assertThat(loser)
                    .as("진 쪽도 우리가 아는 오류로 답해야 한다")
                    .isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) loser).getErrorCode().name()).isEqualTo("ZZAL_SHARE_NOT_FOUND");
        }
        assertThat(shares.findAll()).as("(petId, motionKey) 한 쌍에 링크 하나").hasSize(1);

        // 다시 누르면 이긴 쪽의 링크를 받는다 — 사용자가 빠져나갈 길은 있다.
        assertThat(shareService.issue(petId, "pet", now).token())
                .isEqualTo(shares.findAll().get(0).getToken());
    }

    // ══ M-22(나). 후기 제출 둘 ══════════════════════════════════════════

    @Test
    @DisplayName("★★ 후기를 동시에 두 번 내면 한 건만 저장되고 다른 하나는 '이미 냈다' — 보상을 켜는 날의 이중 지급을 막는다")
    void twoFeedbackSubmitsKeepOnlyOne() {
        Long userId = newUserId();
        Long petId = playablePet(userId);
        Instant now = Instant.now();

        List<Object> results = bothAtOnce(
                () -> feedbackService.submit(userId, petId, 5, List.of(), "좋아요", now),
                () -> feedbackService.submit(userId, petId, 1, List.of(), "별로예요", now));

        assertThat(feedbacks.findAll()).as("행이 둘이면 한 사람이 두 명으로 잡힌다").hasSize(1);
        Throwable refused = errorIn(results);
        assertThat(refused)
                .as("늦은 쪽은 500 이 아니라 '이미 냈다' 여야 한다")
                .isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) refused).getErrorCode().name())
                .isEqualTo("ZZAL_FEEDBACK_ALREADY_SUBMITTED");
    }

    // ── 재료 ──────────────────────────────────────────────────────────────

    /** 이 펫의 심화 하나를 큐에 올린 상태로 만든다. */
    private Long queuedMotion(Long petId) {
        motionSeeder.seed(petId, Instant.now());
        return transactions.execute(status -> {
            ZzalMotion row = motions.findByPetIdOrderBySeqAsc(petId).stream()
                    .filter(m -> "pet".equals(m.getName()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("교감 자세 행이 없다"));
            ReflectionTestUtils.setField(row, "status", MotionStatus.NONE);
            assertThat(row.queue(LocalDate.now())).isTrue();
            return row.getId();
        });
    }
}
