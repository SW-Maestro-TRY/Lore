package com.lore.zzal.admin;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 지금 요청이 봇 토큰(X-Admin-Token)으로 들어왔는지 판정한다.
 *
 * ★ 사용자 번호로 가르지 않는다 — {@code bot-user-id} 가 관리자 본인 계정일 수 있어, 번호를 비교하면
 *   쿠키로 로그인한 사람의 pick 까지 막힌다. 봇 보안 줄이 붙이는 {@link AdminBotToken#BOT_AUTHORITY} 만 본다.
 *
 * 봇이 할 수 있는 일은 목록 읽기 · 집기 · 후보 올리기 · 메모 적기뿐이고, 사용자 화면을 바꾸는 고르기(pick)는
 * 사람만 한다(설계 6절, 결정 9).
 */
@Component
public class AdminCaller {

    public boolean isBot() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        for (GrantedAuthority a : auth.getAuthorities()) {
            if (AdminBotToken.BOT_AUTHORITY.equals(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    /** 봇 토큰 호출이면 403. */
    public void requireHuman() {
        if (isBot()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "봇 토큰으로는 고를 수 없습니다");
        }
    }
}
