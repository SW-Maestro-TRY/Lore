package com.lore.common.auth.jwt;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 토큰을 쿠키로 내보내고 지우는 곳.
 *
 * ★ 세 가지 속성이 핵심이다.
 *   HttpOnly — 자바스크립트가 못 읽는다. localStorage 에 넣으면 XSS 한 번에 통째로 털린다.
 *   Secure   — HTTPS 로만 전송. 로컬(http://localhost)에서는 꺼야 로그인이 된다.
 *   SameSite=Lax — 다른 사이트에서 온 요청에는 쿠키가 안 붙는다(CSRF 기본 방어).
 *
 * ★ refresh 쿠키의 Path 를 좁힌 이유 — 갱신 API 를 부를 때만 브라우저가 보낸다.
 *   모든 요청에 실려 다니면 노출 빈도가 그만큼 올라가는데, 이 토큰은 14일짜리다.
 */
@Component
public class AuthCookies {

    public static final String ACCESS = JwtAuthenticationFilter.ACCESS_COOKIE;
    public static final String REFRESH = "refresh_token";
    private static final String REFRESH_PATH = "/api/v1/auth";

    private final boolean secure;

    public AuthCookies(@Value("${app.cookie.secure:true}") boolean secure) {
        this.secure = secure;
    }

    public void write(HttpServletResponse response, String accessToken, String refreshToken,
                      Duration accessExpiry, Duration refreshExpiry) {
        response.addHeader("Set-Cookie", cookie(ACCESS, accessToken, "/", accessExpiry).toString());
        response.addHeader("Set-Cookie", cookie(REFRESH, refreshToken, REFRESH_PATH, refreshExpiry).toString());
    }

    /** 로그아웃 — 수명 0 으로 덮어써 지운다. Path 가 발급 때와 같아야 실제로 지워진다. */
    public void clear(HttpServletResponse response) {
        response.addHeader("Set-Cookie", cookie(ACCESS, "", "/", Duration.ZERO).toString());
        response.addHeader("Set-Cookie", cookie(REFRESH, "", REFRESH_PATH, Duration.ZERO).toString());
    }

    private ResponseCookie cookie(String name, String value, String path, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path(path)
                .maxAge(maxAge)
                .build();
    }
}
