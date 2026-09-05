package com.lore.webtoon;

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
 * 오늘 몫이 찼는지 보는 자리.
 *
 * 이 검사가 지키는 것은 <b>숫자가 아니라 부등호</b>다. 상한과 같아졌을 때
 * 막느냐 안 막느냐에서 한 편이 갈리고, 그 한 편이 1,148원이다.
 */
class SpendGuardTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-09-06T05:00:00Z"), ZONE);   // 한국시간 14시

    private SpendGuard guard(long runs, long krw, long limitRuns, long limitKrw) {
        UsageRepository usage = mock(UsageRepository.class);
        when(usage.runsBetween(any(), any())).thenReturn(runs);
        when(usage.krwBetween(any(), any())).thenReturn(krw);
        return new SpendGuard(usage, limitRuns, limitKrw, FIXED);
    }

    @Test
    @DisplayName("몫이 남았으면 안 막는다")
    void 몫이_남았으면_통과() {
        assertThat(guard(5, 6_000, 30, 40_000).whyBlocked()).isNull();
    }

    @Test
    @DisplayName("편수가 상한과 같아지는 순간 막는다 — 상한이 30이면 30편째가 아니라 31편째를 막으면 늦다")
    void 편수_상한() {
        assertThat(guard(29, 0, 30, 40_000).whyBlocked()).isNull();
        assertThat(guard(30, 0, 30, 40_000).whyBlocked()).isNotNull();
    }

    @Test
    @DisplayName("금액이 상한과 같아지는 순간 막는다")
    void 금액_상한() {
        assertThat(guard(0, 39_999, 30, 40_000).whyBlocked()).isNull();
        assertThat(guard(0, 40_000, 30, 40_000).whyBlocked()).isNotNull();
    }

    @Test
    @DisplayName("둘 중 하나만 차도 막는다 — 편수는 적은데 비싼 편이 몰릴 수 있다")
    void 둘_중_하나만_차도_막는다() {
        assertThat(guard(3, 40_000, 30, 40_000).whyBlocked()).isNotNull();
    }

    @Test
    @DisplayName("상한이 0이면 안 센다 — 끄는 스위치다")
    void 상한_0은_끄는_스위치() {
        assertThat(guard(9_999, 9_999_999, 0, 0).whyBlocked()).isNull();
    }

    @Test
    @DisplayName("막는 말에 상한 숫자를 안 적는다 — 얼마까지 되는지 알려 주면 그 앞까지 긁어 쓴다")
    void 막는_말은_숫자를_안_흘린다() {
        String said = guard(30, 0, 30, 40_000).whyBlocked();
        assertThat(said).isNotNull();
        assertThat(said).doesNotContain("30").doesNotContain("40000").doesNotContain("40,000");
    }

    @Test
    @DisplayName("오늘 값은 쓴 것과 상한을 함께 준다 — 화면이 「몇 편 중 몇 편」을 그린다")
    void 오늘_값() {
        SpendGuard.Today today = guard(7, 8_000, 30, 40_000).today();
        assertThat(today.runs()).isEqualTo(7);
        assertThat(today.runLimit()).isEqualTo(30);
        assertThat(today.krw()).isEqualTo(8_000);
        assertThat(today.krwLimit()).isEqualTo(40_000);
    }
}
