package com.lore.webtoon.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.lore.webtoon.credit.CreditGate;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.util.ContentCachingRequestWrapper;

/**
 * 웹툰 API 요청을 전부 한 줄씩 남긴다(2026-11-03) — 누가 · 어떤 주소를 · 어떤 값으로 · 얼마나 걸려 · 무슨 답을 받았나.
 *
 * <ul>
 *   <li>웹툰 주소({@code /api/webtoon/})만 본다. 화면 행동 기록({@code /events})은 그 자체가 기록이라 뺀다.</li>
 *   <li>요청 본문은 JSON 일 때만, 8,000자까지. 비밀번호·토큰·카드·전화·이메일 칸은 가리고, 아주 긴 글(사진 data
 *       주소 등)은 앞부분만 남긴다. 파일 올리기(multipart)는 크기만.</li>
 *   <li>응답은 흘려 보내면서 앞 2,000바이트만 곁에 베낀다 — 통째로 붙잡아 두면 그림 내려받기·실시간 진행 같은
 *       긴 응답이 멈춘다. 오류 응답은 늘, 성공 응답은 GET 이 아닐 때만 남긴다(만든 작업 번호 같은 것).</li>
 *   <li>쓰기는 {@link ApiLogWriter} 가 뒤에서 한다. 기록 때문에 응답이 늦어지거나 실패하지 않는다.</li>
 * </ul>
 *
 * 기록기는 {@code ObjectProvider} 로 받는다 — 화면 단위 검사({@code @WebMvcTest})는 필터만 끌어오고 저장소는
 * 안 끌어와서, 바로 받으면 웹툰 화면 검사가 전부 문맥을 못 띄운다({@link ServerErrorAlertFilter} 와 같은 이유).
 */
