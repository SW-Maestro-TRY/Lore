package com.lore.common.retention;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 한 사람을 지우는 순서.
 *
 * <b>이 검사가 지키는 것</b> — 도메인(그림 포함)을 먼저 지우는가, 그리고
 * 트랜잭션이 실제로 걸려 있는가.
 */
class AccountPurgeStepTest {

    private static final Long USER = 42L;

    @Test
    @DisplayName("도메인을 먼저 지우고 그 다음에 계정을 지운다")
    void domainsBeforeAccount() {
        PurgeableUserRepository users = mock(PurgeableUserRepository.class);
        UserDataPurge domain = mock(UserDataPurge.class);
        when(domain.domain()).thenReturn("webtoon");
        when(domain.purge(USER)).thenReturn(3);

        new AccountPurgeStep(users, List.of(domain)).purge(USER);

        // 계정이 먼저 사라지면 도메인이 그 사람의 것을 못 찾는다.
        InOrder order = inOrder(domain, users);
        order.verify(domain).purge(USER);
        order.verify(users).deleteUser(USER);
    }

    @Test
    @DisplayName("계정을 지우기 전에 외래키가 걸린 것부터 지운다")
    void foreignKeyChildrenFirst() {
        PurgeableUserRepository users = mock(PurgeableUserRepository.class);

        new AccountPurgeStep(users, List.of()).purge(USER);

        // 이 셋이 안 지워지면 DB 가 마지막 줄을 막는다(ON DELETE CASCADE 가 없다).
        InOrder order = inOrder(users);
        order.verify(users).deleteRefreshTokens(USER);
        order.verify(users).deleteCredentials(USER);
        order.verify(users).deleteAgreements(USER);
        order.verify(users).deleteUser(USER);
    }

    @Test
    @DisplayName("도메인이 없어도 계정은 지운다")
    void worksWithNoDomains() {
        PurgeableUserRepository users = mock(PurgeableUserRepository.class);
        when(users.deleteUser(anyLong())).thenReturn(1);

        assertThat(new AccountPurgeStep(users, List.of()).purge(USER)).isEqualTo(1);
    }

    @Test
    @DisplayName("★ purge 에 @Transactional 이 붙어 있다")
    void isTransactional() throws Exception {
        Method m = AccountPurgeStep.class.getMethod("purge", Long.class);

        // 트랜잭션이 없으면 계정만 지워지고 딸린 것이 남는(또는 그 반대의) 상태가
        // 되고, 다음 회차는 그 사람을 다시 못 찾아 찌꺼기가 영영 남는다.
        // 걸렸는지 눈으로 볼 수 없는 종류라 검사로 못 박는다.
        assertThat(m.getAnnotation(org.springframework.transaction.annotation.Transactional.class))
                .as("한 사람을 지우는 것은 한 트랜잭션이어야 한다")
                .isNotNull();
    }
}
