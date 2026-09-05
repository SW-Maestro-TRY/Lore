package com.lore.zzal.night;

import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.MotionService;
import com.lore.zzal.motion.MotionStatus;
import com.lore.zzal.motion.ZzalMotion;
import com.lore.zzal.motion.ZzalMotionRepository;
import com.lore.zzal.pet.PetPhase;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalPetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static com.lore.zzal.pet.AwakeClockTest.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 밤 스위프 — 실패 주입(verify-failure-paths).
 *
 * ★ 여기서 지키는 것은 "같은 밤을 두 번 굽지 않는다" 와 "K 를 넘기면 이월" 이다. 둘 다 정상 경로에서는
 *   한 번도 실행되지 않는 분기라, 일부러 만들어 넣어야 돈다: 스위프 도중 재기동 · claim 경쟁 2스레드 ·
 *   K 초과 · 스위프 비활성.
 */
@DisplayName("밤 스위프 — 실패 주입")
class NightSweepTest {

    private static final Instant T23 = kst("2026-09-05 23:00");
    private static final LocalDate NIGHT = LocalDate.of(2026, 9, 5);

    private final MotionCatalog catalog = new MotionCatalog("", "", "v1");
    private final Map<Long, ZzalMotion> motions = new ConcurrentHashMap<>();
    private final Map<LocalDate, ZzalNightRun> runs = new ConcurrentHashMap<>();
    private final List<Long> baked = new ArrayList<>();
    private ZzalPetRepository petRepo;
    private ZzalMotionRepository motionRepo;
    private ZzalNightRunRepository runRepo;
    private NightPlanner planner;
    private MotionService motionService;
    private Executor executor;

    @BeforeEach
    void setUp() {
        petRepo = mock(ZzalPetRepository.class);
        motionRepo = mock(ZzalMotionRepository.class);
        runRepo = mock(ZzalNightRunRepository.class);
        planner = mock(NightPlanner.class);
        motionService = mock(MotionService.class);
        executor = Runnable::run;                                  // 동기 실행기 — 굽기 호출 순서를 그대로 본다

        // run 표 — PK 충돌을 흉내 낸다
        when(runRepo.findById(any())).thenAnswer(inv -> Optional.ofNullable(runs.get(inv.getArgument(0))));
        when(runRepo.saveAndFlush(any())).thenAnswer(inv -> {
            ZzalNightRun r = inv.getArgument(0);
            if (runs.putIfAbsent(r.getNightOf(), r) != null) {
                throw new DataIntegrityViolationException("duplicate key night_of");
            }
            return r;
        });
        // claim — 조건부 UPDATE 를 흉내 낸다(원자적)
        when(motionRepo.claim(anyLong(), any(), anyString())).thenAnswer(inv -> {
            ZzalMotion m = motions.get(inv.<Long>getArgument(0));
            synchronized (m) {
                if (m.getStatus() != MotionStatus.QUEUED) {
                    return 0;
                }
                ReflectionTestUtils.setField(m, "status", MotionStatus.BAKING);
                return 1;
            }
        });
        when(planner.queued()).thenAnswer(inv -> motions.values().stream().filter(m -> m.getStatus() == MotionStatus.QUEUED).toList());
        when(petRepo.findByPhase(PetPhase.ALIVE)).thenReturn(List.of());
        when(petRepo.findAllById(any())).thenReturn(List.of());
        org.mockito.Mockito.doAnswer(inv -> {
            synchronized (baked) {
                baked.add(inv.getArgument(0));
            }
            return null;
        }).when(motionService).bakeNow(anyLong());
    }

