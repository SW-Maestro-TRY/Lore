package com.lore.webtoon;

import com.lore.common.user.User;
import com.lore.common.user.UserRepository;
import com.lore.webtoon.credit.CreditGate;
import org.springframework.stereotype.Component;

/**
 * 지금 요청한 사람이 관리자 계정({@code User.isAdmin()})인가.
 *
 * 운영용 정보 — 예: 「넣은 설정이 간 곳」(#428) — 을 응답에 실을지 가르는 데 쓴다.
 * 로그인 안 했거나 계정이 없으면 관리자가 아니다.
 */
@Component
public class Admins {

    private final UserRepository users;

    public Admins(UserRepository users) {
        this.users = users;
    }

    public boolean isAdmin(Long userId) {
        return userId != null && users.findById(userId).map(User::isAdmin).orElse(false);
    }

    /** 지금 요청한 사람. */
    public boolean current() {
        return isAdmin(CreditGate.currentUser());
    }
}
