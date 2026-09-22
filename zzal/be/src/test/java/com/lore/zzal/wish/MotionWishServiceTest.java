package com.lore.zzal.wish;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.PetFixture;
import com.lore.zzal.pet.PetService;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalRules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.lore.zzal.pet.AwakeClockTest.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 동작 희망 — 하루 20줄의 <b>"하루"</b>가 어디서 끊기나(2026-09-22 결정).
 *
 * <h3>★★ 왜 이 시험이 필요한가</h3>
 * 경계가 틀려도 아무것도 안 터진다. 자정 기준이던 때는 늦은 밤에 쓰는 사람에게만,
 * 그것도 <b>상한이 한 번 더 풀리는</b> 방향으로 조용히 어긋났다 — 로그에도 안 남는다.
 */
@DisplayName("동작 희망 — 하루 상한의 경계는 취침 기준")
class MotionWishServiceTest {

    private static final Long USER = 1L;
    private static final Long PET = 7L;

    /** 저녁 8시에 하루를 시작한 펫 — 자정이 <b>깨어 있는 사이</b>에 낀다. */
    private static final Instant WOKE = kst("2026-09-05 20:00");

    private final List<ZzalMotionWish> store = new ArrayList<>();
    private ZzalMotionWishRepository repo;
    private ZzalPet pet;
    private PetService pets;
    private MotionWishService service;

    @BeforeEach
    void setUp() {
        repo = mock(ZzalMotionWishRepository.class);
        when(repo.save(any())).thenAnswer(inv -> {
            store.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        // ★ 살아 있는 저장소 흉내 — 경계 시각으로 실제로 걸러야 시험이 경계를 본다.
        //   (모든 줄을 그냥 세면 자정을 넘겨도·기상을 넘겨도 똑같이 빨강/초록이 된다)
        when(repo.countByPetIdAndCreatedAtGreaterThanEqual(anyLong(), any())).thenAnswer(inv -> {
            Instant from = inv.getArgument(1);
            return store.stream().filter(w -> !w.getCreatedAt().isBefore(from)).count();
        });

        pet = PetFixture.hatching(USER, "여울", null, "k", WOKE);
        pet.markAlive("s", "i", WOKE);
        pet.skipTutorial(WOKE);                         // 시계를 켠다 — wokeAt = WOKE
        ReflectionTestUtils.setField(pet, "id", PET);

        pets = mock(PetService.class);
        when(pets.get(any(), any())).thenReturn(pet);
        service = new MotionWishService(repo, pets);
    }

    private void fillTheDay(Instant at) {
        for (int i = 0; i < ZzalRules.MOTION_WISH_DAILY_LIMIT; i++) {
            service.submit(USER, PET, "구르기 보고 싶어요 " + i, at);
        }
    }

    @Test
    @DisplayName("★★★ 자정을 넘겨도 잠들기 전이면 같은 하루다 — 스물한 번째는 자정 뒤에도 거절")
    void midnightIsNotTheBoundary() {
        fillTheDay(kst("2026-09-05 23:50"));
        assertThat(store).hasSize(ZzalRules.MOTION_WISH_DAILY_LIMIT);

        // ★ 자정 기준이던 때는 여기서 상한이 통째로 풀렸다 — 한 번 깨어 있는 사이에 40줄.
        assertThatThrownBy(() -> service.submit(USER, PET, "자정 넘어 한 줄 더", kst("2026-09-06 00:10")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_MOTION_WISH_DAILY_LIMIT);

        assertThat(store).as("거절된 줄은 저장되지 않는다").hasSize(ZzalRules.MOTION_WISH_DAILY_LIMIT);
    }

    @Test
    @DisplayName("★★ 잠들었다 깨면 새 하루다 — 그 기상부터 다시 20줄")
    void wakingOpensANewDay() {
        fillTheDay(kst("2026-09-05 23:50"));

        // 23:00 자동 취침을 지나 10:00 자동 기상 뒤까지 정산한다
        pet.settle(kst("2026-09-06 10:30"));
        assertThat(pet.isSleeping()).isFalse();
        assertThat(pet.dayStartedAt())
                .as("밤잠에서 깬 시각이 새 하루의 시작")
                .isEqualTo(kst("2026-09-06 10:00"));

        ZzalMotionWish fresh = service.submit(USER, PET, "새 아침의 첫 줄", kst("2026-09-06 10:35"));

        assertThat(fresh.getText()).isEqualTo("새 아침의 첫 줄");
        assertThat(store).hasSize(ZzalRules.MOTION_WISH_DAILY_LIMIT + 1);
    }

    @Test
    @DisplayName("★★ dev 시계로 앞당긴 펫에서도 상한이 산다 — 펫 시각을 실제 시각과 섞으면 조용히 사라진다")
    void theLimitSurvivesTheDevClock() {
        // ★ 시계를 <b>켜기 전에</b> 이틀을 민다 — 그래야 기상 시각이 펫 시계(이틀 뒤)로 기록되고,
        //   줄의 created_at(실제 시각)과 단위가 갈리는 그 자리를 실제로 밟는다.
        ZzalPet devPet = PetFixture.hatching(USER, "여울", null, "k", WOKE);
        devPet.markAlive("s", "i", WOKE);
        devPet.advanceDevClock(java.time.Duration.ofDays(2));
        devPet.skipTutorial(devPet.now(WOKE));
        ReflectionTestUtils.setField(devPet, "id", PET);
        assertThat(devPet.dayStartedAt())
                .as("기상 시각이 펫 시계로 이틀 앞에 있다").isEqualTo(WOKE.plus(java.time.Duration.ofDays(2)));
        when(pets.get(any(), any())).thenReturn(devPet);

        fillTheDay(kst("2026-09-05 21:00"));        // 실제 시각으로 스무 줄

        assertThatThrownBy(() -> service.submit(USER, PET, "dev 시계 뒤 한 줄 더", kst("2026-09-05 21:30")))
                .as("경계를 펫 시각 그대로 쓰면 세는 줄이 0 이 되어 상한이 없어진다")
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_MOTION_WISH_DAILY_LIMIT);
    }
}
