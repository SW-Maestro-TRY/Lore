package com.lore.webtoon.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 화면이 보낸 행동 기록을 깎아서 저장한다.
 *
 * <h2>이 주소는 누구나 부를 수 있다</h2>
 *
 * 로그인 전 사람이 어디서 나가는지가 가장 알고 싶은 것이라 로그인을 요구하지
 * 않는다. 그래서 받는 쪽에서 막는다.
 *
 * <ul>
 *   <li><b>이름</b>은 소문자·숫자·밑줄만, 60자까지</li>
 *   <li><b>props</b>는 {@link #ALLOWED_KEYS} 에 있는 키만. 값은 짧은 기호·숫자·참거짓만
 *       남기고 나머지는 버린다 — 한글이나 공백이 든 값은 사람이 쓴 글일 수 있어서다</li>
 *   <li>한 번에 {@link #MAX_BATCH} 줄, 한 브라우저가 1분에 {@link #MAX_REQUESTS_PER_MINUTE} 번</li>
 *   <li>IP 와 브라우저 원문은 저장하지 않는다. 셈할 때만 잠깐 쓴다</li>
 * </ul>
 *
 * 걸리면 거절하지 않고 조용히 버린다. 오류를 돌려주면 화면에 그걸 처리하는 코드가
 * 생기는데, 기록은 실패해도 사용자에게 보일 일이 아니다.
 */
@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    static final int MAX_BATCH = 50;
    static final int MAX_REQUESTS_PER_MINUTE = 120;
    private static final int MAX_PROPS = 15;
    private static final int MAX_TRACKED = 20_000;

    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_]{0,59}$");
    private static final Pattern VIEW = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]{0,29}$");
    /** 화면이 만드는 lore_uid 는 "u" + 영숫자다. 모양이 다르면 누가 손으로 넣은 것이다. */
    private static final Pattern UID = Pattern.compile("^[A-Za-z0-9_-]{4,64}$");
    /** props 문자열 값. 정해진 목록에서 온 기호와 id 만 이 모양이다. */
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_.:-]{1,64}$");
    private static final Pattern SOURCE_BAD = Pattern.compile("[^A-Za-z0-9._/-]");

    /**
     * 저장하는 props 키. <b>여기 없는 키는 값이 무엇이든 버린다.</b>
     *
     * 고른 기준은 하나다 — <b>값이 정해진 목록이나 숫자에서 오는가.</b> 사람이 적은
     * 글이 값으로 들어올 수 있는 키는 넣지 않고, 대신 "있었는가" 만 {@code has_*} 로 받는다.
     * 키마다 어떤 이벤트가 쓰는지는 {@code webtoon/docs/analytics.md} 에 있다.
     */
    static final Set<String> ALLOWED_KEYS = Set.of(
            // 어디서 · 무엇을
            "where", "target", "kind", "tab", "pane", "filter", "reason", "status", "result",
            // 만들기 설정
            "step", "quality", "style", "mode", "preset", "lang",
            // 가리키는 것 (작품·작업·캐릭터 id, 몇 번째)
            "run", "job", "character", "n", "ep", "cut", "page",
            // 수·시간·돈
            "count", "ms", "cost", "free_left", "balance",
            // 참거짓
            "mine", "logged_in", "edited", "random", "ok",
            "has_photo", "has_name", "has_desc", "has_note", "has_email");

    /** 화면 시계가 말이 되는 범위. 벗어나면 받은 시각으로 대신한다. */
    private static final Duration TS_PAST = Duration.ofDays(2);
    private static final Duration TS_FUTURE = Duration.ofMinutes(5);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final WebtoonEventRepository events;
    private final Clock clock;
    private final Map<String, long[]> windows = new ConcurrentHashMap<>();

    @Autowired
    public EventService(WebtoonEventRepository events) {
        this(events, Clock.systemUTC());
    }

    EventService(WebtoonEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    /** 화면이 보내는 한 묶음. */
    public record Batch(String uid, String source, String ref, List<Event> events) {
    }

    /** 한 줄. ts 는 화면 시계의 밀리초. */
    public record Event(String name, Long ts, String view, Map<String, Object> props) {
    }

    /**
     * @param headerUid {@code X-Lore-Uid} 머리. 페이지를 떠날 때 보내는 길(sendBeacon)은
     *                  머리를 못 실어서 본문의 uid 도 받는다
     * @param userId    로그인했으면 계정 번호
     * @param ip        셈에만 쓴다. 저장하지 않는다
     * @return 실제로 저장한 줄 수
     */
    @Transactional
    public int collect(Batch batch, String headerUid, Long userId, String ip, String userAgent) {
        if (batch == null || batch.events() == null || batch.events().isEmpty()) {
            return 0;
        }
        String uid = cleanUid(headerUid != null ? headerUid : batch.uid());
        String who = uid != null ? uid : userId != null ? "user:" + userId : "ip:" + ip;
        if (!allow(who)) {
            log.debug("행동 기록 과다 — {} 의 이번 분 요청을 버립니다", who);
            return 0;
        }
        Instant now = Instant.now(clock);
        String device = deviceOf(userAgent);
        String source = cleanSource(batch.source());
        String refHost = hostOf(batch.ref());

        List<WebtoonEvent> rows = new ArrayList<>();
        for (Event e : batch.events()) {
            if (rows.size() >= MAX_BATCH) {
                break;
            }
            if (e == null || e.name() == null || !NAME.matcher(e.name()).matches()) {
                continue;
            }
            String view = e.view() != null && VIEW.matcher(e.view()).matches() ? e.view() : null;
            rows.add(WebtoonEvent.of(e.name(), uid, userId, view, cleanProps(e.props()),
                    device, source, refHost, occurredAt(e.ts(), now), now));
        }
        if (!rows.isEmpty()) {
            events.saveAll(rows);
        }
        return rows.size();
    }

    /** 허용한 키, 허용한 모양의 값만 남긴다. 남는 게 없으면 null. */
    static String cleanProps(Map<String, Object> props) {
        if (props == null || props.isEmpty()) {
            return null;
        }
        Map<String, Object> kept = new LinkedHashMap<>();
        for (Map.Entry<String, Object> p : props.entrySet()) {
            if (kept.size() >= MAX_PROPS) {
                break;
            }
            if (!ALLOWED_KEYS.contains(p.getKey())) {
                continue;
            }
            Object v = p.getValue();
            if (v instanceof Boolean) {
                kept.put(p.getKey(), v);
            } else if (v instanceof Number n && Double.isFinite(n.doubleValue())
                    && Math.abs(n.doubleValue()) <= 1e12) {
                kept.put(p.getKey(), n);
            } else if (v instanceof String s && TOKEN.matcher(s).matches()) {
                kept.put(p.getKey(), s);
            }
        }
        if (kept.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(kept);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    static String cleanUid(String uid) {
        return uid != null && UID.matcher(uid).matches() ? uid : null;
    }

    static String cleanSource(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        String s = SOURCE_BAD.matcher(source.trim()).replaceAll("");
        if (s.isEmpty()) {
            return null;
        }
        return s.length() > 100 ? s.substring(0, 100) : s;
    }

    /** 들어오기 전 주소에서 호스트만. 경로와 쿼리에는 검색어나 개인 링크가 들어 있을 수 있다. */
    static String hostOf(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(ref.trim()).getHost();
            if (host == null || host.isBlank()) {
                return null;
            }
            host = host.toLowerCase();
            return host.length() > 100 ? host.substring(0, 100) : host;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String deviceOf(String userAgent) {
        if (userAgent == null) {
            return null;
        }
        String ua = userAgent.toLowerCase();
        return ua.contains("mobi") || ua.contains("android") || ua.contains("iphone") || ua.contains("ipad")
                ? "mobile" : "desktop";
    }

    private static Instant occurredAt(Long ts, Instant now) {
        if (ts == null) {
            return now;
        }
        Instant at = Instant.ofEpochMilli(ts);
        return at.isBefore(now.minus(TS_PAST)) || at.isAfter(now.plus(TS_FUTURE)) ? now : at;
    }

    /** 1분 창에서 몇 번째인가. 셀 대상이 너무 많아지면 통째로 비운다(그 1분치를 잃을 뿐이다). */
    private boolean allow(String who) {
        long minute = Instant.now(clock).getEpochSecond() / 60;
        if (windows.size() > MAX_TRACKED) {
            windows.clear();
        }
        long[] w = windows.compute(who, (k, old) ->
                old == null || old[0] != minute ? new long[]{minute, 1} : new long[]{minute, old[1] + 1});
        return w[1] <= MAX_REQUESTS_PER_MINUTE;
    }
}
