package com.lore.common.analytics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.common.analytics.dto.EventRequests;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 들어온 기록을 <b>깎아서</b> 저장하는 곳.
 *
 * <h3>★ 이 클래스의 본체는 "저장" 이 아니라 "버리기" 다</h3>
 * 화면 쪽 코드는 지금 조심스럽게 짜여 있다(이름은 "쳤다" 만, 오류는 코드만). 하지만 그 조심성이
 * 코드로 강제돼 있지 않아서, 앞으로 한 줄만 잘못 들어가면 샌다. 실제로 지금도 새고 있다 —
 * {@code CharacterCreator.tsx} 의 {@code feedback_submit} 은 이메일 원문·후기 본문·캐릭터
 * 설명을 통째로 실어 보낸다. 화면을 고치는 것으로는 부족하다. 다음에 또 들어오기 때문이다.
 * <p>
 * 그래서 <b>서버가 최종 관문</b>이다. 아래 네 가지가 그 관문이다.
 * <ol>
 *   <li>{@link #ALLOWED_PROP_KEYS} 밖의 키는 통째로 버린다 — 이 하나가 나머지를 다 덮는다</li>
 *   <li>{@code referrer} 는 쿼리스트링을 잘라 낸다 — 쿼리에 이메일·토큰이 실려 온다</li>
 *   <li>기기는 헤더에서 mobile/desktop 두 글자만 뽑는다 — User-Agent 원문은 지문이다</li>
 *   <li>익명 번호는 쿠키에서만 온다 — 본문을 믿으면 남의 번호로 기록을 심을 수 있다</li>
 * </ol>
 *
 * <h3>버릴 때 소리를 내지 않는다</h3>
 * 규칙에 안 맞는 값은 400 이 아니라 조용히 버린다. 기록은 화면의 곁다리라, 여기서 실패를
 * 돌려주면 그걸 처리하는 코드가 화면에 생기고 결국 기록이 화면을 멈추게 한다.
 * 대신 <b>몇 개를 왜 버렸는지는 로그에 남긴다</b> — 조용히 버리는 것과 모르게 버리는 것은 다르다.
 */
@Service
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);

    /**
     * ★★ 저장을 허용하는 {@code props} 키. <b>여기 없는 키는 값이 무엇이든 버린다.</b>
     *
     * <p>실제 호출부 41곳(AuthModal · useZzalSession · useTamagotchi · CharacterCreator)을
     * 읽고 정했다. 고른 기준은 하나다 — <b>값의 가짓수가 미리 정해져 있는가.</b>
     * 사람이 타이핑한 것이 값으로 들어올 수 있는 키는 넣지 않았다.
     *
     * <table border="1">
     *   <caption>키와 근거</caption>
     *   <tr><td>action</td>      <td>zzal_care·result_action — 돌보기 종류/저장·공유. 버튼이 정한 값이다</td></tr>
     *   <tr><td>tab</td>         <td>auth_modal_opened·dismissed — login|signup 둘뿐</td></tr>
     *   <tr><td>from, to</td>    <td>auth_tab_switched — 위와 같은 두 값</td></tr>
     *   <tr><td>code</td>        <td>auth_*_failed·zzal_pet_create_failed — ErrorCode 이름 또는 client_* 상수</td></tr>
     *   <tr><td>reason</td>      <td>generate_blocked·zzal_hatch_failed·auth_modal_dismissed — 열거된 사유</td></tr>
     *   <tr><td>type</td>        <td>feedback_submit — email|feedback</td></tr>
     *   <tr><td>stars</td>       <td>feedback_submit — 1~5 점수</td></tr>
     *   <tr><td>has_image, has_keywords, has_note, has_email</td>
     *                            <td>★ 내용 대신 "있었는가" 만 남기는, 이 서비스가 이미 쓰고 있는 패턴</td></tr>
     *   <tr><td>step, count, seq, ms</td>
     *                            <td>아직 안 쓰지만 곧 들어올 자리(미니게임·후기 단계·소요시간).
     *                                숫자이거나 열거값이라 새어도 개인을 가리키지 않는다</td></tr>
     * </table>
     *
     * <p>★ 반대로 <b>일부러 뺀 것</b>들이 이 목록보다 중요하다.
     *    {@code email} · {@code fb_text} · {@code char_name} · {@code char_desc} ·
     *    {@code char_appearance} · {@code keywords} · {@code fb_tags} — 지금 화면이
     *    실제로 보내고 있는 값들이고, 전부 사람이 쓴 글이다. 하나도 저장되지 않는다.
     *
     * <p>키를 늘릴 때의 질문은 "이걸 보면 편한가" 가 아니라 <b>"이 칸에 사람이 쓴 글이
     *    들어올 수 있는가"</b> 다. 들어올 수 있으면 넣지 않는다.
     */
    private static final Set<String> ALLOWED_PROP_KEYS = Set.of(
            "action", "tab", "from", "to", "code", "reason", "type", "stars",
            "has_image", "has_keywords", "has_note", "has_email",
            "step", "count", "seq", "ms");

    /** 이벤트 이름의 생김새. 화면이 부르는 이름 그대로라 소문자·숫자·밑줄뿐이다. */
    private static final Pattern EVENT_NAME = Pattern.compile("^[a-z][a-z0-9_]{0,59}$");

    /** 유입 출처에 남길 수 있는 글자. UTM 값은 원래 이 범위이고, 벗어나면 누가 손으로 넣은 것이다. */
    private static final Pattern SOURCE_SAFE = Pattern.compile("[^A-Za-z0-9._/\\-]");

    /** props 문자열 값의 길이 상한. 넘으면 자르지 않고 <b>버린다</b> — 잘라 봐야 앞부분이 남는다. */
    private static final int MAX_PROP_VALUE = 64;

    /** 한 이벤트에 담을 수 있는 props 개수. 허용 키가 16개뿐이라 사실상 여유값이다. */
    private static final int MAX_PROPS = 10;

    /** 칸 길이(엔티티)와 맞춘 상한. 넘치면 저장이 통째로 실패하므로 여기서 자른다. */
    private static final int MAX_PATH = 200;
    private static final int MAX_REFERRER = 200;
    private static final int MAX_SOURCE = 100;

    /**
     * 시각이 말이 되는 범위.
     *
     * ★ 브라우저 시계는 틀려 있는 경우가 실제로 있다(시간대를 손으로 바꿔 둔 기기).
     *   말이 안 되는 시각을 그대로 넣으면 "언제 무슨 일이 있었나" 를 볼 때 그 줄들이
     *   엉뚱한 날짜에 흩어져 조용히 통계를 망친다. 벗어나면 서버 도착 시각으로 대신한다.
     */
    private static final Duration TS_PAST_LIMIT = Duration.ofDays(2);
    private static final Duration TS_FUTURE_LIMIT = Duration.ofMinutes(5);

    /**
     * 익명 번호 하나가 1분에 보낼 수 있는 요청 수.
     *
     * ★ 정상 화면은 5초에 한 번(=분당 12회)이다. 열 배로 잡아 두면 진짜 사용자는 절대 안 걸리고,
     *   자동으로 두드리는 쪽만 걸린다. 걸리면 거절하지 않고 <b>그냥 버린다</b> —
     *   429 를 돌려주면 그걸 처리하는 코드가 화면에 생긴다.
     */
    private static final int MAX_REQUESTS_PER_MINUTE = 120;

    /**
     * 분당 카운터를 들고 있을 수 있는 번호 개수.
     *
     * ★ 상한이 없으면 이 Map 자체가 공격 통로가 된다 — 쿠키를 매번 새로 만들며 두드리면
     *   메모리가 무한히 는다. 넘치면 통째로 비운다(그 순간 1분치 셈을 잃지만, 그건 괜찮다).
     */
    private static final int MAX_TRACKED_CLIENTS = 20_000;

    /** 이미 이어 붙인 (익명번호, 사용자) 쌍. DB 를 매 배치마다 두드리지 않으려는 앞단 캐시다. */
    private static final int MAX_CACHED_LINKS = 10_000;

    /**
     * props 를 JSON 문자열로 만드는 데만 쓴다.
     *
     * ★ 스프링이 관리하는 것을 주입받지 않고 직접 만든다 — Boot 4 의 관리 빈은 Jackson 3
     *   ({@code tools.jackson}) 이고, 그건 <b>요청·응답 본문을 다루는 도구</b>다.
     *   여기서 만드는 것은 밖으로 나가는 응답이 아니라 <b>DB 칸에 들어갈 문자열</b>이라,
     *   응답 형식 설정이 바뀔 때 저장 형식까지 함께 흔들리면 안 된다.
     *   zzal/be 의 OpenAI 클라이언트도 같은 이유로 자기 것을 따로 만들어 쓴다.
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AnalyticsEventRepository eventRepository;
    private final AnonIdentityRepository identityRepository;
    private final boolean enabled;
    private final int maxBatch;

    /** 익명번호 → [분, 그 분에 들어온 요청 수]. */
    private final Map<String, RateWindow> rateWindows = new ConcurrentHashMap<>();

    /** "익명번호:사용자번호" 문자열 집합. */
    private final Set<String> linkedCache = ConcurrentHashMap.newKeySet();

    public AnalyticsService(AnalyticsEventRepository eventRepository,
                            AnonIdentityRepository identityRepository,
                            @Value("${app.analytics.enabled:true}") boolean enabled,
                            @Value("${app.analytics.max-batch:50}") int maxBatch) {
        this.eventRepository = eventRepository;
        this.identityRepository = identityRepository;
        this.enabled = enabled;
        this.maxBatch = maxBatch;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getMaxBatch() {
        return maxBatch;
    }

    /**
     * 묶음 하나를 받아 깎아서 저장한다.
     *
     * @param anonId    ★ 쿠키에서 온 것만 들어온다. 본문에서 온 값은 여기 닿을 수 없다
     * @param userId    로그인 상태면 사용자 번호, 아니면 null
     * @param userAgent 헤더 원문. ★ <b>대분류를 뽑는 데만 쓰고 저장하지 않는다</b>
     * @return 실제로 저장된 줄 수
     */
    @Transactional
    public int collect(EventRequests.Batch batch, String anonId, Long userId, String userAgent) {
        if (!enabled) return 0;
        if (!allowRate(anonId)) {
            log.debug("행동 기록 과다 — 익명번호 {} 의 이번 분 요청을 버린다", anonId);
            return 0;
        }

        Instant now = Instant.now();
        String referrer = sanitizeReferrer(batch.referrer());
        String source = sanitizeSource(batch.source());
        String device = deviceOf(userAgent);

        List<AnalyticsEvent> rows = new ArrayList<>();
        int dropped = 0;
        for (EventRequests.Event e : batch.events()) {
            if (e == null || e.name() == null || !EVENT_NAME.matcher(e.name()).matches()) {
                dropped++;
                continue;
            }
            rows.add(AnalyticsEvent.of(
                    e.name(),
                    anonId,
                    userId,
                    sanitizeProps(e.props()),
                    sanitizePath(e.path()),
                    referrer,
                    source,
                    device,
                    null,
                    occurredAt(e.ts(), now),
                    now));
        }

        if (dropped > 0) {
            log.debug("이름이 규칙에 안 맞아 버린 이벤트 {}건", dropped);
        }
        if (!rows.isEmpty()) {
            eventRepository.saveAll(rows);
        }

        return rows.size();
    }

    // ── 익명 ↔ 로그인 잇기 ─────────────────────────────────────────────────

    /**
     * "이 브라우저는 이 사람이었다" 를 한 줄 남긴다.
     *
     * <h3>★ 지난 이벤트를 소급해서 고치지 않는다</h3>
     * 가입하는 순간 그 앞의 수백 줄을 UPDATE 하고 싶어지지만, 그건 t3.micro 에서 랜딩 화면이
     * 멈추는 길이다. 이 한 줄만 있으면 나중에 조인해서 똑같이 풀린다 —
     * {@code zzal_event e JOIN zzal_anon_identity i ON e.anon_id = i.anon_id}.
     *
     * <h3>★ collect 와 <b>다른 트랜잭션</b>인 것이 중요하다</h3>
     * 유니크 제약(uk_anon_identity)에 걸리는 것은 "이미 이어져 있다" 는 정상 상태다
     * (같은 순간 두 요청이 함께 넣으려 한 경우). 그런데 제약 위반은 트랜잭션을 롤백 표시로
     * 만들어, 같은 트랜잭션 안에 있던 <b>이벤트 저장까지 함께 버려진다.</b>
     * 그래서 컨트롤러가 collect 와 이 메서드를 따로 부른다 — 한 클래스 안에서 서로 부르면
     * 프록시를 안 거쳐 이 {@code @Transactional} 자체가 통째로 무시되므로,
     * 호출 지점을 바깥에 두는 것이 곧 설계다.
     *
     * <h3>실패해도 위로 던지지 않는다</h3>
     * 잇기에 실패해도 이벤트는 이미 저장돼 있고, 다음 요청에서 다시 시도된다.
     * 기록의 곁다리 때문에 화면이 멈추면 안 된다.
     */
    @Transactional
    public void linkIdentity(String anonId, Long userId) {
        if (!enabled || userId == null || anonId == null) return;

        String key = anonId + ":" + userId;
        if (linkedCache.contains(key)) return;

        try {
            if (!identityRepository.existsByAnonIdAndUserId(anonId, userId)) {
                identityRepository.save(AnonIdentity.link(anonId, userId, Instant.now()));
            }
            rememberLink(key);
        } catch (DataIntegrityViolationException ex) {
            // 이미 있다는 뜻이다. 이 트랜잭션만 롤백되고 이벤트 저장은 이미 커밋돼 있다.
            rememberLink(key);
        } catch (RuntimeException ex) {
            log.warn("익명번호 잇기 실패 — anon={} user={}", anonId, userId, ex);
        }
    }

    private void rememberLink(String key) {
        if (linkedCache.size() >= MAX_CACHED_LINKS) linkedCache.clear();
        linkedCache.add(key);
    }

    // ── 깎아내기 ───────────────────────────────────────────────────────────

    /**
     * ★★ 허용된 키만 남긴다. 이 메서드가 이 기능 전체의 방어선이다.
     *
     * <p>값도 함께 본다 — 숫자·불리언·짧은 문자열(64자)만. 배열이나 객체는 통째로 버린다.
     * 그래서 지금 화면이 보내는 {@code keywords}(배열)·{@code fb_tags}(배열)는 키가
     * 허용 목록에 없기도 하고, 있었더라도 모양에서 걸린다.
     *
     * <p>문자열이 64자를 넘으면 <b>자르지 않고 버린다.</b> 잘라서 넣으면 이메일 앞부분이나
     * 후기 첫 문장이 그대로 남는다 — 개인정보의 절반은 여전히 개인정보다.
     */
    private String sanitizeProps(Map<String, Object> props) {
        if (props == null || props.isEmpty()) return null;

        Map<String, Object> kept = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : props.entrySet()) {
            if (kept.size() >= MAX_PROPS) break;
            if (!ALLOWED_PROP_KEYS.contains(entry.getKey())) continue;

            Object value = entry.getValue();
            if (value instanceof Boolean b) {
                kept.put(entry.getKey(), b);
            } else if (value instanceof Number n) {
                // NaN·Infinity 는 JSON 으로 나갈 수 없어 직렬화 자체가 터진다.
                if (n instanceof Double d && !Double.isFinite(d)) continue;
                if (n instanceof Float f && !Float.isFinite(f)) continue;
                kept.put(entry.getKey(), n);
            } else if (value instanceof String s) {
                String trimmed = s.trim();
                if (!trimmed.isEmpty() && trimmed.length() <= MAX_PROP_VALUE) {
                    kept.put(entry.getKey(), trimmed);
                }
            }
            // null·배열·객체는 여기까지 안 온다. 버린다.
        }

        if (kept.isEmpty()) return null;
        try {
            return JSON.writeValueAsString(kept);
        } catch (JsonProcessingException ex) {
            // 여기까지 온 값은 전부 원시 타입이라 사실상 일어나지 않는다. 나더라도 props 만 비운다.
            log.warn("props 직렬화 실패", ex);
            return null;
        }
    }

    /**
     * 어느 화면이었나. ★ 쿼리스트링과 fragment 를 잘라 낸다.
     *
     * 우리 화면은 지금 쿼리를 안 쓰지만, 초대 링크나 공유 링크가 붙는 순간
     * {@code /zzal?invite=...} 같은 것이 그대로 쌓이기 시작한다.
     */
    private String sanitizePath(String path) {
        if (path == null || path.isBlank()) return null;
        String p = path.trim();
        int cut = indexOfAny(p, '?', '#');
        if (cut >= 0) p = p.substring(0, cut);
        if (!p.startsWith("/")) return null;   // 절대 URL 이 path 자리로 들어온 경우
        return truncate(p, MAX_PATH);
    }

    /**
     * 어디서 들어왔나. ★ origin + path 만 남기고 <b>쿼리는 통째로 버린다.</b>
     *
     * 이게 이 파일에서 두 번째로 중요한 줄이다 — 검색·메일·SNS 에서 넘어온 referrer 의
     * 쿼리에는 검색어는 물론이고 이메일 주소와 로그인 토큰이 실제로 실려 온다.
     * 그 값을 우리가 원해서 받는 게 아니라, 안 자르면 그냥 들어와 쌓인다.
     */
    private String sanitizeReferrer(String referrer) {
        if (referrer == null || referrer.isBlank()) return null;
        try {
            URI uri = URI.create(referrer.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) return null;
            if (uri.getHost() == null) return null;

            StringBuilder sb = new StringBuilder(scheme).append("://").append(uri.getHost());
            if (uri.getPort() > 0) sb.append(':').append(uri.getPort());
            if (uri.getPath() != null) sb.append(uri.getPath());
            return truncate(sb.toString(), MAX_REFERRER);
        } catch (IllegalArgumentException ex) {
            // 주소 모양이 아니면 없는 것으로 본다.
            return null;
        }
    }

    /** 유입 출처. UTM 값에 안 나오는 글자는 지운다(따옴표·꺾쇠가 로그·화면으로 흘러가는 길을 막는다). */
    private String sanitizeSource(String source) {
        if (source == null || source.isBlank()) return null;
        String cleaned = SOURCE_SAFE.matcher(source.trim()).replaceAll("");
        return cleaned.isEmpty() ? null : truncate(cleaned, MAX_SOURCE);
    }

    /**
     * 기기 대분류.
     *
     * ★ User-Agent 원문은 <b>저장하지 않는다.</b> 원문은 브라우저·버전·기기 모델까지 들어 있어
     *   그 자체로 사람을 특정하는 지문이 된다(지인 10명 규모에서는 사실상 실명이다).
     *   우리가 알고 싶은 것은 "폰에서 막혔나 PC 에서 막혔나" 하나뿐이라, 두 글자면 충분하다.
     */
    private String deviceOf(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return null;
        String ua = userAgent.toLowerCase();
        boolean mobile = ua.contains("mobi") || ua.contains("android")
                || ua.contains("iphone") || ua.contains("ipad") || ua.contains("ipod");
        return mobile ? "mobile" : "desktop";
    }

    /** 브라우저가 말한 시각. 말이 안 되면 서버 도착 시각으로 대신한다. */
    private Instant occurredAt(Long ts, Instant now) {
        if (ts == null || ts <= 0) return now;
        Instant occurred;
        try {
            occurred = Instant.ofEpochMilli(ts);
        } catch (RuntimeException ex) {
            return now;
        }
        if (occurred.isAfter(now.plus(TS_FUTURE_LIMIT))) return now;
        if (occurred.isBefore(now.minus(TS_PAST_LIMIT))) return now;
        return occurred;
    }

    // ── 분당 상한 ──────────────────────────────────────────────────────────

    private boolean allowRate(String anonId) {
        long minute = System.currentTimeMillis() / 60_000L;
        if (rateWindows.size() >= MAX_TRACKED_CLIENTS) rateWindows.clear();

        RateWindow window = rateWindows.computeIfAbsent(anonId, k -> new RateWindow(minute));
        return window.hit(minute) <= MAX_REQUESTS_PER_MINUTE;
    }

    /** 한 익명번호의 "이번 분" 셈. 분이 바뀌면 0 부터 다시 센다. */
    private static final class RateWindow {
        private long minute;
        private int count;

        RateWindow(long minute) {
            this.minute = minute;
        }

        synchronized int hit(long now) {
            if (now != minute) {
                minute = now;
                count = 0;
            }
            return ++count;
        }
    }

    // ── 잔손질 ─────────────────────────────────────────────────────────────

    private static int indexOfAny(String s, char a, char b) {
        int i = s.indexOf(a);
        int j = s.indexOf(b);
        if (i < 0) return j;
        if (j < 0) return i;
        return Math.min(i, j);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
