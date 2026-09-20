package com.lore.zzal.night;

import com.lore.zzal.PetFixture;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.MotionService;
import com.lore.zzal.motion.MotionStatus;
import com.lore.zzal.motion.ZzalMotion;
import com.lore.zzal.motion.ZzalMotionRepository;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.piece.PieceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.lore.zzal.pet.AwakeClockTest.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>조건을 채운 그 순간</b> 굽는다(정본 1.8·1.9).
 *
 * <h3>★ 무엇을 지키는 시험인가</h3>
 * <ul>
 *   <li>세 조건이 <b>각자의 순간</b>에 일감을 만든다 — 구르기는 튜토리얼 완주, 나머지는 잠들 때·조각이 찰 때</li>
 *   <li><b>즉시 굽기와 스위프가 같은 줄을 두 번 잡지 않는다.</b> 여기가 뚫리면 한 동작에 돈이 두 번 나간다</li>
 *   <li>구르기가 <b>함께한 날 3일이 아니라 튜토리얼 완주</b>에 걸린다(1.7)</li>
 * </ul>
 */
@DisplayName("즉시 굽기 — 조건을 채운 그 순간")
class BakeTriggerTest {

    private static final Instant T0 = kst("2026-09-05 18:00");

    private final MotionCatalog realCatalog = new MotionCatalog("", "", "v1");
    private MotionCatalog catalog;
    private ZzalMotionRepository repo;
    private MotionService motionService;
    private NightPlanner planner;
    private BakeTrigger trigger;
    private List<ZzalMotion> rows;
    private ZzalPet pet;

    /** 집기(claim)가 성공한 횟수 — 실제 UPDATE 를 흉내 낸다. */
    private final Map<Long, Integer> claimed = new HashMap<>();
    private final List<Long> baked = new ArrayList<>();

    @BeforeEach
    void setUp() {
        pet = PetFixture.hatching(1L, "여울", null, "k", T0);
        pet.markAlive("s", "i", T0);
        pet.skipTutorial(T0);
        ReflectionTestUtils.setField(pet, "id", 7L);

        rows = new ArrayList<>(realCatalog.all().stream()
                .map(s -> ZzalMotion.forCatalog(7L, s, T0)).toList());
        for (int i = 0; i < rows.size(); i++) {
            ReflectionTestUtils.setField(rows.get(i), "id", (long) (100 + i));
        }

        repo = mock(ZzalMotionRepository.class);
        when(repo.findByPetIdOrderBySeqAsc(anyLong())).thenReturn(rows);
        when(repo.findById(anyLong())).thenAnswer(i -> rows.stream()
                .filter(m -> i.getArgument(0).equals(m.getId())).findFirst());
        // ★ 집기는 QUEUED 일 때만 1 을 돌려준다 — 진짜 UPDATE ... WHERE status='QUEUED' 와 같은 규칙.
        when(repo.claim(anyLong(), any(), anyString())).thenAnswer(i -> {
            Long id = i.getArgument(0);
            ZzalMotion m = rows.stream().filter(r -> id.equals(r.getId())).findFirst().orElseThrow();
            if (m.getStatus() != MotionStatus.QUEUED) {
                return 0;
            }
            ReflectionTestUtils.setField(m, "status", MotionStatus.BAKING);
            claimed.merge(id, 1, Integer::sum);
            return 1;
        });

        catalog = mock(MotionCatalog.class);
        when(catalog.gifts()).thenReturn(realCatalog.gifts());
        when(catalog.isBakeable(anyString())).thenReturn(true);

        PieceService pieceService = mock(PieceService.class);
        planner = new NightPlanner(repo, catalog, pieceService);

        motionService = mock(MotionService.class);
        // 실행기는 바로 돌린다 — 커밋 뒤 동작을 시험에서 재현하기 위해.
        trigger = new BakeTrigger(planner, repo, motionService, catalog, Runnable::run);
        baked.clear();
        claimed.clear();
    }

    private ZzalMotion row(int seq) {
        return rows.stream().filter(m -> m.getSeq() == seq).findFirst().orElseThrow();
    }

    @Nested
    @DisplayName("구르기 — 튜토리얼을 끝낸 순간")
    class Roll {

