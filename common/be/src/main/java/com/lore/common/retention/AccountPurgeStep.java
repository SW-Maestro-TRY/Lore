package com.lore.common.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 한 사람을 지우는 한 걸음.
 *
 * <h2>왜 {@link AccountPurge} 안의 메서드가 아닌가</h2>
 *
 * {@code @Transactional} 은 <b>빈 바깥에서 불릴 때만</b> 걸린다. 스프링이
 * 프록시로 감싸는 구조라서, 같은 클래스 안에서 {@code this.purgeOne(...)} 로
 * 부르면 프록시를 안 거치고 <b>트랜잭션이 조용히 안 걸린다.</b> 그러면 계정만
 * 지워지고 딸린 것이 남거나 그 반대가 되고, 다음 회차는 그 사람을 다시 찾지
 * 못해 찌꺼기가 영영 남는다.
 *
 * 걸렸는지 눈으로 확인할 방법이 없는 종류의 실수라, 아예 다른 빈으로 갈라
 * 두었다.
 */
@Component
public class AccountPurgeStep {

    private static final Logger log = LoggerFactory.getLogger(AccountPurgeStep.class);

    private final PurgeableUserRepository users;
    private final List<UserDataPurge> domains;

    public AccountPurgeStep(PurgeableUserRepository users, List<UserDataPurge> domains) {
        this.users = users;
        this.domains = domains;
    }

    /**
     * 이 사람의 것을 전부 지운다.
     *
     * 순서: 도메인(그림 포함) → 계정에 딸린 것 → 계정.
     * 그림을 먼저 지워야 한다 — DB 행을 먼저 지우면 어느 키를 지워야 하는지
     * 알 길이 사라진다.
     *
     * @return 지운 행 수(기록용)
     */
    @Transactional
    public int purge(Long userId) {
        int rows = 0;
        for (UserDataPurge domain : domains) {
            int n = domain.purge(userId);
            if (n > 0) {
                log.debug("{} 에서 {}행을 지웠습니다 (user={})", domain.domain(), n, userId);
            }
            rows += n;
        }
        // 이 셋은 users 를 가리키는 외래키가 있다. 안 지우면 DB 가 마지막 줄을 막는다.
        rows += users.deleteRefreshTokens(userId);
        rows += users.deleteCredentials(userId);
        rows += users.deleteAgreements(userId);
        rows += users.deleteUser(userId);
        return rows;
    }
}
