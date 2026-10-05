package com.lore.piecemaker.adlanding;

import com.lore.common.analytics.RequestSizeLimitFilter;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdLandingSizeLimitFilterTest {
    private static final String AD = "/api/piece-maker/v1/ad-landings";
    private final AdLandingSizeLimitFilter filter = new AdLandingSizeLimitFilter();

    @Test
    void encodedAdPathBeforeServletMappingCannotBypassLimit() throws Exception {
        var request = request("/api/piece-maker/v1/ad-landing%73", 4097, false);
        var response = new MockHttpServletResponse();
        var passed = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> passed.set(req));
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("요청이 너무 큽니다");
        assertThat(passed.get()).isNull();
    }

    @Test
    void decodedServletAndPathInfoAreUsedWithContextPath() throws Exception {
        var request = request("/lore/api/piece-maker/v1/ad-landing%73/id/claim", 4097, false);
        request.setContextPath("/lore");
        request.setServletPath("/api");
        request.setPathInfo("/piece-maker/v1/ad-landings/id/claim");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { throw new AssertionError("oversize reached controller"); });
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void preDispatchContextPathAndEncodedAliasRemainLimited() throws Exception {
        var request = request("/lore/api/piece-maker/v1/ad-landing%73", 4097, false);
        request.setContextPath("/lore");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { throw new AssertionError("oversize reached controller"); });
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void chunkedAdBodyAllows4096AndCuts4097AtReadTime() throws Exception {
        var bytes = new AtomicReference<byte[]>();
        filter.doFilter(request(AD, 4096, true), new MockHttpServletResponse(),
                (req, res) -> bytes.set(req.getInputStream().readAllBytes()));
        assertThat(bytes.get()).hasSize(4096);
        assertThatThrownBy(() -> filter.doFilter(request("/api/piece-maker/v1/ad-landing%73", 4097, true),
                new MockHttpServletResponse(), (req, res) -> req.getInputStream().readAllBytes()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    void commonEventsRetainConfiguredLimitWhenBothFiltersArePresent() throws Exception {
        var common = new RequestSizeLimitFilter(65536);
        var bytes = new AtomicReference<byte[]>();
        filter.doFilter(request("/api/v1/events", 65536, true), new MockHttpServletResponse(),
                (req, res) -> common.doFilter(req, res, (inner, ignored) -> bytes.set(inner.getInputStream().readAllBytes())));
        assertThat(bytes.get()).hasSize(65536);
        assertThatThrownBy(() -> filter.doFilter(request("/api/v1/events", 65537, true), new MockHttpServletResponse(),
                (req, res) -> common.doFilter(req, res, (inner, ignored) -> inner.getInputStream().readAllBytes())))
                .isInstanceOf(BusinessException.class);
        var response = new MockHttpServletResponse();
        filter.doFilter(request("/api/v1/events", 65537, false), response,
                (req, res) -> common.doFilter(req, res, (inner, ignored) -> { throw new AssertionError("oversize reached controller"); }));
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void knownLengthBoundaryOnlyAppliesToAdPostEndpoints() throws Exception {
        var bytes = new AtomicReference<byte[]>();
        filter.doFilter(request(AD, 4096, false), new MockHttpServletResponse(),
                (req, res) -> bytes.set(req.getInputStream().readAllBytes()));
        assertThat(bytes.get()).hasSize(4096);
        for (String uri : java.util.List.of(AD, AD + "/receipt/claim")) {
            var response = new MockHttpServletResponse();
            filter.doFilter(request(uri, 4097, false), response,
                    (req, res) -> { throw new AssertionError("oversize reached controller"); });
            assertThat(response.getStatus()).isEqualTo(400);
        }
        var get = request(AD, 4097, false);
        get.setMethod("GET");
        var passed = new AtomicReference<>();
        filter.doFilter(get, new MockHttpServletResponse(), (req, res) -> passed.set(req));
        assertThat(passed.get()).isSameAs(get);
    }

    @Test
    void unrelatedPathsKeepOriginalRequestAndBody() throws Exception {
        for (String uri : java.util.List.of("/api/piece-maker/v1/hypotheses", "/api/v1/events", "/api/v1/event%73",
                "/api/zzal/v1/pets", "/api/webtoon/v1/jobs", AD + "/admin", AD + "/claim", AD + "/id/claim/extra")) {
            var request = request(uri, 65537, false);
            var passed = new AtomicReference<>();
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> passed.set(req));
            assertThat(passed.get()).isSameAs(request);
        }
    }

    private MockHttpServletRequest request(String uri, int bytes, boolean chunked) {
        var request = new MockHttpServletRequest("POST", uri) {
            @Override public long getContentLengthLong() { return chunked ? -1 : super.getContentLengthLong(); }
        };
        request.setContent(new byte[bytes]);
        return request;
    }
}
