package com.lore.zzal.openai;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** 창구 시험용 흉내 OpenAI — 지연·상태를 마음대로, 동시 처리 수와 도착 순서를 센다. 과금 0. */
final class GatewayTestServer implements AutoCloseable {
    final HttpServer server;
    final AtomicInteger inFlight = new AtomicInteger();
    final AtomicInteger maxInFlight = new AtomicInteger();
    final AtomicInteger requests = new AtomicInteger();
    final List<String> arrivals = new CopyOnWriteArrayList<>();
    volatile long delayMs = 0;
    /** 요청 꼬리표(쿼리)별 지연. 있으면 {@link #delayMs} 대신 쓴다. */
    final Map<String, Long> delayByTag = new ConcurrentHashMap<>();
    volatile int status = 200;
    volatile String body = "{\"id\":\"r\",\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1},\"choices\":[{\"message\":{\"content\":\"{}\"}}]}";
    final Map<String, String> headers = new ConcurrentHashMap<>();

    GatewayTestServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", e -> {
            requests.incrementAndGet();
            arrivals.add(String.valueOf(e.getRequestURI().getQuery()));
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
                e.getRequestBody().readAllBytes();
                long delay = delayByTag.getOrDefault(String.valueOf(e.getRequestURI().getQuery()), delayMs);
                if (delay > 0) Thread.sleep(delay);
                byte[] b = body.getBytes();
                headers.forEach((k, v) -> e.getResponseHeaders().add(k, v));
                e.sendResponseHeaders(status, b.length);
                e.getResponseBody().write(b);
            } catch (Exception ignored) {
            } finally {
                inFlight.decrementAndGet();
                e.close();
            }
        });
        server.start();
    }

    URI uri(String tag) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions?" + tag);
    }

    HttpRequest request(String tag) {
        return HttpRequest.newBuilder(uri(tag)).timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
