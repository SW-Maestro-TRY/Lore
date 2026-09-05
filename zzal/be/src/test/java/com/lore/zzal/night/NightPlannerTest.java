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

    /** 3층이 열리고 조각을 이틀 연속 모은 상태로 만든다(잠든 뒤 스냅샷까지). */
    private void tierThreeReady(int streak) {
        pet.enablePieces(T0);
        ReflectionTestUtils.setField(pet, "pieceStreak", streak);
        ReflectionTestUtils.setField(pet, "lastNightPieceStreak", streak);
        ReflectionTestUtils.setField(pet, "lastNightOf", NIGHT);
    }

    @Test
    @DisplayName("★★ 조각 4개 이틀 연속 → 다음 심화 하나가 13장 번호 순으로 오른다")
    void tierThreeQueuesNextInOrder() {
        tierThreeReady(2);
        when(catalog.isBakeable(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);

        int queued = planner.plan(pet, NIGHT);

        assertThat(queued).isEqualTo(1);
        assertThat(row(1).getStatus()).as("13장 1번(기본 자세)부터").isEqualTo(MotionStatus.QUEUED);
        assertThat(row(2).getStatus()).isEqualTo(MotionStatus.NONE);
        assertThat(pet.getPieceStreak()).as("연속은 소모된다").isZero();
    }

    @Test
    @DisplayName("★ 이미 구운 것은 건너뛰고 다음 번호로 — 선물(101·102)은 이 순서 밖")
    void tierThreeSkipsDone() {
        tierThreeReady(2);
        when(catalog.isBakeable(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        ReflectionTestUtils.setField(row(1), "status", MotionStatus.OPEN);
        ReflectionTestUtils.setField(row(2), "status", MotionStatus.REVIEW);

        planner.plan(pet, NIGHT);

        assertThat(row(3).getStatus()).isEqualTo(MotionStatus.QUEUED);
        assertThat(row(101).getStatus()).as("선물은 순서 밖").isNotEqualTo(MotionStatus.QUEUED);
    }

    @Test
    @DisplayName("★★ 연속이 1이면 안 오른다 — 이틀 연속이어야 한다")
    void tierThreeNeedsTwo() {
        tierThreeReady(1);
        when(catalog.isBakeable(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);

        assertThat(planner.plan(pet, NIGHT)).isZero();
        assertThat(row(1).getStatus()).isEqualTo(MotionStatus.NONE);
    }

    @Test
    @DisplayName("★★ 실패한 밤은 조각을 소모하지 않는다 — FAILED 행이 다음 밤에 다시 오른다")
    void failedNightIsRetriedWithoutPieces() {
        tierThreeReady(2);
        when(catalog.isBakeable(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        planner.plan(pet, NIGHT);
        assertThat(row(1).getStatus()).isEqualTo(MotionStatus.QUEUED);

        // 그 밤에 실패했다 — 조각을 다시 모으지 않아도 다음 밤에 같은 동작이 오른다(정본 16장)
        row(1).markFailed();
        ReflectionTestUtils.setField(pet, "lastNightOf", NIGHT.plusDays(1));
        int queued = planner.plan(pet, NIGHT.plusDays(1));

        assertThat(queued).isEqualTo(1);
        assertThat(row(1).getStatus()).isEqualTo(MotionStatus.QUEUED);
        assertThat(row(1).getNightOf()).isEqualTo(NIGHT.plusDays(1));
        assertThat(row(2).getStatus()).as("연속은 이미 소모됐으니 새 동작은 안 오른다").isEqualTo(MotionStatus.NONE);
    }

    @Test
    @DisplayName("★★ 여행 중에는 아무것도 안 굽는다 — 재등록도 멈춘다(돈이 나가는 경로)")
    void nothingWhileTraveling() {
        when(catalog.isBakeable(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        ReflectionTestUtils.setField(row(101), "status", MotionStatus.FAILED);
        ReflectionTestUtils.setField(pet, "tripStartedAt", T0);

        assertThat(planner.plan(pet, NIGHT)).isZero();
        assertThat(row(101).getStatus()).as("실패한 행이 매일 밤 다시 구워지면 안 된다")
                .isEqualTo(MotionStatus.FAILED);
    }

    @Test
    @DisplayName("★ 3층이 안 열린 펫에게는 두 번째 선물도 안 오른다(같은 가드 안)")
    void secondGiftNeedsTierThree() {
        when(catalog.isBakeable(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        ReflectionTestUtils.setField(pet, "lastNightOf", NIGHT);
        for (int seq = 1; seq <= 8; seq++) {
            ReflectionTestUtils.setField(row(seq), "status", MotionStatus.OPEN);
        }
        assertThat(pet.isPiecesEnabled()).isFalse();

        assertThat(planner.plan(pet, NIGHT)).isZero();
        assertThat(row(102).getStatus()).isEqualTo(MotionStatus.NONE);
    }

    @Test
    @DisplayName("★ 두 번째 선물은 3층 8종이 열린 뒤에 오른다")
    void secondGiftAfterEightAdvanced() {
        tierThreeReady(0);
        when(catalog.isBakeable(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        for (int seq = 1; seq <= 7; seq++) {
            ReflectionTestUtils.setField(row(seq), "status", MotionStatus.OPEN);
        }
        assertThat(planner.plan(pet, NIGHT)).as("7종으로는 아직").isZero();

        ReflectionTestUtils.setField(row(8), "status", MotionStatus.OPEN);
        planner.plan(pet, NIGHT);
        assertThat(row(102).getStatus()).isEqualTo(MotionStatus.QUEUED);
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
