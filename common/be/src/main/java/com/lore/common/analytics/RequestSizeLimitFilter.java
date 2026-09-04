package com.lore.common.analytics;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 행동 기록 주소로 오는 본문의 크기를 <b>읽기 전에</b> 막는다.
 *
 * <h3>왜 컨트롤러에서 재면 안 되는가</h3>
 * {@code @RequestBody} 는 메서드가 불리기 <b>전에</b> 이미 본문을 통째로 읽어 객체로 바꾼다.
 * 그래서 컨트롤러 안에서 크기를 재는 것은 <b>다 읽고 나서 "크네요" 하는 것</b>이라
 * 자원은 이미 다 쓴 뒤다.
 *
 * 게다가 길이를 안 알려주고 보내는 방식(chunked)에서는 {@code Content-Length} 가 -1 이라
 * 그 검사 자체를 통째로 지나친다.
 *
 * <h3>왜 이 주소만인가</h3>
 * 이 주소는 <b>로그인 없이 누구나 부를 수 있는 유일한 쓰기 주소</b>다. 나머지는 로그인이
 * 앞을 막아 준다. 아무나 큰 덩어리를 계속 밀어 넣을 수 있는 곳이 여기뿐이라 여기만 막는다.
 *
 * <h3>어떻게 막는가</h3>
 * 두 겹이다.
 * <pre>
 *   1. 길이를 알려주면  그 자리에서 거절한다(읽지도 않는다)
 *   2. 안 알려주면      읽어 나가면서 세고, 넘는 순간 끊는다
 * </pre>
 */
@Component
@Order(1)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private static final String PATH = "/api/v1/events";
    private static final String TOO_LARGE = "요청이 너무 큽니다";

    private final int maxBytes;

    public RequestSizeLimitFilter(@Value("${app.analytics.max-body-bytes:65536}") int maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 길이를 알려주면 읽지도 않고 거절한다.
        //
        // ★ 여기서는 예외를 던지지 않고 응답을 직접 쓴다 — 이 필터는 보안 필터보다 앞이라,
        //   던진 예외를 보안 쪽이 먼저 잡아 <b>401(로그인이 필요합니다)</b> 로 바꿔 버린다.
        //   크기가 문제인데 로그인 문제로 보이면 부르는 쪽이 원인을 못 찾는다.
        if (request.getContentLengthLong() > maxBytes) {
            tooLarge(response);
            return;
        }

        // 길이를 안 알려주는 경우(chunked)는 읽어 나가면서 센다. 그때는 이미 뒤쪽 처리 안이라
        // 우리 예외를 던지면 공통 처리기가 400 으로 바꿔 준다.
        chain.doFilter(new LimitedRequest(request, maxBytes), response);
    }

    /** ★ 우리 봉투 모양으로 답한다 — 이 주소만 다른 모양이면 화면이 그것만 따로 다뤄야 한다. */
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

        private final int limit;

        LimitedRequest(HttpServletRequest request, int limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream origin = super.getInputStream();
            return new ServletInputStream() {
                private int read;

                private int count(int n) {
                    if (n > 0) {
                        read += n;
                        if (read > limit) {
                            // ★ 우리 예외를 던진다 — 자체 예외를 만들면 스프링이 먼저 낚아채
                            //   "처리되지 않은 예외" 로 500 이 나간다(필터의 catch 까지 안 온다).
                            //   BusinessException 은 공통 처리기가 이미 400 으로 바꿔 준다.
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
