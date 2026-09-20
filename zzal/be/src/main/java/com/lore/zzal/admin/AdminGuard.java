package com.lore.zzal.admin;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.user.User;
import com.lore.common.user.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자인지 확인한다.
 *
 * <h3>★ 왜 Spring Security 의 hasRole 을 안 쓰는가</h3>
 * {@code JwtAuthenticationFilter} 가 <b>모든 사람에게 {@code ROLE_USER} 를 하드코딩</b>하고 있다.
 * 토큰에 role 클레임 자체가 없다. 그래서 {@code hasRole('ADMIN')} 으로 잠그면
 * <b>상훈님을 포함해 아무도 통과하지 못한다.</b>
 *
 * 토큰에 role 을 넣는 쪽이 더 정석이지만, <b>이미 발급된 토큰에는 그 값이 없다.</b>
 * 하위호환을 잘못 다루면 배포 순간 전원이 로그아웃된다. 지금은 DB 로 판정한다 —
 * 관리자 요청은 드물어서 조회 한 번이 부담이 안 되고, 기존 로그인이 그대로 살아 있다.
 *
 * ⚠️ 이것만으로 충분하지 않다. 관리자 API 는 세 겹으로 막는다.
 *   1. 이 판정
 *   2. {@code @ConditionalOnProperty} — 꺼져 있으면 빈이 안 올라와 매핑 자체가 없다
 *   3. 화면에 noindex — 검색에 걸려 발견되는 것이 실제로 가장 흔한 경로다
 */
@Component
public class AdminGuard {

    private final UserRepository userRepository;

    public AdminGuard(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** 관리자가 아니면 막는다. */
    @Transactional(readOnly = true)
    public void require(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_ONLY));
        if (!user.isAdmin()) {
            throw new BusinessException(ErrorCode.ADMIN_ONLY);
        }
    }
}
