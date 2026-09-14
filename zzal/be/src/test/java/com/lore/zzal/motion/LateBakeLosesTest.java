package com.lore.zzal.motion;

import com.lore.zzal.night.NightPlanner;
import com.lore.zzal.piece.PieceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>늦게 끝난 굽기가 이미 끝난 판정을 되돌리지 않는다</b>(1.9).
 *
 * <h3>★★ 이 시험이 지키는 사고</h3>
 * 굽기가 느려 {@code StuckMotionRecovery} 의 유예를 넘기면 그 줄은 큐로 되돌아가 <b>다른 판이 다시 구워진다.</b>
 * 그런데 처음 굽던 스레드는 죽은 것이 아니라 느릴 뿐이라, 나중에 결과를 들고 돌아온다.
 *
 * <pre>
 *   굽기 A 시작(BAKING) → 느림 → 복구가 큐로 되돌림 → 굽기 B → 검수 → OK → OPEN
 *   그제서야 A 도착 → (막지 않으면) OPEN 이 REVIEW 로 되돌아가고 판정이 지워진다
 * </pre>
 *
 * 사용자에게는 <b>아침에 받은 동작이 다시 "연습 중" 으로 사라진다.</b> 검수도 다시 해야 한다.
 *
 * <h3>★ 순서를 실제로 만들어 본다</h3>
 * "조건을 하나 더 봤다" 를 확인하는 것으로는 부족하다. A·B 두 굽기를 <b>그 순서대로</b> 태워야
 * 진짜로 막히는지 알 수 있다.
 */
@DisplayName("늦게 끝난 굽기는 진다 — 끝난 판정을 되돌리지 않는다")
class LateBakeLosesTest {

    private static final Long MOTION = 16L;
    private static final Instant T0 = Instant.parse("2026-09-11T12:00:00Z");
    private static final MotionGate.Verdict V =
            new MotionGate.Verdict(GateVerdict.REVIEW, "게이트 미적용", "g0");

    private final Map<Long, ZzalMotion> motions = new HashMap<>();
    private final List<ZzalMotionCandidate> candidates = new ArrayList<>();

    private ZzalMotionRepository motionRepository;
    private MotionRecorder recorder;
    private ZzalMotion motion;

    @BeforeEach
    void setUp() {
        MotionCatalog catalog = new MotionCatalog("", "", "v1");
        motion = ZzalMotion.forCatalog(7L, catalog.bySeq(101).orElseThrow(), T0);
        ReflectionTestUtils.setField(motion, "id", MOTION);
        motions.put(MOTION, motion);

        motionRepository = mock(ZzalMotionRepository.class);
        when(motionRepository.findByIdForUpdate(anyLong()))
                .thenAnswer(i -> Optional.ofNullable(motions.get(i.<Long>getArgument(0))));
        when(motionRepository.findById(anyLong()))
                .thenAnswer(i -> Optional.ofNullable(motions.get(i.<Long>getArgument(0))));

        ZzalMotionCandidateRepository candidateRepository = mock(ZzalMotionCandidateRepository.class);
        when(candidateRepository.save(any())).thenAnswer(i -> {
            candidates.add(i.getArgument(0));
            return i.getArgument(0);
        });

        recorder = new MotionRecorder(motionRepository, candidateRepository);
    }

    /** 운영에서는 {@code claim} 이 DB 에서 집는다. 시험에서는 그 결과만 만든다. */
    private void claim() {
        ReflectionTestUtils.setField(motion, "status", MotionStatus.BAKING);
    }

    @Test
    @DisplayName("★★ A 가 느린 사이 B 가 구워져 판정까지 끝나면, 뒤늦은 A 는 아무것도 못 바꾼다")
    void lateBakeCannotUndoAJudgedRow() {
        // 1. 굽기 A 가 시작된다
        motion.queue(LocalDate.of(2026, 9, 11));
        claim();

        // 2. A 가 느리다 → 복구가 큐로 되돌리고, 굽기 B 가 시작돼 끝난다
        motion.releaseClaim();
        claim();
        assertThat(recorder.toReview(MOTION, "grid-B.png", "B.webp", 298, 330, V)).isTrue();

        // 3. 사람이 B 를 보고 통과시킨다
        motion.review(HumanVerdict.OK, "좋다", T0);
        motion.approve(T0);
        assertThat(motion.getStatus()).isEqualTo(MotionStatus.OPEN);

        // 4. 그제서야 A 가 도착한다 — 여기서 막혀야 한다
        assertThat(recorder.toReview(MOTION, "grid-A.png", "A.webp", 298, 330, V)).isFalse();

        assertThat(motion.getStatus())
                .as("판정이 끝난 줄이 검수 대기로 되돌아가면 안 된다")
                .isEqualTo(MotionStatus.OPEN);
        assertThat(motion.getHumanVerdict())
                .as("상훈님 판정이 지워지면 안 된다")
                .isEqualTo(HumanVerdict.OK);
        assertThat(motion.getImageKey())
                .as("공개될 그림이 뒤늦은 판으로 바뀌면 안 된다")
                .isEqualTo("B.webp");
        assertThat(candidates)
                .as("진 판은 후보로도 안 남는다 — 남기면 한 판이 둘로 보인다")
                .hasSize(1);
    }

    @Test
    @DisplayName("굽는 중이던 줄은 정상적으로 검수 대기가 된다 — 문을 너무 좁게 잠그지 않았는지")
    void bakingRowStillPasses() {
        motion.queue(LocalDate.of(2026, 9, 11));
        claim();

        assertThat(recorder.toReview(MOTION, "grid.png", "ok.webp", 298, 330, V)).isTrue();
        assertThat(motion.getStatus()).isEqualTo(MotionStatus.REVIEW);
        assertThat(candidates).hasSize(1);
    }