        @Test
        @DisplayName("★★ 튜토리얼 완주가 구르기를 굽는다 — 함께한 날 3일이 아니다(1.7)")
        void bakesOnTutorialDone() {
            trigger.onTutorialDone(pet, T0);

            assertThat(row(101).getStatus()).isEqualTo(MotionStatus.BAKING);
            verify(motionService).bakeNow(row(101).getId());
        }

        @Test
        @DisplayName("지시문이 없으면 조건이 차도 안 굽는다 — 사용자는 밤을 헛되이 기다리지 않는다")
        void skipsWithoutPrompt() {
            when(catalog.isBakeable("roll")).thenReturn(false);

            trigger.onTutorialDone(pet, T0);

            assertThat(row(101).getStatus()).isEqualTo(MotionStatus.NONE);
            verify(motionService, never()).bakeNow(anyLong());
        }

        @Test
        @DisplayName("두 번 불러도 한 번만 굽는다")
        void idempotent() {
            trigger.onTutorialDone(pet, T0);
            trigger.onTutorialDone(pet, T0);

            verify(motionService).bakeNow(row(101).getId());
            assertThat(claimed.get(row(101).getId())).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("뒤로 넘어짐 — 좌우 맞히기 첫 패배")
    class FallBack {

        @Test
        @DisplayName("★★ 진 그 순간 굽는다 — 옛 조건(함께한 날 3일)은 그림과 아무 관계가 없었다")
        void bakesOnFirstLoss() {
            trigger.onFirstGameLoss(pet, T0);

            assertThat(row(102).getStatus()).isEqualTo(MotionStatus.BAKING);
            verify(motionService).bakeNow(row(102).getId());
        }

        @Test
        @DisplayName("지시문이 없으면 조건이 차도 안 굽는다")
        void skipsWithoutPrompt() {
            when(catalog.isBakeable("fall_back")).thenReturn(false);

            trigger.onFirstGameLoss(pet, T0);

            assertThat(row(102).getStatus()).isEqualTo(MotionStatus.NONE);
            verify(motionService, never()).bakeNow(anyLong());
        }

        @Test
        @DisplayName("★★ 두 번 져도 한 번만 굽는다 — 선물은 하나다")
        void onlyTheFirstLossCounts() {
            trigger.onFirstGameLoss(pet, T0);
            trigger.onFirstGameLoss(pet, T0.plusSeconds(600));

            verify(motionService).bakeNow(row(102).getId());
            assertThat(claimed.get(row(102).getId())).isEqualTo(1);
        }

        @Test
        @DisplayName("★★ 즉시 굽기가 집은 줄은 스위프가 다시 못 집는다 — 여기가 뚫리면 돈이 두 번 나간다")
        void sweepCannotTakeWhatWeClaimed() {
            trigger.onFirstGameLoss(pet, T0);

            // 스위프가 같은 줄을 집으려 한다 — 이미 BAKING 이라 0 이 돌아와야 한다.
            int won = repo.claim(row(102).getId(), Instant.now(), "sweep");

            assertThat(won).isZero();
            assertThat(claimed.get(row(102).getId())).isEqualTo(1);
            verify(motionService).bakeNow(row(102).getId());
        }

        @Test
        @DisplayName("★★★ 재우는 것으로는 안 열린다 — 옛 3일 조건이 남아 있으면 한 사람이 두 번 받는다")
        void sleepingDoesNotOpenIt() {
            ReflectionTestUtils.setField(pet, "daysTogether", 3);
            ReflectionTestUtils.setField(pet, "todayCareMiss", 0);
            pet.settle(kst("2026-09-05 19:00"));
            pet.sleep(kst("2026-09-05 19:00"));

            trigger.onSleep(pet, kst("2026-09-05 19:00"));

            assertThat(row(102).getStatus()).isEqualTo(MotionStatus.NONE);
            verify(motionService, never()).bakeNow(anyLong());
        }
    }

    @Nested
    @DisplayName("실행기가 안 받으면")
    class Rejected {

        @Test
        @DisplayName("★ 집기를 되돌린다 — 안 그러면 그 줄은 BAKING 인 채 영영 남는다")
        void releasesClaim() {
            BakeTrigger t = new BakeTrigger(planner, repo, motionService, catalog, task -> {
                throw new java.util.concurrent.RejectedExecutionException("종료 중");
            });

            t.onTutorialDone(pet, T0);

            verify(repo).releaseClaim(eq(row(101).getId()));
        }
    }
}
