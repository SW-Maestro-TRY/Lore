package com.lore.webtoon.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 운영자에게 바로 알린다 — 디스코드 웹훅으로(#831).
 *
 * 서버 오류 · 생성 실패 · 하루 상한 · DB · 모델 잔액처럼 <b>사람이 바로 봐야 하는 일</b>이 생기면
 * 디스코드 채널에 한 줄을 보낸다. 웹훅 주소({@code lore.webtoon.alert.discord-webhook})가 없으면
 * 아무것도 하지 않는다 — 로컬과 테스트는 그대로다.
 *
 * <h2>같은 일은 묶는다</h2>
 * 같은 {@code key} 는 {@code lore.webtoon.alert.quiet-minutes}(기본 10분) 안에 한 번만 보낸다.
 * 그 사이 생긴 것은 세어 두었다가 다음 알림에 「그 사이 N번 더」로 붙인다. 오류가 쏟아질 때
 * 채널이 묻히거나 디스코드가 웹훅을 막지 않게 하려는 것이다(웹훅 하나에 분당 30번).
 *
 * <h2>요청을 붙잡지 않는다</h2>
 * 보내기는 따로 한 줄짜리 스레드에서 한다. 디스코드가 느리거나 죽어도 사용자 요청과 생성은
 * 기다리지 않는다. 보내다 실패하면 로그만 남긴다.
 *
 * <h2>무엇을 싣지 않나</h2>
 * 사람이 쓴 글(이야기 · 캐릭터 설명 · 이메일)과 비밀값은 싣지 않는다. 주소 · 작업 번호 · 오류 종류 ·
 * 우리 코드가 만든 이유 한 줄만 싣는다.
 */
@Component
public class OpsAlert {

    private static final Logger log = LoggerFactory.getLogger(OpsAlert.class);
    /** 디스코드 메시지 한 개의 글자 상한. */
    private static final int DISCORD_MAX = 1900;

    public enum Level {
        /** 바로 손대야 하는 것 — 크레딧 바닥 · DB 안 됨. 채널에 @here 를 붙인다. */
        URGENT("🚨", true),
        /** 오류 — 서버 500 · 생성 실패. */
        ERROR("🔴", false),
        /** 알아 두면 되는 것 — 하루 상한에 걸림 · 잔액이 줄어듦 · 복구됨. */
        WARN("🟡", false),
        OK("🟢", false);

        final String mark;
        final boolean ping;

        Level(String mark, boolean ping) {
            this.mark = mark;
            this.ping = ping;
        }
    }

    /** 실제로 보내는 손. 테스트에서 갈아 끼운다. */
    interface Sink {
        void post(String webhook, String json) throws Exception;
    }

    private final String webhook;
    private final String env;
    private final Duration quiet;
    private final Clock clock;
    private final Sink sink;
    private final ExecutorService out;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Instant> lastSent = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> held = new ConcurrentHashMap<>();

    @Autowired
    public OpsAlert(@Value("${lore.webtoon.alert.discord-webhook:}") String webhook,
                    @Value("${lore.webtoon.alert.env:${spring.profiles.active:local}}") String env,
                    @Value("${lore.webtoon.alert.quiet-minutes:10}") long quietMinutes) {
        this(webhook, env, Duration.ofMinutes(quietMinutes), Clock.systemUTC(), httpSink(),
                Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "ops-alert");
                    t.setDaemon(true);
                    return t;
                }));
    }

    OpsAlert(String webhook, String env, Duration quiet, Clock clock, Sink sink, ExecutorService out) {
        this.webhook = webhook == null ? "" : webhook.trim();
        this.env = env == null || env.isBlank() ? "local" : env.trim();
        this.quiet = quiet;
        this.clock = clock;
        this.sink = sink;
        this.out = out;
    }

    public boolean enabled() {
        return !webhook.isEmpty();
    }

    /**
     * 알린다. 같은 {@code key} 가 조용히 할 시간 안에 또 오면 세기만 한다.
     *
     * @param key   묶는 단위 — 같은 일이면 같은 값(예: {@code "http500:/api/webtoon/v1/runs"})
     * @param title 한 줄 제목
     * @param body  자세한 내용(없으면 null). 사람이 쓴 글은 넣지 않는다
     */
    public void send(Level level, String key, String title, String body) {
        if (!enabled()) {
            return;
        }
        Instant now = clock.instant();
        Instant last = lastSent.get(key);
        if (last != null && Duration.between(last, now).compareTo(quiet) < 0) {
            held.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            return;
        }
        lastSent.put(key, now);
        AtomicInteger skipped = held.remove(key);
        String text = format(level, title, body, skipped == null ? 0 : skipped.get());
        String payload;
        try {
            payload = json.writeValueAsString(Map.of("content", text,
                    "allowed_mentions", Map.of("parse", level.ping ? java.util.List.of("everyone") : java.util.List.of())));
        } catch (Exception e) {
            log.warn("알림을 꾸리지 못했습니다 ({})", key, e);
            return;
        }
        out.execute(() -> {
            try {
                sink.post(webhook, payload);
            } catch (Exception e) {
                log.warn("디스코드 알림을 보내지 못했습니다 ({}): {}", key, e.toString());
            }
        });
    }

    String format(Level level, String title, String body, int skipped) {
        StringBuilder b = new StringBuilder();
        if (level.ping) {
            b.append("@here ");
        }
        b.append(level.mark).append(" **[").append(env).append("] ").append(title).append("**");
        if (skipped > 0) {
            b.append(" _(앞 알림 뒤로 ").append(skipped).append("번 더)_");
        }
        if (body != null && !body.isBlank()) {
            // 「충전:」 · 「관리:」 줄은 코드 블록 밖에 둔다 — 안에 두면 디스코드에서 링크가 안 눌린다.
            StringBuilder code = new StringBuilder();
            StringBuilder links = new StringBuilder();
            body.strip().lines().forEach(line -> (line.startsWith("충전:") || line.startsWith("관리:") ? links : code)
                    .append(line).append('\n'));
            if (!code.isEmpty()) {
                b.append("\n```\n").append(code).append("```");
            }
            if (!links.isEmpty()) {
                b.append('\n').append(links.toString().strip());
            }
        }
        String s = b.toString();
        return s.length() <= DISCORD_MAX ? s : s.substring(0, DISCORD_MAX - 4) + "\n```";
    }

    private static Sink httpSink() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return (url, body) -> {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
                throw new IllegalStateException("discord " + res.statusCode() + " " + res.body());
            }
        };
    }
}
