package com.lore.zzal.admin;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** 봇 토큰 호출 판정 — 사용자 번호가 아니라 권한 표식으로 가른다(bot-user-id 가 관리자 본인 계정일 수 있다). */
@DisplayName("관리자 호출자 — 봇 토큰 표식")
class AdminCallerTest {

    private static final String TOKEN = "admin-caller-test-token-0123456789abcdef";
    private final AdminCaller caller = new AdminCaller();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("권한에 ZZAL_ADMIN_BOT 이 있으면 봇 — requireHuman 은 FORBIDDEN")
    void botAuthority_isBot() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(5L, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority(AdminBotToken.BOT_AUTHORITY))));
        assertThat(caller.isBot()).isTrue();
        assertThatThrownBy(caller::requireHuman)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("ROLE_USER 만 있으면(쿠키 로그인) 사람 — 같은 사용자 번호여도 예외 없음")
    void cookieLogin_isHuman() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(5L, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        assertThat(caller.isBot()).isFalse();
        assertThatCode(caller::requireHuman).doesNotThrowAnyException();

        SecurityContextHolder.clearContext();
        assertThat(caller.isBot()).as("인증이 없으면 봇이 아니다(인증 판정은 다른 줄이 한다)").isFalse();
    }

    @Test
    @DisplayName("★ 필터가 토큰 사용자에게 봇 표식을 붙인다")
    void filterAddsBotAuthority() throws Exception {
        var filter = new AdminBotSecurityConfig.BotTokenFilter(new AdminBotToken(TOKEN, 5));
        var req = new MockHttpServletRequest("GET", "/api/zzal/v1/admin/layer2");
        req.addHeader(AdminBotToken.HEADER, TOKEN);
        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_USER", AdminBotToken.BOT_AUTHORITY);
        assertThat(caller.isBot()).isTrue();
    }
}
