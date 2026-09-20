package com.lore.webtoon.usage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 하루 지출 상한이 <b>아직 안 적힌 몫까지</b> 세는가.
 *
 * <b>나란히 둘을 돌리기 시작하면서 생긴 구멍이다.</b> 지출은 걸음이 끝나야
 * DB 에 적히는데, 상한까지 한 편 남았을 때 둘이 같이 물으면 둘 다 아직
 * 아무것도 안 써서 <b>둘 다 통과한다.</b> 그러면 넘겨서 시작하고, 우리는 다
 * 쓴 뒤에야 안다.
 */
class SpendReservationTest {

    private UsageRepository usage;
    private SpendGuard guard;

    @BeforeEach
    void setUp() {
        usage = mock(UsageRepository.class);
        // 하루 30편 · 40,000원. 기본값과 같다.
        guard = new SpendGuard(usage, 30, 40_000,
                Clock.fixed(Instant.parse("2026-09-13T05:00:00Z"), ZoneId.of("Asia/Seoul")));
    }

    private void 적힌것(long runs, long krw) {
        when(usage.runsBetween(any(), any())).thenReturn(runs);
        when(usage.krwBetween(any(), any())).thenReturn(krw);
    }

    @Test
    @DisplayName("적힌 것만으로 여유가 있으면 통과한다")
    void 여유가_있으면_통과() {
        적힌것(10, 10_000);
        assertThat(guard.whyBlocked(SpendGuard.Reserved.NONE)).isNull();
    }

    @Test
    @DisplayName("적힌 것은 모자란데 예약을 더하면 넘는다 — 여기서 막아야 한다")
    void 예약을_더해_막는다() {
        /* 적힌 지출은 39,000원이라 아직 여유가 있어 보인다. 그런데 지금 도는
           두 편이 2,222원을 쓸 참이다 — 그대로 두면 41,222원이 된다. */
        적힌것(10, 39_000);
        assertThat(guard.whyBlocked(SpendGuard.Reserved.NONE)).isNull();
        assertThat(guard.whyBlocked(new SpendGuard.Reserved(2, 2_222)))
                .as("예약을 더하면 상한을 넘으므로 막아야 한다")
                .isNotNull();
    }

    @Test
    @DisplayName("편수 상한도 예약을 센다")
    void 편수도_예약을_센다() {
        적힌것(29, 1_000);
        assertThat(guard.whyBlocked(SpendGuard.Reserved.NONE)).isNull();
        // 이미 하나가 돌고 있으면 29 + 1 = 30 이라 다 찼다.
        assertThat(guard.whyBlocked(new SpendGuard.Reserved(1, 1_111))).isNotNull();
    }

    @Test
    @DisplayName("예약 없이 부르던 옛 자리도 그대로 돈다")
    void 인자_없는_것도_돈다() {
        적힌것(40, 50_000);
        assertThat(guard.whyBlocked()).isNotNull();
        적힌것(1, 100);
        assertThat(guard.whyBlocked()).isNull();
    }
}
