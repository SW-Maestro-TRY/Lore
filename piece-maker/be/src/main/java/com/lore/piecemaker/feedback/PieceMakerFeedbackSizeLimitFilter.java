package com.lore.piecemaker.feedback;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 피드백 주소로 오는 몸통의 크기를 <b>읽기 전에</b> 막는다.
 *
 * <p>이 주소는 로그인 없이 누구나 부르는 쓰기 주소다. 본문 2,000자 검사는 {@code @RequestBody} 가 몸통을 통째로 읽은
 * <b>뒤에</b> 돌므로, 그 검사만으로는 큰 덩어리를 읽는 일 자체를 막지 못한다. 같은 사정의 행동 기록 주소는
 * 공통의 {@code RequestSizeLimitFilter} 가 막는다 — 그 필터는 그 주소 하나에 묶여 있어 같은 방식을 여기에 따로 둔다.
 *
 * <p>두 겹이다. 길이를 알려주면 읽지 않고 거절하고, 안 알려주면(chunked) 읽어 나가며 세다가 넘는 순간 끊는다.
 * 어느 쪽이든 400 {@code INVALID_INPUT} 이다.
 */
@Component
public class PieceMakerFeedbackSizeLimitFilter extends OncePerRequestFilter {

    static final String PATH = "/api/piece-maker/v1/public/feedback";

    /** 몸통의 위 끝. 본문 2,000자가 전부 여섯 바이트짜리 유니코드 이스케이프로 적혀 와도 12,000바이트라 그 두 배가 넘는 여유다. */
    static final int MAX_BODY_BYTES = 32 * 1024;

    private static final String TOO_LARGE = "요청이 너무 큽니다";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && PATH.equals(decodedPath(request)));
    }

    /**
     * 컨테이너가 풀어 준 경로. 원문 주소({@code getRequestURI})로 비교하면 안 된다 — 퍼센트 인코딩이 그대로라
     * {@code …/feedbac%6B} 같은 주소는 이 필터만 비켜 가고, 경로를 풀어서 맞추는 시큐리티와 컨트롤러에는 닿는다.
     */
    private static String decodedPath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        String pathInfo = request.getPathInfo();
        return (servletPath == null ? "" : servletPath) + (pathInfo == null ? "" : pathInfo);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 길이를 알려주면 읽지도 않고 거절한다. 필터는 공통 예외 처리기 바깥이라 응답을 직접 쓴다.
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            tooLarge(response);
            return;
        }
        // 길이를 안 알려주는 경우(chunked)는 읽어 나가면서 센다. 그때는 컨트롤러가 몸통을 읽는 중이라
        // 우리 예외를 던지면 공통 예외 처리기가 400 으로 바꿔 준다.
        chain.doFilter(new LimitedRequest(request), response);
    }

    /** 공통 봉투 모양으로 답한다 — 이 주소만 다른 모양이면 화면이 그것만 따로 다뤄야 한다. */
    private void tooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("""
                {"success":false,"data":null,\
                "error":{"code":"INVALID_INPUT","message":"요청이 너무 큽니다"},\
                "message":"요청이 너무 큽니다"}""");
    }

    /** 읽은 만큼 세다가 넘으면 끊는다. */
    private static final class LimitedRequest extends HttpServletRequestWrapper {

        LimitedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream origin = super.getInputStream();
            return new ServletInputStream() {
                private int read;

                private int count(int n) {
                    if (n > 0) {
                        read += n;
                        if (read > MAX_BODY_BYTES) {
                            throw new BusinessException(ErrorCode.INVALID_INPUT, TOO_LARGE);
                        }
                    }
                    return n;
                }

                @Override
                public int read() throws IOException {
                    int b = origin.read();
                    count(b < 0 ? 0 : 1);
                    return b;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    return count(origin.read(b, off, len));
                }

                @Override
                public boolean isFinished() {
                    return origin.isFinished();
                }

                @Override
                public boolean isReady() {
                    return origin.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    origin.setReadListener(listener);
                }
            };
        }
    }
}
