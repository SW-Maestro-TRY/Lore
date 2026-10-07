package com.lore.common.analytics;

import com.lore.common.analytics.dto.EventRequests;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 쿠키가 지워진 브라우저의 옛 익명 번호 잇기 — 사본 쿠키 발급과 from 고르기. */
class AnonIdLinkTest {

    private static final String OLD = "0123456789abcdef0123456789abcdef";
    private static final String NOW = "fedcba9876543210fedcba9876543210";

    @Test
    void 새로_발급하면_사본도_같은_번호로_맞춘다() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        AnonIdResolver resolver = new AnonIdResolver(false);
        String id = resolver.resolve(req, res);
        resolver.syncHint(req, res, id);

        List<String> cookies = res.getHeaders("Set-Cookie");
        assertThat(cookies).hasSize(2);
        assertThat(cookies.get(0)).startsWith("lore_anon_id=" + id).contains("HttpOnly");
        assertThat(cookies.get(1)).startsWith("lore_anon_hint=" + id).doesNotContain("HttpOnly");
    }

    @Test
    void 사본이_없던_옛_브라우저에는_한_번_맞춰_주고_맞으면_안_보낸다() {
        AnonIdResolver resolver = new AnonIdResolver(false);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie("lore_anon_id", NOW));
        MockHttpServletResponse res = new MockHttpServletResponse();
        String id = resolver.resolve(req, res);
        assertThat(res.getHeaders("Set-Cookie")).isEmpty();   // resolve 자체는 그대로(피스메이커 계약)
        resolver.syncHint(req, res, id);
        assertThat(res.getHeaders("Set-Cookie")).hasSize(1);
        assertThat(res.getHeaders("Set-Cookie").get(0)).startsWith("lore_anon_hint=" + NOW);

        MockHttpServletRequest req2 = new MockHttpServletRequest();
        req2.setCookies(new Cookie("lore_anon_id", NOW), new Cookie("lore_anon_hint", NOW));
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        resolver.syncHint(req2, res2, resolver.resolve(req2, res2));
        assertThat(res2.getHeaders("Set-Cookie")).isEmpty();
    }

    @Test
    void 사본을_바꿔_넣어도_기록되는_번호는_본_쿠키다() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie("lore_anon_id", NOW), new Cookie("lore_anon_hint", OLD));
        assertThat(new AnonIdResolver(false).resolve(req, new MockHttpServletResponse())).isEqualTo(NOW);
    }

    @Test
    void 가입_로그인_성공_줄의_모양_맞는_옛_번호만_고른다() {
        EventRequests.Batch batch = new EventRequests.Batch(null, null, List.of(
                new EventRequests.Event("auth_login_succeeded", 1L, "/zzal", Map.of("from", OLD)),
                new EventRequests.Event("auth_signup_succeeded", 1L, "/zzal", Map.of("from", OLD)),   // 중복
                new EventRequests.Event("auth_login_succeeded", 1L, "/zzal", Map.of("from", NOW)),    // 지금 번호
                new EventRequests.Event("auth_login_succeeded", 1L, "/zzal", Map.of("from", "t0")),   // 모양 틀림
                new EventRequests.Event("zzal_page_view", 1L, "/zzal", Map.of("from", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))));

        assertThat(AnalyticsService.previousAnonIds(batch, NOW)).containsExactly(OLD);
    }
}
