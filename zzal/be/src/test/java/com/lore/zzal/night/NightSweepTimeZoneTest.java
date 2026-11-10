package com.lore.zzal.night;

import com.lore.zzal.PetFixture;
import com.lore.zzal.motion.MotionService;
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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 시간대 2단계 밤 스위프 — 15분 눈금이 시간대마다의 23:00 에 그 시간대 펫만, 펫마다 한 밤에 한 번 돈다.
 */
@DisplayName("밤 스위프 — 시간대마다·펫마다 한 번")
class NightSweepTimeZoneTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final ZoneId LA = ZoneId.of("America/Los_Angeles");
    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

    private final Map<ZzalNightRun.Key, ZzalNightRun> runs = new ConcurrentHashMap<>();
    private final Map<Long, ZzalPet> pets = new ConcurrentHashMap<>();
    /** 계획한 (펫, 밤) 기록. */
    private final List<String> planned = new ArrayList<>();
    private ZzalPetRepository petRepo;
    private ZzalNightRunRepository runRepo;
    private NightPlanner planner;
    private NightSweep sweep;

    private static Instant at(String iso) {
        return OffsetDateTime.parse(iso).toInstant();
    }

    @BeforeEach
    void setUp() {
        petRepo = mock(ZzalPetRepository.class);
        ZzalMotionRepository motionRepo = mock(ZzalMotionRepository.class);
        runRepo = mock(ZzalNightRunRepository.class);
        planner = mock(NightPlanner.class);
        when(runRepo.findById(any())).thenAnswer(inv -> Optional.ofNullable(runs.get(inv.<ZzalNightRun.Key>getArgument(0))));
        when(runRepo.saveAndFlush(any())).thenAnswer(inv -> {
            ZzalNightRun r = inv.getArgument(0);
            if (runs.putIfAbsent(r.key(), r) != null) throw new DataIntegrityViolationException("dup");
            return r;
        });
        when(petRepo.findByPhase(PetPhase.ALIVE)).thenAnswer(inv -> List.copyOf(pets.values()));
        when(petRepo.findByIdForUpdate(anyLong())).thenAnswer(inv -> Optional.ofNullable(pets.get(inv.<Long>getArgument(0))));
        when(petRepo.findAllById(any())).thenReturn(List.of());
        when(planner.queued()).thenReturn(List.of());
        when(planner.plan(any(), any(), eq(NightPlanner.Occasion.NIGHT))).thenAnswer(inv -> {
            ZzalPet p = inv.getArgument(0);
            synchronized (planned) {
                planned.add(p.getId() + "@" + inv.getArgument(1));
            }
            return 0;
        });
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv -> inv.<org.springframework.transaction.support.TransactionCallback<Object>>getArgument(0)
                .doInTransaction(new SimpleTransactionStatus()));
        sweep = new NightSweep(petRepo, motionRepo, runRepo, planner, mock(MotionService.class), Runnable::run, tx, true, 200);
    }

    private ZzalPet pet(long id, ZoneId zone, Instant hatch) {
        ZzalPet p = PetFixture.hatching(1L, "p" + id, null, "k", hatch);
        p.markAlive("s", "i", hatch);
        p.skipTutorial(hatch);
        ReflectionTestUtils.setField(p, "id", id);
        if (zone != null) p.observeTimeZone(zone);
        pets.put(id, p);
        return p;
    }

    /** from 부터 to 까지 15분 눈금마다 스위프 한 번. */
    private void tickEvery15(Instant from, Instant to) {
        for (Instant t = from; !t.isAfter(to); t = t.plus(Duration.ofMinutes(15))) {
            sweep.tick(t, "scheduled");
        }
    }

    @Test
    @DisplayName("★★ 한국 펫(시간대 NULL·서울) — KST 23:00 눈금에 한 번만, 그 뒤 눈금들은 아무것도 안 한다")
    void seoulPetsOncePerNightAtElevenKst() {
        pet(1L, null, at("2026-10-20T12:00:00+09:00"));
        pet(2L, SEOUL, at("2026-10-20T12:00:00+09:00"));
        tickEvery15(at("2026-10-26T22:00:00+09:00"), at("2026-10-27T11:00:00+09:00"));
        assertThat(planned).containsExactlyInAnyOrder("1@2026-10-26", "2@2026-10-26");
        ZzalNightRun run = runs.get(new ZzalNightRun.Key(LocalDate.of(2026, 10, 26), "Asia/Seoul"));
        assertThat(run).isNotNull();
        assertThat(run.getStartedAt()).as("23:00 정각 눈금").isEqualTo(at("2026-10-26T23:00:00+09:00"));
    }

    @Test
    @DisplayName("★★ LA 2026-11-01(서머타임 끝) — 현지 23:00 PST 에 그 밤 한 번. 서울 펫은 서울 23시에 따로")
    void losAngelesDstNight() {
        pet(1L, null, at("2026-10-20T12:00:00+09:00"));
        pet(3L, LA, at("2026-10-20T12:00:00-07:00"));
        // 11/1 LA 10:00(밤 창이 막 닫힌 때)부터 11/2 LA 낮까지 15분마다 — 사이에 서울 11/2 23:00 도 지나간다.
        // (LA 06:00 부터 돌면 한 번도 안 돈 펫이라 10/31 밤을 따라잡는다 — 기동 복구와 같은 뜻이다.)
        tickEvery15(at("2026-11-01T10:00:00-08:00"), at("2026-11-02T12:00:00-08:00"));
        assertThat(planned).filteredOn(s -> s.startsWith("3@")).containsExactly("3@2026-11-01");
        ZzalNightRun la = runs.get(new ZzalNightRun.Key(LocalDate.of(2026, 11, 1), "America/Los_Angeles"));
        assertThat(la.getStartedAt()).as("PST 23:00 = UTC 07:00").isEqualTo(at("2026-11-01T23:00:00-08:00"));
        // 서울 펫: 첫 눈금(LA 11/1 10:00 = KST 11/2 03:00)은 서울 11/1 밤 창 안 → 그 밤을 따라잡고, KST 11/2 23:00 에 다음 밤.
        assertThat(planned).filteredOn(s -> s.startsWith("1@")).containsExactly("1@2026-11-01", "1@2026-11-02");
    }

    @Test
    @DisplayName("★★ 콜카타(+5:30) — 22:45 눈금에는 안 돌고 23:00 눈금에 돌고, 23:15 에는 다시 안 돈다")
    void kolkataHalfHourZone() {
        pet(4L, KOLKATA, at("2026-10-20T12:00:00+05:30"));
        sweep.tick(at("2026-10-26T22:45:00+05:30"), "scheduled");
        assertThat(planned).filteredOn(s -> s.startsWith("4@")).isEmpty();
        sweep.tick(at("2026-10-26T23:00:00+05:30"), "scheduled");
        assertThat(planned).filteredOn(s -> s.startsWith("4@")).containsExactly("4@2026-10-26");
        sweep.tick(at("2026-10-26T23:15:00+05:30"), "scheduled");
        sweep.tick(at("2026-10-27T02:00:00+05:30"), "boot-recovery");   // 기동 복구도 다시 계획하지 않는다
        assertThat(planned).filteredOn(s -> s.startsWith("4@")).containsExactly("4@2026-10-26");
        assertThat(NightSweep.nightWindowOf(at("2026-10-27T09:59:00+05:30"), KOLKATA)).isEqualTo(LocalDate.of(2026, 10, 26));
        assertThat(NightSweep.nightWindowOf(at("2026-10-27T10:00:00+05:30"), KOLKATA)).isNull();
    }

    @Test
    @DisplayName("★★★ 전환 — 10/24 KST 로 부화·10/25 KST 밤까지 돈 펫이 10/26 첫 접속에 LA 가 되면 같은 날짜의 현지 밤은 건너뛰고, 그 뒤로는 LA 창")
    void firstContactTransitionDoesNotRunASecondNight() {
        ZzalPet p = pet(5L, null, at("2026-10-24T09:00:00+09:00"));
        // KST 밤 둘을 지금까지처럼 돈다.
        sweep.tick(at("2026-10-24T23:00:00+09:00"), "scheduled");
        sweep.tick(at("2026-10-25T23:00:00+09:00"), "scheduled");
        assertThat(planned).containsExactly("5@2026-10-24", "5@2026-10-25");
        assertThat(p.getLastSweptNightOf()).isEqualTo(LocalDate.of(2026, 10, 25));

        // 10/26 KST 정오(= LA 10/25 20:00) 첫 접속에 시간대가 LA 로 정해진다.
        Instant firstContact = at("2026-10-26T12:00:00+09:00");
        p.settle(firstContact);
        assertThat(p.observeTimeZone(LA)).isTrue();

        // LA 10/25 23:00(= KST 10/26 15:00) — 날짜 10/25 의 밤은 이미 돌았다 → 건너뛴다.
        tickEvery15(at("2026-10-25T22:30:00-07:00"), at("2026-10-26T09:45:00-07:00"));
        assertThat(planned).as("같은 날짜(10/25)의 밤을 두 번 돌지 않는다").containsExactly("5@2026-10-24", "5@2026-10-25");

        // 그 뒤로는 LA 의 밤 — LA 10/26 23:00 에 돈다(KST 10/26 23:00 이 아니다).
        tickEvery15(at("2026-10-26T10:00:00-07:00"), at("2026-10-27T09:45:00-07:00"));
        assertThat(planned).containsExactly("5@2026-10-24", "5@2026-10-25", "5@2026-10-26");
        assertThat(runs.get(new ZzalNightRun.Key(LocalDate.of(2026, 10, 26), "America/Los_Angeles")).getStartedAt())
                .isEqualTo(at("2026-10-26T23:00:00-07:00"));

        // 창도 LA 벽시계 — LA 19:30 에 재울 수 있고, 서울 19:30(= LA 03:30) 에는 자고 있다.
        Instant laEvening = at("2026-10-27T19:30:00-07:00");
        p.settle(laEvening);
        assertThat(p.canSleep(laEvening)).isTrue();
        Instant seoulEvening = at("2026-10-28T19:30:00+09:00");
        p.settle(seoulEvening);
        assertThat(p.isSleeping()).isTrue();
    }

    @Test
    @DisplayName("★ 하루 상한 K 는 KST 하루에 시작한 판들의 합 — 시간대가 늘어도 하루 비용이 불지 않는다")
    void budgetIsSharedAcrossZonesPerKstDay() {
        when(runRepo.sumClaimedStartedBetween(any(), any())).thenReturn(150L);
        assertThat(sweep.budget(at("2026-10-26T23:00:00+09:00"))).isEqualTo(50);
        when(runRepo.sumClaimedStartedBetween(any(), any())).thenReturn(250L);
        assertThat(sweep.budget(at("2026-10-26T23:00:00+09:00"))).isZero();
    }
}
