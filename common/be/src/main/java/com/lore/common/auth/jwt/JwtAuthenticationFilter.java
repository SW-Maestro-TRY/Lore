package com.lore.common.auth.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * 요청마다 access 토큰을 확인해 "누구인지"를 세운다.
 *
 * 토큰을 두 곳에서 찾는다.
 *   1) 쿠키 `access_token` — 웹. HttpOnly 라 스크립트가 못 읽는다(XSS 방어)
 *   2) `Authorization: Bearer ...` — 나중에 붙을 앱(React Native). 쿠키를 다루기 번거로워서다
 *
 * ★ 토큰이 없거나 틀려도 여기서 막지 않는다. 그냥 "인증 안 된 요청"으로 흘려보내고,
 *   막을지 말지는 SecurityConfig 의 경로 규칙이 정한다. 필터가 판단까지 하면
 *   열어둬야 할 경로(랜딩·회원가입)까지 막힌다.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String ACCESS_COOKIE = "access_token";

    private final JwtProvider jwtProvider;
    private final com.lore.common.user.UserRepository users;

    @org.springframework.beans.factory.annotation.Autowired
    public JwtAuthenticationFilter(JwtProvider jwtProvider, com.lore.common.user.UserRepository users) {
        this.jwtProvider = jwtProvider;
        this.users = users;
    }

    /** 계정 확인 없이 토큰만 보는 옛 모양 — 다른 서비스의 시험 설정이 쓴다. */
    public JwtAuthenticationFilter(JwtProvider jwtProvider) {
        this(jwtProvider, null);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        extractToken(request)
                .flatMap(jwtProvider::parseUserId)
                // 토큰의 계정이 이 DB 에 없으면(지워진 계정 · 다른 환경에서 남은 쿠키) 로그인으로 치지 않는다.
                // 그대로 두면 화면은 비로그인(/me 404)인데 서버는 로그인으로 보아 게스트 길이 전부 막힌다(2026-11-03).
                .filter(userId -> users == null || users.existsById(userId))
                .ifPresent(userId -> {
                    var auth = new UsernamePasswordAuthenticationToken(
                            userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                });
        chain.doFilter(request, response);
    }

    private Optional<String> extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return Optional.of(header.substring(7));
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (ACCESS_COOKIE.equals(c.getName())) {
                    return Optional.of(c.getValue());
                }
            }
        }
        return Optional.empty();
    }
}
