package com.lore.zzal.it;

import com.lore.zzal.generation.GenJob;
import com.lore.zzal.generation.GenJobRepository;
import com.lore.zzal.generation.GenKind;
import com.lore.zzal.generation.GenStatus;
import com.lore.zzal.generation.GenStepRecord;
import com.lore.zzal.generation.GenStepRecordRepository;
import com.lore.zzal.generation.GenerationRecorder;
import com.lore.zzal.generation.StepResult;
import com.lore.zzal.motion.MotionStatus;
import com.lore.zzal.motion.ZzalMotion;
import com.lore.zzal.motion.ZzalMotionRepository;
import com.lore.zzal.night.BakeTrigger;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.piece.PieceCompleted;
import com.lore.zzal.piece.PieceKind;
import com.lore.zzal.piece.ZzalPiece;
import com.lore.zzal.piece.ZzalPieceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 시나리오 7 — <b>트랜잭션 경계</b>: 커밋 뒤에 도는 것 · 거절에 되감기지 않는 것 · 롤백되는 것(M-2 · M-20 · M-24).
 *
 * <h3>★ 왜 목으로는 못 보나</h3>
 * 단위 시험에는 <b>트랜잭션이 없다.</b> 그래서 {@code registerAfterCommit} 은 곧바로 {@code task.run()} 으로
 * 빠지고({@code BakeTrigger:192}), {@code REQUIRES_NEW} 는 그냥 같은 호출이며, {@code noRollbackFor} 는
 * 되감을 것이 없어 언제나 통과한다. <b>"동기화가 없는 세계" 만 밟고 있었다.</b>
 * 여기서는 진짜 트랜잭션 안에서 같은 것을 부르고, <b>새 트랜잭션에서 다시 읽어</b> 확인한다.
 */
@ZzalIntegrationTest
@DisplayName("시나리오 7 — 트랜잭션 경계: 커밋 뒤·되감김·독립 커밋")
class TransactionBoundariesIT extends ZzalItSupport {

    @Autowired GenerationRecorder recorder;
    @Autowired GenJobRepository jobs;
    @Autowired GenStepRecordRepository steps;
    @Autowired ZzalPieceRepository pieces;
    @Autowired ZzalMotionRepository motions;
    @Autowired BakeTrigger bakeTrigger;
    @Autowired org.springframework.context.ApplicationEventPublisher events;

    // ══ M-2. REQUIRES_NEW — 바깥이 되감겨도 기록은 남는다 ════════════════

