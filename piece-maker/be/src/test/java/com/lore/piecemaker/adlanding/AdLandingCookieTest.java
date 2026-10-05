package com.lore.piecemaker.adlanding;

import com.lore.common.analytics.AnonIdResolver;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdLandingCookieTest {
    @Test
    void readsSharedExistingIdentityWithoutReissuingCookie() {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        String anonId = new AnonIdResolver(false).resolve(request, response);
        request.setCookies(new Cookie(AnonIdResolver.COOKIE, anonId));
        assertThat(AdLandingCookie.readExisting(request)).isEqualTo(anonId);
        var untouched = new MockHttpServletResponse();
        assertThat(new AnonIdResolver(false).resolve(request, untouched)).isEqualTo(anonId);
        assertThat(untouched.getHeader("Set-Cookie")).isNull();
    }

    @Test
    void missingAndInvalidCookieCannotBecomeClaimIdentity() {
        assertThat(AdLandingCookie.readExisting(new MockHttpServletRequest())).isNull();
        for (String value : List.of("", "a".repeat(31), "a".repeat(33), "A".repeat(32), "g".repeat(32), "reader@example.com")) {
            var request = new MockHttpServletRequest();
            request.setCookies(new Cookie(AnonIdResolver.COOKIE, value));
            assertThat(AdLandingCookie.readExisting(request)).isNull();
        }
        var wrongName = new MockHttpServletRequest();
        wrongName.setCookies(new Cookie("other_identity", "a".repeat(32)));
        assertThat(AdLandingCookie.readExisting(wrongName)).isNull();
    }
}
