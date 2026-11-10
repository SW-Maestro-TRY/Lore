package com.lore.piecemaker.adlanding;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Piece Maker 광고 방문·연결 POST의 본문만 4 KiB로 제한한다. */
@Component
@Order(1)
public class AdLandingSizeLimitFilter extends OncePerRequestFilter {
    private static final String PATH = "/api/piece-maker/v1/ad-landings";
    private static final Pattern CLAIM_PATH = Pattern.compile(Pattern.quote(PATH) + "/[^/]+/claim");
    private static final int MAX_BODY_BYTES = 4096;
    private static final String TOO_LARGE = "요청이 너무 큽니다";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) return true;
        String path = decodedPath(request);
        return !PATH.equals(path) && !CLAIM_PATH.matcher(path).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("""
                    {"success":false,"data":null,\
                    "error":{"code":"INVALID_INPUT","message":"요청이 너무 큽니다"},\
                    "message":"요청이 너무 큽니다"}""");
            return;
        }
        // Content-Length가 없는 요청도 본문을 읽는 동안 같은 상한을 적용한다.
        chain.doFilter(new LimitedRequest(request), response);
    }

    /** 광고 API에만 컨테이너의 디코딩 경로를 사용한다. 공통 이벤트의 경로 처리는 바꾸지 않는다. */
    private static String decodedPath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        String pathInfo = request.getPathInfo();
        String resolved = (servletPath == null ? "" : servletPath) + (pathInfo == null ? "" : pathInfo);
        if (!resolved.isEmpty()) return resolved;
        // 서블릿 매핑 전 MockMvc에서는 context 경로를 빼고 URI를 한 번만 디코딩한다.
        String raw = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && raw.startsWith(context + "/")) raw = raw.substring(context.length());
        try { return UriUtils.decode(raw, StandardCharsets.UTF_8); }
        catch (IllegalArgumentException invalidEncoding) { return raw; }
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {
        LimitedRequest(HttpServletRequest request) { super(request); }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream origin = super.getInputStream();
            return new ServletInputStream() {
                private int read;

                private int count(int size) {
                    if (size > 0) {
                        read += size;
                        if (read > MAX_BODY_BYTES) throw new BusinessException(ErrorCode.INVALID_INPUT, TOO_LARGE);
                    }
                    return size;
                }

                @Override public int read() throws IOException {
                    int value = origin.read();
                    count(value < 0 ? 0 : 1);
                    return value;
                }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    return count(origin.read(bytes, offset, length));
                }
                @Override public boolean isFinished() { return origin.isFinished(); }
                @Override public boolean isReady() { return origin.isReady(); }
                @Override public void setReadListener(ReadListener listener) { origin.setReadListener(listener); }
            };
        }
    }
}
