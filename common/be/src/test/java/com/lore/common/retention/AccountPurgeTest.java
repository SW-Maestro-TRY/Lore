package com.lore.common.retention;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 탈퇴하고 30일이 지난 계정을 지우는 자리.
 *
 * <b>이 검사가 지키는 것</b> — 아직 30일이 안 된 사람을 지우지 않는가.
 * 실수로 탈퇴한 사람이 되돌릴 시간을 주는 것이 30일을 둔 이유다.
 */
class AccountPurgeTest {

    private static final Instant NOW = Instant.parse("2026-09-23T03:00:00Z");

    private PurgeableUserRepository users;
    private AccountPurgeStep step;

    @BeforeEach
    void setUp() {
        users = mock(PurgeableUserRepository.class);
        step = mock(AccountPurgeStep.class);
        when(users.idsToPurge(any())).thenReturn(List.of());
    }

    private AccountPurge purge(boolean enabled) {
        return new AccountPurge(users, step, enabled);
    }

    @Test
    @DisplayName("꺼져 있으면 목록조차 묻지 않는다")
    void disabledAsksNothing() {
        AccountPurge.Result r = purge(false).runOnce(NOW);

        assertThat(r.accounts()).isZero();
        verify(users, never()).idsToPurge(any());
    }

    @Test
    @DisplayName("30일 전을 기준으로 고른다")
    void cutIs30Days() {
        purge(true).runOnce(NOW);

        verify(users).idsToPurge(NOW.minus(Duration.ofDays(30)));
    }

    @Test
    @DisplayName("지울 사람이 없으면 조용히 끝난다")
    void nobodyToPurge() {
        assertThat(purge(true).runOnce(NOW).accounts()).isZero();
        verify(step, never()).purge(anyLong());
    }

    @Test
    @DisplayName("찾은 사람을 하나씩 지운다")
    void purgesEach() {
        when(users.idsToPurge(any())).thenReturn(List.of(1L, 2L, 3L));
        when(step.purge(anyLong())).thenReturn(4);

        AccountPurge.Result r = purge(true).runOnce(NOW);

        assertThat(r.accounts()).isEqualTo(3);
        assertThat(r.rows()).isEqualTo(12);
        assertThat(r.failed()).isZero();
        verify(step).purge(1L);
        verify(step).purge(2L);
        verify(step).purge(3L);
    }

    @Test
    @DisplayName("한 사람이 실패해도 나머지는 지운다")
    void oneFailureDoesNotStopTheRest() {
        when(users.idsToPurge(any())).thenReturn(List.of(1L, 2L, 3L));
        when(step.purge(1L)).thenThrow(new RuntimeException("이 사람 데이터가 이상합니다"));
        when(step.purge(2L)).thenReturn(1);
        when(step.purge(3L)).thenReturn(1);

        AccountPurge.Result r = purge(true).runOnce(NOW);

        // 한 사람 때문에 나머지 전부가 안 지워지면 안 된다.
        assertThat(r.accounts()).isEqualTo(2);
        assertThat(r.failed()).isEqualTo(1);
        verify(step).purge(2L);
        verify(step).purge(3L);
    }

    @Test
    @DisplayName("목록을 묻다가 터져도 예외를 밖으로 내보내지 않는다")
    void failureDoesNotEscape() {
        when(users.idsToPurge(any())).thenThrow(new RuntimeException("DB 가 잠깐 안 됩니다"));

        // 시각 트리거에서 예외가 새면 그 뒤로 이 작업이 다시 안 돈다.
        assertThat(purge(true).runOnce(NOW).accounts()).isZero();
    }
}
