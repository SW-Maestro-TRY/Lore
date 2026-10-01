package com.lore.piecemaker.feedback;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.lore.piecemaker.feedback.PieceMakerFeedbackSizeLimitFilter.MAX_BODY_BYTES;
import static com.lore.piecemaker.feedback.PieceMakerFeedbackSizeLimitFilter.PATH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 피드백 몸통 크기 필터의 단위 검사 — DB 도 스프링 컨텍스트도 쓰지 않는다.
 * 길이를 알려주지 않는 요청(chunked)은 MockMvc 로 만들 수 없어 여기서 따로 본다.
 */
@DisplayName("피드백 몸통 크기 필터")
class PieceMakerFeedbackSizeLimitFilterTest {

    private final PieceMakerFeedbackSizeLimitFilter filter = new PieceMakerFeedbackSizeLimitFilter();

    @Test
    @DisplayName("AC-2-10-8 길이를 알려준 큰 몸통은 읽지 않고 400 으로 답한다")
    void declaredOversizeIsRejectedWithoutReading() throws Exception {
        MockHttpServletRequest request = post(PATH, bytes(MAX_BODY_BYTES + 1));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Object> passed = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> passed.set(req));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("INVALID_INPUT", "요청이 너무 큽니다");
        assertThat(passed.get()).as("컨트롤러까지 가지 않는다").isNull();
    }

    @Test
    @DisplayName("AC-2-10-8 길이를 알려주지 않은 큰 몸통은 읽는 도중에 끊는다")
    void undeclaredOversizeIsCutWhileReading() {
        MockHttpServletRequest request = withoutLength(bytes(MAX_BODY_BYTES + 1));

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> req.getInputStream().readAllBytes()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT))
                .hasMessageContaining("너무 큽니다");
    }

    @Test
    @DisplayName("위 끝까지의 몸통은 그대로 읽힌다")
    void bodyUpToTheLimitPasses() throws Exception {
        byte[] body = bytes(MAX_BODY_BYTES);
        AtomicReference<byte[]> read = new AtomicReference<>();

        filter.doFilter(withoutLength(body), new MockHttpServletResponse(),
                (req, res) -> read.set(req.getInputStream().readAllBytes()));

        assertThat(read.get()).isEqualTo(body);
    }

    @Test
    @DisplayName("AC-2-10-8 주소를 퍼센트 인코딩으로 적어 보내도 비켜 가지 못한다")
    void percentEncodedPathIsStillLimited() throws Exception {
        // 원문 주소는 인코딩된 그대로이고, 컨테이너가 풀어 준 경로만 같다. 시큐리티와 컨트롤러는 푼 경로로 맞춘다.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/piece-maker/v1/public/feedbac%6B");
        request.setServletPath(PATH);
        request.setContent(bytes(MAX_BODY_BYTES + 1));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Object> passed = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> passed.set(req));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(passed.get()).as("컨트롤러까지 가지 않는다").isNull();
    }

    @Test
    @DisplayName("다른 주소와 다른 방식은 건드리지 않는다")
    void otherRequestsAreUntouched() throws Exception {
        MockHttpServletRequest elsewhere = post("/api/piece-maker/v1/hypotheses", bytes(MAX_BODY_BYTES + 1));
        MockHttpServletRequest get = new MockHttpServletRequest("GET", PATH);
        get.setServletPath(PATH);

        for (MockHttpServletRequest request : List.of(elsewhere, get)) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicReference<Object> passed = new AtomicReference<>();

            filter.doFilter(request, response, (req, res) -> passed.set(req));

            assertThat(passed.get()).as("감싸지 않은 원래 요청이 그대로 넘어간다").isSameAs(request);
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    /** 컨테이너가 하는 것처럼 푼 경로를 서블릿 경로에 넣는다 — 필터는 원문 주소가 아니라 이 경로로 비교한다. */
    private static MockHttpServletRequest post(String path, byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        request.setContent(body);
        return request;
    }

    /** 길이를 알려주지 않는 요청(chunked) — 몸통은 있지만 Content-Length 는 -1 이다. */
    private static MockHttpServletRequest withoutLength(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setServletPath(PATH);
        request.setContent(body);
        return request;
    }

    private static byte[] bytes(int size) {
        byte[] body = new byte[size];
        Arrays.fill(body, (byte) 'a');
        return body;
    }
}
