package com.lore.webtoon.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class ApiLogFilterTest {

    @Test
    void 작품번호와_캐릭터번호를_접는다() {
        assertThat(ApiLogFilter.shape("/api/webtoon/v1/jobs/20261103T045128-9ade96/pages/3"))
                .isEqualTo("/api/webtoon/v1/jobs/{id}/pages/{id}");
        assertThat(ApiLogFilter.shape("/api/webtoon/v1/play/worlds/w8f3k2abc9"))
                .isEqualTo("/api/webtoon/v1/play/worlds/{id}");
        assertThat(ApiLogFilter.shape("/api/webtoon/v1/play/worlds/make"))
                .isEqualTo("/api/webtoon/v1/play/worlds/make");
    }

    @Test
    void 비밀_칸은_가리고_긴_글은_앞만_남긴다() {
        String body = "{\"name\":\"서윤\",\"email\":\"a@b.c\",\"nested\":{\"password\":\"x\"},"
                + "\"photo\":\"" + "a".repeat(5000) + "\"}";
        String out = ApiLogFilter.body(body.getBytes(StandardCharsets.UTF_8));
        assertThat(out).contains("\"name\":\"서윤\"").contains("\"email\":\"***\"").contains("\"password\":\"***\"")
                .contains("…(5000자)").doesNotContain("a".repeat(400));
        assertThat(ApiLogFilter.redactQuery("view=play&token=abc&uid=1")).isEqualTo("view=play&token=***&uid=1");
    }

    @Test
    void 요청_하나를_값_시간_응답과_함께_남긴다() throws Exception {
        List<ApiLog> written = new ArrayList<>();
        ApiLogWriter writer = new ApiLogWriter(null) {
            @Override
            public void write(ApiLog row) {
                written.add(row);
            }
        };
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("writer", writer);
        ObjectProvider<ApiLogWriter> provider = beans.getBeanProvider(ApiLogWriter.class);
        ApiLogFilter filter = new ApiLogFilter(provider);

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/webtoon/v1/play/runs");
        req.setContentType("application/json");
        req.addHeader("X-Lore-Uid", "u-123");
        req.setContent("{\"world_id\":\"edelia\",\"event_id\":\"edelia-e1\"}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest rq, jakarta.servlet.http.HttpServletResponse rs)
                    throws java.io.IOException {
                rq.getInputStream().readAllBytes();             // 컨트롤러가 본문을 읽는다
                rs.setStatus(201);
                rs.getWriter().write("{\"id\":\"job-1\"}");
            }
        });
        filter.doFilter(req, res, chain);

        assertThat(res.getContentAsString()).isEqualTo("{\"id\":\"job-1\"}");   // 응답은 그대로 나간다
        assertThat(written).hasSize(1);
        ApiLog row = written.get(0);
        assertThat(row.getStatus()).isEqualTo(201);
        assertThat(row.getRequestBody()).contains("edelia-e1");
        assertThat(row.getResponseBody()).contains("job-1");
        assertThat(row.browserUid).isEqualTo("u-123");
    }

    @Test
    void 행동_기록_주소는_남기지_않는다() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("writer", new ApiLogWriter(null));
        ApiLogFilter filter = new ApiLogFilter(beans.getBeanProvider(ApiLogWriter.class));
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/api/webtoon/v1/events"))).isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/v1/auth/me"))).isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/webtoon/v1/play/worlds"))).isFalse();
    }
}