    private NightSweep sweep(boolean enabled, int cap) {
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv -> inv.<org.springframework.transaction.support.TransactionCallback<Object>>getArgument(0)
                .doInTransaction(new SimpleTransactionStatus()));
        return new NightSweep(petRepo, motionRepo, runRepo, planner, motionService, executor, tx, enabled, cap);
    }

    private ZzalMotion queued(long id, int seq, long petId) {
        ZzalMotion m = ZzalMotion.forCatalog(petId, catalog.bySeq(seq).orElseThrow(), T23);
        m.queue(NIGHT);
        ReflectionTestUtils.setField(m, "id", id);
        motions.put(id, m);
        return m;
    }

    @Test
    @DisplayName("★ 스위프 비활성이면 아무것도 하지 않는다 — run 도 안 남고 굽지도 않는다")
    void disabledDoesNothing() {
        queued(1L, 101, 7L);
        NightSweep s = sweep(false, 200);
        s.sweep();
        s.recoverOnBoot();
        assertThat(runs).isEmpty();
        assertThat(baked).isEmpty();
        verify(motionRepo, never()).claim(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("★ K 초과는 이월 — 5건 중 3건만 굽고 2건은 QUEUED 그대로, run 에 carried=2")
    void capCarriesOver() {
        for (long i = 1; i <= 5; i++) {
            queued(i, 1, i);
        }
        NightSweep.Result r = sweep(true, 3).run(NIGHT, T23, "test");
        assertThat(r.claimed()).isEqualTo(3);
        assertThat(r.carried()).isEqualTo(2);
        assertThat(baked).hasSize(3);
        assertThat(motions.values().stream().filter(m -> m.getStatus() == MotionStatus.QUEUED)).hasSize(2);
        assertThat(runs.get(NIGHT).isFinished()).isTrue();
        assertThat(runs.get(NIGHT).getCarried()).isEqualTo(2);
    }

    @Test
    @DisplayName("★ 우선순위 — 선물(101) > 케어 미스 0인 날 수 > 친밀도 > id")
    void priorityOrder() {
        ZzalPet lowPet = pet(1L, 0, 10);
        ZzalPet streakPet = pet(2L, 3, 0);
        ZzalPet intimatePet = pet(3L, 0, 500);
        when(petRepo.findAllById(any())).thenReturn(List.of(lowPet, streakPet, intimatePet));
        queued(10L, 1, 1L);          // 낮음
        queued(11L, 1, 3L);          // 친밀도
        queued(12L, 101, 1L);        // 선물
        queued(13L, 1, 2L);          // streak
        sweep(true, 200).run(NIGHT, T23, "test");
        assertThat(baked).containsExactly(12L, 13L, 11L, 10L);
    }

    @Test
    @DisplayName("★ 같은 밤을 두 번 돌리면 두 번째는 건너뛴다(run PK) — 재기동 복구가 다시 굽지 않는다")
    void secondRunOfSameNightIsSkipped() {
        queued(1L, 101, 7L);
        NightSweep s = sweep(true, 200);
        assertThat(s.run(NIGHT, T23, "scheduled").ran()).isTrue();
        assertThat(baked).hasSize(1);
        NightSweep.Result again = s.run(NIGHT, T23.plusSeconds(600), "boot-recovery");
        assertThat(again.ran()).isFalse();
        assertThat(baked).hasSize(1);
        verify(planner, times(0)).plan(any(), any());   // 펫이 없어 계획 호출 0 — 두 번째는 계획 자체를 안 한다
    }

    @Test
    @DisplayName("★ 스위프 도중 재기동 — 끝나지 않은 run 이면 계획은 다시 안 하고 남은 QUEUED 만 잇는다(BAKING 은 안 건드림)")
    void resumesUnfinishedRun() {
        runs.put(NIGHT, ZzalNightRun.start(NIGHT, T23, "died"));    // 죽은 서버가 남긴 흔적
        ZzalMotion alreadyBaking = queued(1L, 1, 7L);
        ReflectionTestUtils.setField(alreadyBaking, "status", MotionStatus.BAKING);
        queued(2L, 1, 8L);
        ZzalPet alive = pet(9L, 0, 0);
        when(petRepo.findByPhase(PetPhase.ALIVE)).thenReturn(List.of(alive));

        NightSweep.Result r = sweep(true, 200).run(NIGHT, T23.plusSeconds(120), "boot-recovery");
        assertThat(r.ran()).isTrue();
        assertThat(r.queued()).isZero();                 // 계획 안 함
        verify(planner, never()).plan(any(), any());
        assertThat(baked).containsExactly(2L);           // BAKING(1) 은 안 잡고 QUEUED(2) 만
        assertThat(runs.get(NIGHT).isFinished()).isTrue();
    }

    @Test
    @DisplayName("★ claim 경쟁 — 두 스레드가 같은 큐를 집어도 각 건은 한 번만 굽는다")
    void claimRaceBakesOnce() throws Exception {
        for (long i = 1; i <= 20; i++) {
            queued(i, 1, i);
        }
        NightSweep a = sweep(true, 200);
        NightSweep b = sweep(true, 200);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger winsA = new AtomicInteger();
        AtomicInteger winsB = new AtomicInteger();
        Thread ta = new Thread(() -> {
            await(go);
            winsA.set(a.claimAndBake(T23, 200)[0]);
        });
        Thread tb = new Thread(() -> {
            await(go);
            winsB.set(b.claimAndBake(T23, 200)[0]);
        });
        ta.start();
        tb.start();
        go.countDown();
        ta.join();
        tb.join();
        assertThat(winsA.get() + winsB.get()).isEqualTo(20);
        assertThat(baked).hasSize(20);
        assertThat(baked.stream().distinct()).hasSize(20);
    }

    @Test
    @DisplayName("밤 날짜 — 23:00 은 오늘, 새벽 03:00 은 어제, 낮 12:00 은 창 밖")
    void nightWindow() {
        assertThat(NightSweep.nightOf(kst("2026-09-05 23:00"))).isEqualTo(NIGHT);
        assertThat(NightSweep.nightWindowOf(kst("2026-09-06 03:00"))).isEqualTo(NIGHT);
        assertThat(NightSweep.nightWindowOf(kst("2026-09-06 09:59"))).isEqualTo(NIGHT);
        assertThat(NightSweep.nightWindowOf(kst("2026-09-06 10:00"))).isNull();
        assertThat(NightSweep.nightWindowOf(kst("2026-09-06 12:00"))).isNull();
    }

    private static ZzalPet pet(long id, int zeroMissDays, int intimacy) {
        ZzalPet p = ZzalPet.hatch(1L, "p" + id, null, "k", T23);
        p.markAlive("s", "i", T23);
        ReflectionTestUtils.setField(p, "id", id);
        ReflectionTestUtils.setField(p, "zeroMissDays", zeroMissDays);
        ReflectionTestUtils.setField(p, "intimacy", intimacy);
        return p;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
