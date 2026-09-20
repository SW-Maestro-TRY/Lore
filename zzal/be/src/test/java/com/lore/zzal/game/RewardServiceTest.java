package com.lore.zzal.game;

import com.lore.zzal.PetFixture;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalPetRepository;
import com.lore.zzal.pet.ZzalRules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static com.lore.zzal.pet.AwakeClockTest.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 보상 지급 — <b>한 번도 실행된 적 없는 코드가 켜지는 날 처음 돈다</b>(M-9).
 *
 * <h3>★ 왜 지금 시험하나</h3>
 * {@code app.zzal.reward.*} 가 전부 {@link RewardKind#NONE} 이라 {@link RewardService#grant} 본문이
 * 운영에서도 시험에서도 <b>한 줄도 안 돈다.</b> 설정을 켜는 순간 세 가지가 함께 처음 돌게 되는데,
 * 그때가 되어서야 "행복이 안 오른다 · 밥이 두 번 들어간다" 를 알게 되면 이미 사용자 데이터다.
 * 여기서 <b>진입점 × 보상 종류</b>를 미리 전부 밟아 둔다.
 *
 * <h3>★ 무엇이 진짜인가</h3>
 * 저장소만 목이고 {@link ZzalPet} 은 진짜다 — "얼마나 오르나" 는 엔티티 안의 규칙이고,
 * 그 규칙이 정본과 맞는지가 이 시험이 보려는 것이다.
 */
@DisplayName("보상 지급 — 진입점 셋 × 보상 셋")
class RewardServiceTest {

    private static final Instant T0 = kst("2026-09-05 12:00");
    private static final Long PET = 7L;

    private ZzalPetRepository petRepository;
    private ZzalPet pet;

    @BeforeEach
    void setUp() {
        petRepository = mock(ZzalPetRepository.class);
        pet = PetFixture.hatching(1L, "여울", null, "k", T0);
        pet.markAlive("s", "i", T0);
        pet.skipTutorial(T0);
        ReflectionTestUtils.setField(pet, "id", PET);
        when(petRepository.findById(PET)).thenReturn(Optional.of(pet));
    }

    private RewardService rewards(RewardKind feedback, RewardKind gameWin) {
        return new RewardService(petRepository, feedback, gameWin);
    }

    // ── NONE — 지금의 운영 설정 ────────────────────────────────────────────

    @Test
    @DisplayName("★★ NONE 이면 펫을 읽지도 않는다 — 지금 운영이 이 상태다")
    void noneTouchesNothing() {
        RewardService service = rewards(RewardKind.NONE, RewardKind.NONE);
        int food = pet.getFood();
        int happiness = pet.getHappiness();

        service.forGameWin(PET, T0);
        service.forFeedback(PET, T0);
        service.forGameWin(pet, T0);

        assertThat(pet.getFood()).isEqualTo(food);
        assertThat(pet.getHappiness()).isEqualTo(happiness);
        verify(petRepository, never()).findById(anyLong());
    }

    // ── FOOD ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★ FOOD — 밥 재고가 정확히 1 오른다(게이지가 아니다). 세 진입점이 같은 값을 준다")
    void foodAddsExactlyOneToTheStock() {
        RewardService service = rewards(RewardKind.FOOD, RewardKind.FOOD);
        pet.feed(T0);                                       // 재고 3 → 2 (상한에서 한 칸 비운다)
        int before = pet.getFood();

        service.forGameWin(PET, T0);
        assertThat(pet.getFood()).as("게임 승리(번호로)").isEqualTo(before + 1);

        pet.feed(T0);
        before = pet.getFood();
        service.forFeedback(PET, T0);
        assertThat(pet.getFood()).as("후기").isEqualTo(before + 1);

        pet.feed(T0);
        before = pet.getFood();
        service.forGameWin(pet, T0);
        assertThat(pet.getFood()).as("게임 승리(이미 잠근 펫으로)").isEqualTo(before + 1);
    }

    @Test
    @DisplayName("밥 재고 상한에서는 더 안 오른다 — 넘치는 보상은 조용히 버려진다")
    void foodStopsAtTheCap() {
        RewardService service = rewards(RewardKind.NONE, RewardKind.FOOD);
        assertThat(pet.getFood()).isEqualTo(ZzalRules.FOOD_MAX);

        service.forGameWin(PET, T0);

        assertThat(pet.getFood()).isEqualTo(ZzalRules.FOOD_MAX);
    }

    // ── HAPPINESS ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("★ HAPPINESS — 행복이 정확히 GAME_WIN_HAPPINESS 만큼 오르고 상한을 안 넘는다")
    void happinessAddsTheRuleAmount() {
        RewardService service = rewards(RewardKind.HAPPINESS, RewardKind.HAPPINESS);
        ReflectionTestUtils.setField(pet, "happiness", 0);

        service.forGameWin(PET, T0);
        assertThat(pet.getHappiness()).isEqualTo(ZzalRules.GAME_WIN_HAPPINESS);

        for (int i = 0; i < 10; i++) {
            service.forFeedback(PET, T0);
        }
        assertThat(pet.getHappiness()).isEqualTo(ZzalRules.GAUGE_MAX);
    }

    // ── 없는 펫 · 재호출 ──────────────────────────────────────────────────

    @Test
    @DisplayName("없는 펫 번호면 조용히 아무 일도 없다 — 보상 하나가 게임 응답을 500 으로 만들면 안 된다")
    void missingPetIsSilent() {
        RewardService service = rewards(RewardKind.FOOD, RewardKind.FOOD);
        when(petRepository.findById(anyLong())).thenReturn(Optional.empty());

        assertThatCode(() -> {
            service.forGameWin(999L, T0);
            service.forFeedback(999L, T0);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★★ 같은 인자로 다시 부르면 <b>또 준다</b> — 멱등이 아니다. 중복 방어는 부르는 쪽의 몫")
    void grantingTwiceGivesTwice() {
        RewardService service = rewards(RewardKind.NONE, RewardKind.HAPPINESS);
        ReflectionTestUtils.setField(pet, "happiness", 0);

        service.forGameWin(PET, T0);
        service.forGameWin(PET, T0);

        assertThat(pet.getHappiness()).isEqualTo(ZzalRules.GAME_WIN_HAPPINESS * 2);
    }

    // ── 정산을 누가 거는가 ────────────────────────────────────────────────

    @Test
    @DisplayName("★★ 번호로 부르는 길은 서비스 밖에서 정산을 한 번 더 건다 — 이미 잠근 펫으로 부르는 길은 안 건다")
    void onlyTheByIdPathSettlesAgain() {
        RewardService service = rewards(RewardKind.NONE, RewardKind.HAPPINESS);
        Instant later = T0.plusSeconds(3 * 3600);

        // (가) 이미 잠그고 정산한 펫으로 — 게임 서비스가 트랜잭션 안에서 부르는 길
        Instant settledBefore = pet.getSettledAt();
        service.forGameWin(pet, later);
        assertThat(pet.getSettledAt()).as("여기서는 정산을 다시 걸지 않는다").isEqualTo(settledBefore);

        // (나) 번호로 — 후기·밖에서 부르는 길. grant 안에서 pet.settle 이 한 번 더 걸린다
        service.forGameWin(PET, later);
        assertThat(pet.getSettledAt())
                .as("번호로 부르면 grant 가 정산을 건다(이중 정산이 되는 자리)")
                .isEqualTo(pet.now(later));
    }
}