    @Nested
    @DisplayName("큐에 다시 올리는 문")
    class Queueing {

        @Test
        @DisplayName("★ 굽는 중·검수 중·공개된 줄은 큐로 안 돌아간다 — 되돌리면 같은 것을 두 번 굽는다")
        void queueDoesNotUndoWorkInFlight() {
            LocalDate night = LocalDate.of(2026, 9, 12);

            claim();
            assertThat(motion.queue(night)).as("굽는 중").isFalse();

            ReflectionTestUtils.setField(motion, "status", MotionStatus.REVIEW);
            assertThat(motion.queue(night)).as("검수 중").isFalse();

            ReflectionTestUtils.setField(motion, "status", MotionStatus.LOCAL_REQUESTED);
            assertThat(motion.queue(night)).as("맥미니가 다시 굽는 중").isFalse();

            ReflectionTestUtils.setField(motion, "status", MotionStatus.OPEN);
            assertThat(motion.queue(night)).as("이미 공개").isFalse();
        }

        @Test
        @DisplayName("★★ 보류함은 자동 경로로 안 열린다 — 사람만 꺼낸다")
        void holdOpensOnlyByHand() {
            LocalDate night = LocalDate.of(2026, 9, 12);
            motion.hold();

            assertThat(motion.queue(night))
                    .as("자동 경로(밤 계획·스위프·트리거)가 쓰는 문")
                    .isFalse();
            assertThat(motion.getStatus()).isEqualTo(MotionStatus.HOLD);

            assertThat(motion.releaseFromHold(night)).isTrue();
            assertThat(motion.getStatus()).isEqualTo(MotionStatus.QUEUED);
            assertThat(motion.getRegenRound())
                    .as("꺼내면 처음 조건으로 — 안 그러면 한 판 실패로 곧바로 다시 보류함이 된다")
                    .isZero();
        }

        @Test
        @DisplayName("아직 아무도 안 집은 줄·실패한 줄은 다시 올릴 수 있다")
        void queueableStatesStillWork() {
            LocalDate night = LocalDate.of(2026, 9, 12);

            assertThat(motion.queue(night)).as("NONE").isTrue();
            assertThat(motion.queue(night)).as("QUEUED — 밤만 갱신된다").isTrue();

            motion.failNight();
            assertThat(motion.queue(night)).as("FAILED").isTrue();
        }
    }

    @Nested
    @DisplayName("실패한 줄의 재시도 리듬")
    class RetryRhythm {

        private NightPlanner planner;
        private com.lore.zzal.pet.ZzalPet pet;

        @BeforeEach
        void setUp() {
            MotionCatalog catalog = mock(MotionCatalog.class);
            when(catalog.isBakeable(any())).thenReturn(true);
            when(catalog.gifts()).thenReturn(List.of());

            ZzalMotionRepository repo = mock(ZzalMotionRepository.class);
            when(repo.findByPetIdOrderBySeqAsc(anyLong())).thenReturn(List.of(motion));

            planner = new NightPlanner(repo, catalog, mock(PieceService.class));
            motion.failNight();

            // 밤 계획은 살아 있고 여행 중이 아닌 펫에만 돈다
            pet = com.lore.zzal.PetFixture.hatching(1L, "여울", null, "k", T0);
            pet.markAlive("s", "i", T0);
            pet.skipTutorial(T0);
            ReflectionTestUtils.setField(pet, "id", 7L);
        }

        @Test
        @DisplayName("★★ 밤(재우기·스위프)에는 실패한 줄이 다시 큐에 오른다 — 여러 번 굽는 것이 설계다")
        void nightRequeuesFailedRows() {
            // 같은 지시문으로 구워도 나오는 것이 매번 다르다. 실패는 끝이 아니라 다음 판의 신호다.
            assertThat(planner.plan(pet, LocalDate.of(2026, 9, 12), NightPlanner.Occasion.NIGHT))
                    .isEqualTo(1);
            assertThat(motion.getStatus()).isEqualTo(MotionStatus.QUEUED);
        }

        @Test
        @DisplayName("★★ 조각이 찬 순간에는 실패한 줄을 안 건드린다 — 리듬은 하루 한 번이다")
        void pieceTriggerLeavesFailedRowsAlone() {
            // 조각은 낮에 여러 번 찬다. 여기서 재시도까지 돌면 하루에 몇 번이고 다시 구워진다.
            assertThat(planner.plan(pet, LocalDate.of(2026, 9, 12), NightPlanner.Occasion.PIECE))
                    .as("방금 찬 조각의 새 동작만 올린다 — 여기 실패한 줄은 없다")
                    .isZero();
            assertThat(motion.getStatus())
                    .as("실패한 채로 밤을 기다린다")
                    .isEqualTo(MotionStatus.FAILED);
        }

        @Test
        @DisplayName("★ 사람이 물린 줄(보류함)은 밤에도 안 올라온다 — 생성 실패와 사람의 물림은 다르다")
        void heldRowsStayHeldEvenAtNight() {
            motion.hold();

            assertThat(planner.plan(pet, LocalDate.of(2026, 9, 12), NightPlanner.Occasion.NIGHT))
                    .isZero();
            assertThat(motion.getStatus())
                    .as("일곱 판이 다 아니면 판이 아니라 지시문·원본 문제다 — 사람이 고친 뒤 꺼낸다")
                    .isEqualTo(MotionStatus.HOLD);
        }
    }
}
