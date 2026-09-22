package com.lore.trailer.hypothesis;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.user.User;
import com.lore.common.user.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영자 판정 — {@code users.role} 이 ADMIN 인 사용자만 운영자 API(2-8, 2-9)를 부를 수 있다.
 *
 * <p>JWT 에 역할이 없어(모두 ROLE_USER) 시큐리티의 {@code hasRole} 로는 잠글 수 없다. zzal 의 {@code AdminGuard} 와
 * 같은 방식으로 DB 에서 역할을 읽는다(NA found.md 5-13). 같은 이름의 빈이 겹치지 않게 trailer 접두를 붙였다.
 * zzal 의 운영자 API 와 달리 켜고 끄는 스위치는 두지 않는다 — 운영자(판정을 돌리는 사용자)가 배포된 서버에서 써야 한다.
 */
@Component
public class TrailerAdminGuard {

    private final UserRepository userRepository;

    public TrailerAdminGuard(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** 운영자가 아니면 403 {@code ADMIN_ONLY}. 없는 사용자도 같다. */
    @Transactional(readOnly = true)
    public void require(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_ONLY));
        if (!user.isAdmin()) {
            throw new BusinessException(ErrorCode.ADMIN_ONLY);
        }
    }
}
