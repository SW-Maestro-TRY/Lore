package com.lore.webtoon.ops;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * 웹툰 API 가 500 대를 내면 알린다(#831).
 *
 * 예외는 공용 {@code GlobalExceptionHandler} 가 잡아 500 응답으로 바꾸므로, 여기서는 응답 코드를 보고
 * 스프링이 요청에 남겨 둔 예외(있으면)의 종류와 첫 줄만 싣는다. 웹툰 주소({@code /api/webtoon/})만 본다 —
 * 다른 도메인 오류까지 이 채널에 섞지 않는다.
 *
 * 주소 안의 작품 · 작업 번호는 묶는 열쇠에서 지운다. 안 지우면 작품마다 다른 열쇠가 되어 같은 버그가
 * 작품 수만큼 따로 울린다.
 */
@Component
public class ServerErrorAlertFilter extends OncePerRequestFilter {

    private static final Pattern IDS = Pattern.compile("/(?:[0-9]{8}T[0-9]{6}-[0-9a-f]{6}|example-[^/]+|[0-9a-f]{20}|[0-9]+)(?=/|$)");

    private final OpsAlert alert;

    public ServerErrorAlertFilter(OpsAlert alert) {
        this.alert = alert;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !alert.enabled() || !request.getRequestURI().startsWith("/api/webtoon/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(req, res);
        } catch (IOException | ServletException | RuntimeException e) {
            report(req, 500, e);
            throw e;
        }
        if (res.getStatus() >= 500) {
            Object e = req.getAttribute(DispatcherServlet.EXCEPTION_ATTRIBUTE);
            report(req, res.getStatus(), e instanceof Throwable t ? t : null);
        }
    }

    private void report(HttpServletRequest req, int status, Throwable e) {
        String path = shape(req.getRequestURI());
        String what = e == null ? "(예외 정보 없음)" : e.getClass().getSimpleName() + ": " + firstLine(e.getMessage());
        alert.send(OpsAlert.Level.ERROR, "http" + status + ":" + req.getMethod() + path,
                "서버 오류 " + status + " — " + req.getMethod() + " " + path, what);
    }

    static String shape(String uri) {
        return IDS.matcher(uri).replaceAll("/{id}");
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        String line = s.lines().findFirst().orElse("");
        return line.length() > 300 ? line.substring(0, 300) + "…" : line;
    }
}
