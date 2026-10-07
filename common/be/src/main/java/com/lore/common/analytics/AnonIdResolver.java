package com.lore.common.analytics;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 브라우저마다 하나씩 붙는 익명 번호를 꺼내거나, 없으면 그 자리에서 새로 발급한다.
 *
 * <h3>★ 발급 API 를 따로 두지 않은 이유</h3>
 * "번호 받아오기 → 이벤트 보내기" 두 번 왕복이 되면 <b>첫 이벤트를 놓친다.</b>
 * 랜딩을 열자마자 나가 버리는 사람이야말로 가장 알고 싶은 사람인데, 그 사람은
 * 첫 왕복이 끝나기 전에 이미 떠나 있다. 그래서 첫 요청의 응답에 그냥 얹어 보낸다.
 *
 * <h3>★ 쿠키만 신뢰한다</h3>
 * 이 주소는 로그인 없이 누구나 부를 수 있으므로, 본문에 적힌 번호를 그대로 쓰면
 * 남의 번호로 아무 기록이나 심을 수 있다. 그래서 번호의 출처는 <b>쿠키 한 곳뿐</b>이다.
 *
 * <h3>속성이 {@code AuthCookies} 와 같은 이유</h3>
 * HttpOnly — 스크립트가 못 읽는다. 화면이 못 읽어도 브라우저가 알아서 실어 보내므로
 *            기능에는 아무 지장이 없고, XSS 로 번호가 통째로 새는 길만 막힌다.
 * SameSite=Lax — 남의 사이트에 우리 주소를 심어 놓고 방문자 번호를 만들어 내는 걸 막는다.
 * Secure — 운영은 HTTPS 전용. 로컬(http)에서는 꺼야 쿠키가 저장된다({@code app.cookie.secure}).
 */
@Component
public class AnonIdResolver {

    /**
     * 쿠키 이름.
     *
     * ★ zzal_ 접두어를 안 붙였다 — 로그인·가입 이벤트는 zzal 이 아니라 공통 화면(AuthModal)에서
     *   나오고, 앞으로 webtoon·piece-maker 도 같은 번호를 쓴다. 표 이름(zzal_event)은 이미
     *   그렇게 만들어져 있지만, 쿠키는 브라우저에 오래 남는 것이라 지금 이름을 맞춰 둔다.
     */
    public static final String COOKIE = "lore_anon_id";

    /**
     * 같은 번호를 <b>스크립트가 읽을 수 있게</b> 한 장 더 내려 주는 사본(2026-10-07).
     *
     * ★ 왜 — 쿠키가 지워지면 같은 사람이 새 번호로 세어진다. 화면이 옛 번호를 localStorage 에
     *   남겨 두었다가 가입·로그인 때 {@code from} 으로 알려 주면, 서버가 (옛 번호, 사용자) 를 한 줄 더
     *   이어 같은 사람으로 묶을 수 있다. 본 쿠키는 HttpOnly 라 화면이 그 번호를 알 길이 이것뿐이다.
     * ★ 이 사본은 <b>읽기 전용 표시</b>다 — 서버는 번호를 여전히 {@link #COOKIE} 에서만 꺼낸다.
     *   사본을 바꿔 넣어도 기록되는 번호는 안 바뀐다.
     */
    public static final String HINT_COOKIE = "lore_anon_hint";

    /**
     * 쿠키 수명 400일.
     *
     * ★ 이보다 길게 적어도 소용이 없다 — 크롬이 400일로 잘라 저장한다.
     *   짧게 잡으면 두 달 뒤에 돌아온 사람이 새 사람으로 세어져, 재방문을 영영 못 본다.
     */
    private static final Duration TTL = Duration.ofDays(400);

    /**
     * 번호의 생김새. 대시 없는 UUID(32자리 16진수).
     *
     * ★ 쿠키 값은 사용자가 마음대로 바꿔 넣을 수 있는 문자열이다. 모양을 검사하지 않으면
     *   40자 칸(anon_id)을 넘겨 저장이 통째로 실패하거나, 로그에 아무 문자열이나 섞여 들어온다.
     *   모양이 안 맞으면 없는 것으로 보고 새로 발급한다.
     */
    private static final Pattern SHAPE = Pattern.compile("^[0-9a-f]{32}$");

    private final boolean secure;

    public AnonIdResolver(@Value("${app.cookie.secure:true}") boolean secure) {
        this.secure = secure;
    }

    /**
     * 이 요청의 익명 번호. 쿠키에 쓸 만한 값이 있으면 그것, 없으면 새로 만들어 응답에 실어 보낸다.
     *
     * ★ 응답에 실어 보내기만 하고 표에는 따로 적지 않는다. 번호가 처음 등장한 시각은
     *   그 번호로 들어온 첫 이벤트가 이미 알려 준다.
     */
    public String resolve(HttpServletRequest request, HttpServletResponse response) {
        String existing = read(request, COOKIE);
        if (existing != null) return existing;

        String issued = UUID.randomUUID().toString().replace("-", "");
        addCookie(response, COOKIE, issued, true);
        return issued;
    }

    /**
     * 사본({@link #HINT_COOKIE})을 번호에 맞춰 둔다. 없거나 어긋날 때만 내려 보내므로 브라우저마다 한 번이다.
     *
     * ★ {@link #resolve} 에 넣지 않고 따로 둔 이유 — resolve 는 피스메이커 광고 랜딩도 함께 쓰고,
     *   그쪽은 "번호가 있으면 응답에 쿠키를 안 싣는다" 를 계약으로 둔다(AdLandingCookieTest).
     *   사본은 행동 기록 주소(AnalyticsController)에서만 맞춘다.
     */
    public void syncHint(HttpServletRequest request, HttpServletResponse response, String anonId) {
        if (!isWellFormed(anonId) || anonId.equals(read(request, HINT_COOKIE))) return;
        addCookie(response, HINT_COOKIE, anonId, false);
    }

    /** 번호 모양이 맞는가. 화면이 보낸 옛 번호({@code from})를 거를 때도 같은 기준을 쓴다. */
    public static boolean isWellFormed(String value) {
        return value != null && SHAPE.matcher(value).matches();
    }

    private void addCookie(HttpServletResponse response, String name, String value, boolean httpOnly) {
        response.addHeader("Set-Cookie", ResponseCookie.from(name, value)
                .httpOnly(httpOnly)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(TTL)
                .build()
                .toString());
    }

    /** 쿠키에서 꺼낸다. 없거나 모양이 안 맞으면 null. */
    private String read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (name.equals(c.getName()) && isWellFormed(c.getValue())) {
                return c.getValue();
            }
        }
        return null;
    }
}