    @Test
    @DisplayName("★★ 바깥 트랜잭션이 되감겨도 단계 기록은 남는다 — 함께 사라지면 재시도가 유료 단계를 처음부터 다시 부른다")
    void stepRecordsSurviveAnOuterRollback() {
        Long userId = newUserId();
        Long petId = draftPet(userId);
        GenJob job = transactions.execute(status ->
                jobs.save(GenJob.start(petId, GenKind.HATCH, 1, "v2", Instant.now())));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .as("진짜 트랜잭션 안에서 불러야 이 시험이 뜻을 갖는다")
                    .isTrue();
            Long stepId = recorder.startStep(job.getId(), 0, "sheet");
            recorder.succeedStep(stepId, StepResult.image("sheet", "sheet.png", "m", new BigDecimal("0.0630")));
            throw new IllegalStateException("바깥에서 터졌다 — 이 트랜잭션은 통째로 되감긴다");
        })).isInstanceOf(IllegalStateException.class);

        // ★ 새 트랜잭션에서 다시 읽는다 — 같은 트랜잭션 안에서 보면 되감겼는지 알 수 없다.
        List<GenStepRecord> done = steps.findSucceededByPet(petId, GenKind.HATCH);
        assertThat(done).as("REQUIRES_NEW 가 아니면 여기가 비어 재시도가 시트를 다시 굽는다").hasSize(1);
        assertThat(done.get(0).getName()).isEqualTo("sheet");
        assertThat(done.get(0).getCostUsd()).isEqualByComparingTo("0.0630");
    }

    @Test
    @DisplayName("★ 작업 실패 기록도 바깥과 따로 커밋된다 — 어디까지 갔는지가 남아야 이어서 굽는다")
    void jobFailureIsRecordedIndependently() {
        Long userId = newUserId();
        Long petId = draftPet(userId);
        GenJob job = transactions.execute(status ->
                jobs.save(GenJob.start(petId, GenKind.HATCH, 1, "v2", Instant.now())));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            recorder.failJob(job.getId(), com.lore.zzal.generation.GenErrorCode.TIMEOUT, BigDecimal.ONE);
            throw new IllegalStateException("바깥에서 터졌다");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(GenStatus.FAILED);
    }

    // ══ M-2. AFTER_COMMIT — 커밋 전에는 실행기에 안 넘긴다 ═══════════════

    @Test
    @DisplayName("★★ 조각 완성 알림은 <b>커밋된 뒤에만</b> 굽기를 깨운다 — 되감긴 돌보기로 돈이 나가면 안 된다")
    void theCompletionEventNeverFiresForARolledBackTransaction() {
        Long userId = newUserId();
        ZzalPet pet = aliveLayerTwoPet(userId);
        Long petId = pet.getId();
        openPieces(userId, petId);
        fillAndFlagPieces(petId);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.isSynchronizationActive())
                    .as("동기화가 없으면 이 시험은 '커밋 뒤' 가 아니라 '곧바로' 를 재게 된다")
                    .isTrue();
            events.publishEvent(new PieceCompleted(petId));
            throw new IllegalStateException("돌보기가 되감긴다");
        })).isInstanceOf(IllegalStateException.class);

        sleepBriefly();
        assertThat(motions.findByPetIdOrderBySeqAsc(petId))
                .as("되감긴 트랜잭션의 알림으로는 아무것도 큐에 오르지 않는다")
                .allMatch(m -> m.getStatus() == MotionStatus.NONE);
        assertThat(pieces.findById(petId).orElseThrow().isConsumed())
                .as("조각도 그대로 남아 다음 기회에 다시 쓰인다")
                .isFalse();
    }

    @Test
    @DisplayName("★ 같은 알림이 커밋되면 굽기가 실제로 시작된다 — 위 시험이 '언제나 안 돈다' 로 통과하지 않게")
    void thesameEventDoesFireWhenCommitted() {
        Long userId = newUserId();
        ZzalPet pet = aliveLayerTwoPet(userId);
        Long petId = pet.getId();
        openPieces(userId, petId);
        fillAndFlagPieces(petId);

        transactions.executeWithoutResult(status -> events.publishEvent(new PieceCompleted(petId)));

        await("커밋 뒤 알림이 굽기를 끝까지 굴리는 것", Duration.ofSeconds(30),
                () -> motions.findByPetIdOrderBySeqAsc(petId).stream()
                        .anyMatch(m -> m.getStatus() == MotionStatus.REVIEW));
        assertThat(pieces.findById(petId).orElseThrow().isConsumed()).isTrue();
    }

    // ══ M-24. 조각 소모가 실제로 저장되는가 ═════════════════════════════

    @Test
    @DisplayName("★★ 큐 등록이 커밋되면 조각 완성도 <b>소모됨</b>으로 저장된다 — 안 되면 같은 판으로 두 번 굽는다")
    void consumingThePieceIsPersisted() {
        Long userId = newUserId();
        ZzalPet pet = aliveLayerTwoPet(userId);
        Long petId = pet.getId();
        openPieces(userId, petId);
        fillAndFlagPieces(petId);

        transactions.executeWithoutResult(status -> {
            ZzalPet locked = petRepository.findByIdForUpdate(petId).orElseThrow();
            bakeTrigger.onPieceComplete(locked, locked.now(Instant.now()));
        });

        // 새 트랜잭션에서 다시 읽는다.
        assertThat(pieces.findById(petId).orElseThrow().isConsumed())
                .as("소모 표시가 저장되지 않으면 다음 호출이 같은 완성을 또 집는다")
                .isTrue();
        assertThat(motions.findByPetIdOrderBySeqAsc(petId))
                .anyMatch(m -> m.getStatus() != MotionStatus.NONE);
    }

    @Test
    @DisplayName("★★ 그 완성은 두 번 쓸 수 없다 — 같은 밤에 재우기와 스위프가 둘 다 돌아도 심화는 하나다")
    void thesamecompletionCannotBeSpentTwice() {
        Long userId = newUserId();
        ZzalPet pet = aliveLayerTwoPet(userId);
        Long petId = pet.getId();
        openPieces(userId, petId);
        fillAndFlagPieces(petId);

        for (int i = 0; i < 3; i++) {
            transactions.executeWithoutResult(status -> {
                ZzalPet locked = petRepository.findByIdForUpdate(petId).orElseThrow();
                bakeTrigger.onPieceComplete(locked, locked.now(Instant.now()));
            });
        }

        await("굽기가 끝나는 것", Duration.ofSeconds(30),
                () -> motions.findByPetIdOrderBySeqAsc(petId).stream()
                        .anyMatch(m -> m.getStatus() == MotionStatus.REVIEW));
        sleepBriefly();

        assertThat(motions.findByPetIdOrderBySeqAsc(petId).stream()
                .filter(m -> m.getStatus() != MotionStatus.NONE).toList())
                .as("완성 하나에 심화 하나 — 두 편이 구워지면 돈도 검수도 두 배다")
                .hasSize(1);
    }

    // ══ M-20. 거절당해도 시간이 되감기지 않는다 ═════════════════════════

    @Test
    @DisplayName("★★ 게임이 거절돼도 그 요청이 먼저 돌린 정산은 커밋된다 — Game 서비스의 noRollbackFor")
    void arefusedGameKeepsTheSettlement() throws Exception {
        Long userId = newUserId();
        Long petId = playablePet(userId);
        Instant before = settledAt(petId);

        // ★ 시계만 민다 — 정산은 <b>거절당할 그 요청</b>이 하게 남겨 둔다.
        bumpClockWithoutSettling(petId, Duration.ofMinutes(40));

        // 없는 판 번호 — 무슨 시각이든 반드시 거절된다. 거절 전에 awake → touch 가 이미 돌았다.
        MvcResult refused = postAs(userId,
                "/api/zzal/v1/me/pets/%d/games/999999/guess".formatted(petId), Map.of("pick", "LEFT"));

        assertThat(refused.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
        assertThat(errorCode(refused)).isNotNull();
        assertThat(settledAt(petId))
                .as("맨 @Transactional 이면 여기가 되감겨 '거절당했더니 시간이 되돌아갔다' 가 된다")
                .isAfter(before);
    }

    @Test
    @DisplayName("★★ 채팅이 거절돼도 그 요청이 먼저 돌린 정산은 커밋된다 — Chat 서비스의 noRollbackFor")
    void arefusedChatKeepsTheSettlement() throws Exception {
        Long userId = newUserId();
        Long petId = playablePet(userId);
        Instant before = settledAt(petId);

        bumpClockWithoutSettling(petId, Duration.ofMinutes(40));

        // 아직 도래하지 않은 슬롯 — 닫혀 있다(자고 있으면 수면 거절이지만, 어느 쪽이든 거절이다).
        MvcResult refused = postAs(userId,
                "/api/zzal/v1/me/pets/%d/chat/NOON/answer".formatted(petId), Map.of("text", "안녕"));

        assertThat(refused.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
        assertThat(errorCode(refused)).isNotNull();
        assertThat(settledAt(petId))
                .as("거절 한 번에 장면·엽서·도착이 통째로 사라지면 안 된다")
                .isAfter(before);
    }

    // ── 재료 ──────────────────────────────────────────────────────────────

    private Long draftPet(Long userId) {
        return transactions.execute(status ->
                petRepository.save(ZzalPet.draft(userId, newUploadedImageKey(userId), Instant.now())).getId());
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

    /** 조회 한 번으로 3층(조각)을 연다 — 판정은 지름길 없이 진짜로 돈다. */
    private void openPieces(Long userId, Long petId) {
        try {
            getJson(userId, "/api/zzal/v1/me/pets/" + petId);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertThat(pieces.findById(petId)).as("3층이 열려야 조각 줄이 생긴다").isPresent();
    }

    /** 네 칸을 정본 메서드로 다 채운다(표를 직접 건드리지 않는다). */
    private void fillAndFlagPieces(Long petId) {
        transactions.executeWithoutResult(status -> {
            ZzalPiece row = pieces.findById(petId).orElseThrow();
            for (PieceKind kind : PieceKind.values()) {
                row.grant(kind);
            }
        });
        assertThat(pieces.findById(petId).orElseThrow().isComplete()).isTrue();
    }

    /** 굽는 스레드가 뭔가를 더 하지 않는지 잠깐 본다 — "안 일어난다" 를 재는 유일한 방법이다. */
    private static void sleepBriefly() {
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Instant settledAt(Long petId) {
        return petRepository.findById(petId).orElseThrow().getSettledAt();
    }

    /**
     * 이 펫의 시계만 앞으로 민다 — <b>정산은 하지 않는다.</b>
     *
     * ★ dev 주소({@code advance-clock})는 밀면서 정산까지 해 버린다. 그러면 "거절당할 요청이
     *   스스로 정산한다" 는 이 시험의 모양이 만들어지지 않는다.
     */
    private void bumpClockWithoutSettling(Long petId, Duration by) {
        transactions.executeWithoutResult(status ->
                petRepository.findByIdForUpdate(petId).orElseThrow().advanceDevClock(by));
    }
}
