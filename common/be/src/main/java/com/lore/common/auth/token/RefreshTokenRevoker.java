package com.lore.common.auth.token;

import com.lore.common.user.User;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 토큰 전체 폐기를 **별도 트랜잭션**으로 처리한다.
 *
 * ★ 별도 클래스로 뺀 이유가 두 개다.
 *
 *   1) 탈취를 감지하면 "전부 폐기"하고 곧바로 예외를 던지는데, 같은 트랜잭션이면
 *      예외와 함께 폐기까지 롤백된다. 그러면 방어 코드가 있는데 아무 일도 일어나지 않는다.
 *      (2026-09-02 실제로 이 상태였고, 재사용 공격 재현 테스트에서 잡혔다)
 *
 *   2) 같은 클래스 안에서 자기 메서드를 부르면 스프링 프록시를 안 거쳐
 *      REQUIRES_NEW 가 무시된다. 그래서 호출되는 쪽이 다른 빈이어야 한다.
 */
@Component
public class RefreshTokenRevoker {

    private final UserRefreshTokenRepository repository;

    public RefreshTokenRevoker(UserRefreshTokenRepository repository) {
        this.repository = repository;
    }

    /** 바깥 트랜잭션이 실패해도 이 폐기는 남는다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeAll(User user, Instant now) {
        return repository.revokeAllByUser(user, now);
    }
}