@Component
public class ApiLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiLogFilter.class);

    /** 이보다 오래 걸린 요청은 서버 로그에도 한 줄 남긴다(#1028) — DB 기록은 연결이 바닥나면 같이 밀린다. */
    static final long SLOW_MS = 5000;

    static final int BODY_MAX = 8000;
    static final int RESPONSE_MAX = 2000;
    static final int LONG_TEXT = 1000;

    private static final Pattern SECRET = Pattern.compile(
            "(?i).*(pass|pwd|token|secret|card|cvc|phone|email|authorization|otp|account_no|resident).*");
    private static final Pattern ID_SEGMENT = Pattern.compile(
            "/(?:[0-9]{8}T[0-9]{6}-[0-9a-f]{6}|example-[^/]+|[0-9a-f-]{16,}|[A-Za-z0-9_-]*[0-9][A-Za-z0-9_-]{5,}|[0-9]+)(?=/|$)");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ObjectProvider<ApiLogWriter> writer;

    public ApiLogFilter(ObjectProvider<ApiLogWriter> writer) {
        this.writer = writer;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || !uri.startsWith("/api/webtoon/") || uri.endsWith("/events")
                || writer.getIfAvailable() == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        long t0 = System.nanoTime();
        boolean json = isJson(req.getContentType());
        HttpServletRequest in = json ? new ContentCachingRequestWrapper(req, BODY_MAX * 4) : req;
        Tee out = new Tee(res);
        Throwable failed = null;
        try {
            chain.doFilter(in, out);
        } catch (IOException | ServletException | RuntimeException e) {
            failed = e;
            throw e;
        } finally {
            try {
                record(in, out, json, failed, (System.nanoTime() - t0) / 1_000_000);
            } catch (RuntimeException ignored) {
                // 기록 때문에 요청이 실패하면 안 된다
            }
        }
    }

    private void record(HttpServletRequest req, Tee res, boolean json, Throwable failed, long ms) {
        ApiLogWriter w = writer.getIfAvailable();
        if (w == null) {
            return;
        }
        int status = failed != null && res.getStatus() < 400 ? 500 : res.getStatus();
        String path = req.getRequestURI();
        if (ms >= SLOW_MS) {
            log.warn("오래 걸린 요청 {} {} → {} ({}ms)", req.getMethod(), shape(path), status, ms);
        }
        ApiLog row = new ApiLog(Instant.now(), ApiLog.API, req.getMethod(), path, shape(path));
        row.query = ApiLog.cut(redactQuery(req.getQueryString()), 500);
        row.status = status;
        row.ms = (int) Math.min(Integer.MAX_VALUE, ms);
        row.userId = CreditGate.currentUser();
        String uid = req.getHeader("X-Lore-Uid");
        row.browserUid = ApiLog.cut(uid == null || uid.isBlank() ? req.getParameter("uid") : uid, 64);
        if (json && req instanceof ContentCachingRequestWrapper c) {
            row.requestBody = body(c.getContentAsByteArray());
        } else if (req.getContentLengthLong() > 0) {
            row.requestBody = "(" + req.getContentType() + ", " + req.getContentLengthLong() + " bytes)";
        }
        if (status >= 400 || !"GET".equalsIgnoreCase(req.getMethod())) {
            row.responseBody = res.captured();
        }
        Throwable e = failed != null ? failed
                : req.getAttribute(DispatcherServlet.EXCEPTION_ATTRIBUTE) instanceof Throwable t ? t : null;
        if (e != null) {
            row.error = ApiLog.cut(e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()), 2000);
        }
        w.write(row);
    }

    static boolean isJson(String contentType) {
        return contentType != null && contentType.toLowerCase().contains("json");
    }

    /** 작품·작업·캐릭터 번호를 {id} 로 접는다. 같은 API 를 묶어 세려고. */
    static String shape(String path) {
        return path == null ? "" : ID_SEGMENT.matcher(path).replaceAll("/{id}");
    }

    static String redactQuery(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (String part : q.split("&")) {
            int eq = part.indexOf('=');
            String key = eq < 0 ? part : part.substring(0, eq);
            if (out.length() > 0) {
                out.append('&');
            }
            out.append(eq >= 0 && SECRET.matcher(key).matches() ? key + "=***" : part);
        }
        return out.toString();
    }

    /** JSON 본문 — 가릴 칸은 가리고, 아주 긴 글은 앞만 남긴다. JSON 이 아니면 앞부분 글자 그대로. */
    static String body(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return null;
        }
        String text = new String(raw, StandardCharsets.UTF_8);
        try {
            JsonNode node = JSON.readTree(text);
            return ApiLog.cut(JSON.writeValueAsString(clean(node, null)), BODY_MAX);
        } catch (IOException e) {
            return ApiLog.cut(text, BODY_MAX);
        }
    }

    static JsonNode clean(JsonNode node, String key) {
        if (key != null && SECRET.matcher(key).matches() && !node.isContainerNode()) {
            return TextNode.valueOf("***");
        }
        if (node instanceof ObjectNode o) {
            ObjectNode copy = JSON.createObjectNode();
            for (Iterator<Map.Entry<String, JsonNode>> it = o.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> f = it.next();
                copy.set(f.getKey(), clean(f.getValue(), f.getKey()));
            }
            return copy;
        }
        if (node instanceof ArrayNode a) {
            ArrayNode copy = JSON.createArrayNode();
            a.forEach(x -> copy.add(clean(x, key)));
            return copy;
        }
        if (node.isTextual() && node.asText().length() > LONG_TEXT) {
            String s = node.asText();
            return TextNode.valueOf(s.substring(0, 300) + "…(" + s.length() + "자)");
        }
        return node;
    }

    /** 응답을 그대로 흘려 보내면서 앞부분만 곁에 베끼는 겉옷. */
    static final class Tee extends HttpServletResponseWrapper {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final StringBuilder chars = new StringBuilder();
        private ServletOutputStream stream;
        private PrintWriter writer;

        Tee(HttpServletResponse res) {
            super(res);
        }

        String captured() {
            if (chars.length() > 0) {
                return ApiLog.cut(chars.toString(), RESPONSE_MAX);
            }
            if (bytes.size() == 0) {
                return null;
            }
            return ApiLog.cut(bytes.toString(StandardCharsets.UTF_8), RESPONSE_MAX);
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (stream == null) {
                ServletOutputStream real = super.getOutputStream();
                stream = new ServletOutputStream() {
                    @Override
                    public boolean isReady() {
                        return real.isReady();
                    }

                    @Override
                    public void setWriteListener(WriteListener l) {
                        real.setWriteListener(l);
                    }

                    @Override
                    public void write(int b) throws IOException {
                        real.write(b);
                        if (bytes.size() < RESPONSE_MAX) {
                            bytes.write(b);
                        }
                    }

                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        real.write(b, off, len);
                        int room = RESPONSE_MAX - bytes.size();
                        if (room > 0) {
                            bytes.write(b, off, Math.min(room, len));
                        }
                    }

                    @Override
                    public void flush() throws IOException {
                        real.flush();
                    }

                    @Override
                    public void close() throws IOException {
                        real.close();
                    }
                };
            }
            return stream;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (writer == null) {
                Writer real = super.getWriter();
                writer = new PrintWriter(new Writer() {
                    @Override
                    public void write(char[] c, int off, int len) throws IOException {
                        real.write(c, off, len);
                        int room = RESPONSE_MAX - chars.length();
                        if (room > 0) {
                            chars.append(c, off, Math.min(room, len));
                        }
                    }

                    @Override
                    public void flush() throws IOException {
                        real.flush();
                    }

                    @Override
                    public void close() throws IOException {
                        real.close();
                    }
                });
            }
            return writer;
        }
    }
}
