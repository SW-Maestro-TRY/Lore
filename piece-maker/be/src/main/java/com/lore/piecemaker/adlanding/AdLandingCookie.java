package com.lore.piecemaker.adlanding;

import com.lore.common.analytics.AnonIdResolver;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import java.util.regex.Pattern;

/** 광고 방문 연결은 이미 발급된 익명 쿠키만 읽으며 새 쿠키를 만들지 않는다. */
final class AdLandingCookie {
    private static final Pattern SHAPE = Pattern.compile("[0-9a-f]{32}");

    private AdLandingCookie() { }

    static String readExisting(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (AnonIdResolver.COOKIE.equals(cookie.getName()) && cookie.getValue() != null
                    && SHAPE.matcher(cookie.getValue()).matches()) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
