package com.lore.webtoon.retention;

import com.lore.common.retention.CommonPurgeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 기간이 지난 것만 지우는가.
 *
 * <b>이 검사가 지키는 것</b> — 처리방침에 적은 기간과 코드가 같은 숫자를 보는지.
 * 둘이 어긋나면 문서에 적은 그것이 그대로 위반 사실이 된다.
 */
class RetentionSweepTest {

    /** 2026-09-23 12:00 KST. 경계를 눈으로 셀 수 있게 정오로 잡는다. */
    private static final Instant NOW = Instant.parse("2026-09-23T03:00:00Z");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private WebtoonPurgeRepository webtoon;
    private CommonPurgeRepository common;

    @BeforeEach
    void setUp() {
        webtoon = mock(WebtoonPurgeRepository.class);
        common = mock(CommonPurgeRepository.class);
    }

    private RetentionSweep sweep(boolean enabled) {
        return new RetentionSweep(webtoon, common, Clock.fixed(NOW, KST), enabled);
    }

    @Test
    @DisplayName("꺼져 있으면 아무 표도 건드리지 않는다")
    void disabledTouchesNothing() {
        RetentionSweep.Result r = sweep(false).runOnce(NOW);

        assertThat(r).isEqualTo(RetentionSweep.Result.NOTHING);
        verify(webtoon, never()).deleteOldGuestQuota(any());
        verify(webtoon, never()).clearOldNotifyEmails(any());
        verify(webtoon, never()).clearOldJobInputs(any());
        verify(common, never()).clearOldCreditMemos(any());
    }

    @Test
    @DisplayName("게스트 횟수는 90일 전 날짜를 기준으로 지운다")
    void guestCounterCutIs90Days() {
        sweep(true).runOnce(NOW);

        // 2026-09-23 (KST) 에서 90일 전
        LocalDate expected = LocalDate.of(2026, 9, 23).minusDays(90);
        verify(webtoon).deleteOldGuestQuota(expected);
    }

    @Test
    @DisplayName("알림 주소는 보낸 지 30일이 지난 것만 비운다")
    void notifyEmailCutIs30Days() {
        sweep(true).runOnce(NOW);

        verify(webtoon).clearOldNotifyEmails(NOW.minus(java.time.Duration.ofDays(30)));
    }

    @Test
    @DisplayName("생성 기록과 크레딧 메모는 1년이 지난 것만 비운다")
    void recordCutIsOneYear() {
        sweep(true).runOnce(NOW);

        Instant cut = NOW.minus(java.time.Duration.ofDays(365));
        verify(webtoon).clearOldJobInputs(cut);
        verify(common).clearOldCreditMemos(cut);
    }

    @Test
    @DisplayName("한 표가 터져도 회차 전체가 예외를 밖으로 내보내지 않는다")
    void oneFailureDoesNotEscape() {
        when(webtoon.deleteOldGuestQuota(any())).thenThrow(new RuntimeException("DB 가 잠깐 안 됩니다"));

        RetentionSweep.Result r = sweep(true).runOnce(NOW);

        // 시각 트리거에서 예외가 새면 그 뒤로 이 작업이 다시 안 돈다.
        assertThat(r).isEqualTo(RetentionSweep.Result.NOTHING);
    }

    @Test
    @DisplayName("지운 것이 없으면 결과가 비어 있다")
    void nothingToDo() {
        when(webtoon.deleteOldGuestQuota(any())).thenReturn(0);
        when(webtoon.clearOldNotifyEmails(any())).thenReturn(0);
        when(webtoon.clearOldJobInputs(any())).thenReturn(0);
        when(common.clearOldCreditMemos(any())).thenReturn(0);

        assertThat(sweep(true).runOnce(NOW)).isEqualTo(RetentionSweep.Result.NOTHING);
    }

    @Test
    @DisplayName("지운 수를 그대로 돌려준다")
    void reportsCounts() {
        when(webtoon.deleteOldGuestQuota(any())).thenReturn(12);
        when(webtoon.clearOldNotifyEmails(any())).thenReturn(3);
        when(webtoon.clearOldJobInputs(any())).thenReturn(7);
        when(common.clearOldCreditMemos(any())).thenReturn(5);

        assertThat(sweep(true).runOnce(NOW))
                .isEqualTo(new RetentionSweep.Result(12, 3, 7, 5));
    }
}
