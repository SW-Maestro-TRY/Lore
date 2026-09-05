package com.lore.zzal.night;

import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.MotionStatus;
import com.lore.zzal.motion.ZzalMotion;
import com.lore.zzal.motion.ZzalMotionRepository;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static com.lore.zzal.pet.AwakeClockTest.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 잠드는 순간의 계획 — 첫 심화(3일째·케어 미스 0)·실패 재등록·지시문 없으면 안 굽음·두 번 불러도 안전. */
@DisplayName("밤 계획 — 첫 심화·재등록")
class NightPlannerTest {

    /** 18:00 부화 — 아기 60분이 19:00 에 끝나 케어 미스 없이 바로 재울 수 있다(밤잠 조건 검증에 집중). */
    private static final Instant T0 = kst("2026-09-05 18:00");
    private static final LocalDate NIGHT = LocalDate.of(2026, 9, 5);

    private final MotionCatalog realCatalog = new MotionCatalog("", "", "v1");
    private MotionCatalog catalog;
    private ZzalMotionRepository repo;
    private List<ZzalMotion> rows;
    private ZzalPet pet;
    private NightPlanner planner;

    @BeforeEach
    void setUp() {
        pet = ZzalPet.hatch(1L, "여울", null, "k", T0);
        pet.markAlive("s", "i", T0);
        ReflectionTestUtils.setField(pet, "id", 7L);
        rows = realCatalog.all().stream().map(s -> ZzalMotion.forCatalog(7L, s, T0)).toList();
        repo = mock(ZzalMotionRepository.class);
        when(repo.findByPetIdOrderBySeqAsc(anyLong())).thenReturn(rows);
        catalog = mock(MotionCatalog.class);
        when(catalog.gifts()).thenReturn(realCatalog.gifts());
        when(catalog.isBakeable("roll")).thenReturn(true);
        planner = new NightPlanner(repo, catalog);
    }

    private ZzalMotion row(int seq) {
        return rows.stream().filter(m -> m.getSeq() == seq).findFirst().orElseThrow();
    }

    /** 3일째가 되도록 방문일을 밀고, 오늘 케어 미스 0 인 채 19:00 에 재운다. */
    private void thirdDayNightSleep(int careMissToday) {
        ReflectionTestUtils.setField(pet, "daysTogether", 3);
        ReflectionTestUtils.setField(pet, "todayCareMiss", careMissToday);
        pet.settle(kst("2026-09-05 19:00"));
        pet.sleep(kst("2026-09-05 19:00"));
    }

    @Test
    @DisplayName("★ 함께한 날 3 + 그날 케어 미스 0 → 구르기(101) QUEUED. 조건 미달이면 NONE 그대로")
    void firstGiftWhenThreeDaysAndZeroMiss() {
        thirdDayNightSleep(0);
        assertThat(planner.plan(pet, NIGHT)).isEqualTo(1);
        assertThat(row(101).getStatus()).isEqualTo(MotionStatus.QUEUED);
        assertThat(row(101).getNightOf()).isEqualTo(NIGHT);
        assertThat(row(1).getStatus()).isEqualTo(MotionStatus.NONE);      // 3층은 PR-10
    }

    @Test
    @DisplayName("그날 케어 미스가 1이면 안 오른다 — 다음 밤에 같은 판정(놓쳐도 사라지지 않음)")
    void notWhenCareMissed() {
        thirdDayNightSleep(1);
        assertThat(pet.getLastNightCareMiss()).isEqualTo(1);
        assertThat(planner.plan(pet, NIGHT)).isZero();
        assertThat(row(101).getStatus()).isEqualTo(MotionStatus.NONE);
    }

    @Test
    @DisplayName("2일째는 안 오른다")
    void notBeforeThirdDay() {
        ReflectionTestUtils.setField(pet, "daysTogether", 2);
        pet.settle(kst("2026-09-05 19:00"));
        pet.sleep(kst("2026-09-05 19:00"));
        assertThat(planner.plan(pet, NIGHT)).isZero();
    }

    @Test
    @DisplayName("★ 지시문이 없으면(gift-motions 미등록) 조건이 차도 안 오른다 — 로그만")
    void notWhenNoPrompt() {
        when(catalog.isBakeable("roll")).thenReturn(false);
        thirdDayNightSleep(0);
        assertThat(planner.plan(pet, NIGHT)).isZero();
        assertThat(row(101).getStatus()).isEqualTo(MotionStatus.NONE);
    }

    @Test
    @DisplayName("두 번 불러도(재우기 + 스위프) 한 번만 오른다")
    void idempotent() {
        thirdDayNightSleep(0);
        assertThat(planner.plan(pet, NIGHT)).isEqualTo(1);
        assertThat(planner.plan(pet, NIGHT)).isZero();
        assertThat(row(101).getStatus()).isEqualTo(MotionStatus.QUEUED);
    }

    @Test
    @DisplayName("지난 밤 실패(FAILED)는 다음 밤에 다시 오른다 — 조각을 소모하지 않는다")
    void failedRequeued() {
        row(1).queue(NIGHT.minusDays(1));
        row(1).failNight();
        when(catalog.isBakeable("base")).thenReturn(true);
        pet.settle(kst("2026-09-05 19:00"));
        pet.sleep(kst("2026-09-05 19:00"));
        assertThat(planner.plan(pet, NIGHT)).isEqualTo(1);
        assertThat(row(1).getStatus()).isEqualTo(MotionStatus.QUEUED);
        assertThat(row(1).getNightOf()).isEqualTo(NIGHT);
    }

    @Test
    @DisplayName("v1 펫(18행 없음)은 대상이 아니다")
    void v1PetSkipped() {
        when(repo.findByPetIdOrderBySeqAsc(anyLong())).thenReturn(List.of());
        thirdDayNightSleep(0);
        assertThat(planner.plan(pet, NIGHT)).isZero();
    }
}
