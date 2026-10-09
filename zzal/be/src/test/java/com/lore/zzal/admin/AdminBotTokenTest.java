package com.lore.zzal.admin;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.context.annotation.ConditionContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("관리자 봇 토큰(#702) — 꺼짐이 기본, 맞을 때만 그 사용자로")
class AdminBotTokenTest {

    private static final String TOKEN = "t0ken-for-tests-0123456789abcdef-xyz";

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("★ 비어 있음·32자 미만·사용자 번호 없음 → 꺼짐(어떤 값도 통과 못 함)")
    void offByDefault() {
        assertThat(new AdminBotToken("", 5).enabled()).isFalse();
        assertThat(new AdminBotToken("short", 5).enabled()).isFalse();
        assertThat(new AdminBotToken(TOKEN, 0).enabled()).isFalse();
        assertThat(new AdminBotToken("", 5).authenticate("")).isEmpty();
        assertThat(new AdminBotToken("short", 5).authenticate("short")).isEmpty();
    }

    @Test
    @DisplayName("★ 켜짐 — 맞는 값만 그 사용자, 틀린 값·빈 값은 빈 결과")
    void authenticates() {
        AdminBotToken t = new AdminBotToken(TOKEN, 5);
        assertThat(t.enabled()).isTrue();
        assertThat(t.authenticate(TOKEN)).contains(5L);
        assertThat(t.authenticate(" " + TOKEN + " ")).contains(5L);
        assertThat(t.authenticate(TOKEN + "x")).isEmpty();
        assertThat(t.authenticate(null)).isEmpty();
        assertThat(t.authenticate("")).isEmpty();
    }

    @Test
    @DisplayName("★★ 필터 — 쿠키 로그인이 없을 때 맞는 헤더면 토큰 사용자로, 틀리면 비워 둔다(→401)")
    void filterSetsPrincipal() throws Exception {
        var filter = new AdminBotSecurityConfig.BotTokenFilter(new AdminBotToken(TOKEN, 5));
        FilterChain chain = mock(FilterChain.class);

        var ok = new MockHttpServletRequest("GET", "/api/zzal/v1/admin/layer2");
        ok.addHeader(AdminBotToken.HEADER, TOKEN);
        filter.doFilter(ok, new MockHttpServletResponse(), chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(5L);
        SecurityContextHolder.clearContext();

        var bad = new MockHttpServletRequest("GET", "/api/zzal/v1/admin/layer2");
        bad.addHeader(AdminBotToken.HEADER, "nope");
        filter.doFilter(bad, new MockHttpServletResponse(), chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(org.mockito.ArgumentMatchers.eq(bad), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("★ 필터 — 쿠키로 이미 로그인했으면 그 사람 그대로(토큰이 덮지 않는다)")
    void cookieWins() throws Exception {
        var filter = new AdminBotSecurityConfig.BotTokenFilter(new AdminBotToken(TOKEN, 5));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(9L, null, List.of()));
        var req = new MockHttpServletRequest("GET", "/api/zzal/v1/admin/layer2");
        req.addHeader(AdminBotToken.HEADER, TOKEN);
        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(9L);
    }

    @Test
    @DisplayName("★ 보안 줄은 관리자 스위치·토큰(32자+)·사용자 번호가 다 있을 때만 올라온다")
    void chainCondition() {
        assertThat(cond(env("true", TOKEN, "5"))).isTrue();
        assertThat(cond(env("false", TOKEN, "5"))).isFalse();
        assertThat(cond(env("true", "", "5"))).isFalse();
        assertThat(cond(env("true", "short", "5"))).isFalse();
        assertThat(cond(env("true", TOKEN, "0"))).isFalse();
    }

    private static MockEnvironment env(String enabled, String token, String uid) {
        return new MockEnvironment().withProperty("app.zzal.admin.enabled", enabled)
                .withProperty("app.zzal.admin.bot-token", token).withProperty("app.zzal.admin.bot-user-id", uid);
    }

    private static boolean cond(MockEnvironment env) {
        ConditionContext ctx = mock(ConditionContext.class);
        when(ctx.getEnvironment()).thenReturn(env);
        return new AdminBotSecurityConfig.Enabled().matches(ctx, null);
    }
}
