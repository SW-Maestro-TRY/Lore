package com.lore.webtoon;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Webtoon 도메인 진입점.
 *
 * <h2>지금은 프록시 한 자리뿐이다</h2>
 *
 * {@code /api/webtoon/**} 로 온 것을 생성 하네스(serve.py)의 {@code /api/**}
 * 로 그대로 넘긴다.
 *
 * <pre>
 *   POST /api/webtoon/nh/create                -&gt; POST http://…:8800/api/nh/create
 *   GET  /api/webtoon/nh/jobs/{id}             -&gt; GET  …/api/nh/jobs/{id}
 *   GET  /api/webtoon/runs/{id}/result         -&gt; GET  …/api/runs/{id}/result
 *   POST /api/webtoon/runs/{id}/scenes/3/regen -&gt; …/api/runs/{id}/scenes/3/regen
 * </pre>
 *
 * 주소를 하나하나 안 적는 이유는, 화면이 부르는 주소가 아직 움직이고 있어서다.
 * 여기에 목록을 박아 두면 프로토타입에 주소가 하나 늘 때마다 자바도 같이
 * 고쳐야 하고, 빠뜨리면 그 화면만 조용히 404 가 된다. 넘길 것을 고르는 일은
 * <b>자바가 뜻을 갖고 판단할 것이 생겼을 때</b> 시작한다.
 *
 * <h2>왜 {@code /api/webtoon} 아래인가</h2>
 *
 * 공용 API 와 섞이지 않게 도메인 이름을 앞에 둔다({@code /api/v1/uploads} 처럼).
 * 프론트는 상대경로로 부르고, 운영에서는 CloudFront 가 {@code /api/*} 만
 * 백엔드로 보낸다 — 그래서 CORS 가 없다.
 *
 * <h2>딱 하나, 만들기는 그냥 안 지나간다</h2>
 *
 * {@code POST /api/webtoon/nh/create} 는 <b>여기서부터 실제로 돈이 나가는</b>
 * 유일한 자리다(실측 한 편 1,148원). 그래서 이 주소만 넘기기 전에 두 번
 * 멈춰 세운다 — 오늘 <b>전체</b> 몫이 남았는지({@link SpendGuard}), 그리고
 * 로그인 안 한 <b>이 사람</b>의 몫이 남았는지({@link GuestGate}). 나머지는
 * 그대로 흘러간다.
 */
@RestController
public class WebtoonController {

    /** 프론트가 부르는 접두사. 이 뒤가 하네스의 {@code /api} 뒤와 같다. */
    static final String PREFIX = "/api/webtoon";

    /** 이 주소만 지나가기 전에 한 번 멈춰 세운다 — 여기서부터 돈이 나간다. */
    static final String CREATE = PREFIX + "/nh/create";

    private final HarnessGateway gateway;
    private final SpendGuard guard;
    private final GuestGate guests;

    public WebtoonController(HarnessGateway gateway, SpendGuard guard, GuestGate guests) {
        this.gateway = gateway;
        this.guard = guard;
        this.guests = guests;
    }

    /**
     * 본문은 {@code @RequestBody} 로 안 받는다. 그러면 스프링이 Content-Type
     * 을 보고 어떤 변환기를 쓸지 정하려 드는데, 여기로 오는 것은 JSON 도
     * 있고 본문이 아예 없는 GET 도 있어서 그 협상에서 415 로 튕기는 경우가
     * 생긴다. 우리는 내용을 <b>해석하지 않고 옮기기만</b> 하므로 스트림에서
     * 바로 읽는 편이 맞다.
     */
    @RequestMapping(PREFIX + "/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest request,
                                        @RequestHeader HttpHeaders headers) throws IOException {
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        boolean counted = false;

        // 만들기만 먼저 확인한다 — 시작한 뒤에 막으면 이미 돈이 나간 뒤다.
        // 나머지(읽기·목록·편집)는 그냥 지나간다.
        if (HttpMethod.POST.equals(method) && CREATE.equals(request.getRequestURI())) {
            // 전체 몫을 먼저 본다. 오늘 다 찼으면 로그인해도 못 만들므로,
            // 게스트에게 "로그인하면 됩니다" 라고 말하면 거짓말이 된다.
            String blocked = guard.whyBlocked();
            if (blocked == null) {
                blocked = guests.useOrBlock(request);
                counted = blocked == null;      // 셌으면 실패했을 때 돌려줘야 한다
            }
            if (blocked != null) {
                // 하네스가 사유를 한글로 적어 보내는 것과 **같은 모양**으로 답한다.
                // 화면(프로토타입에서 옮겨 온 것)이 그 모양만 읽어서, 여기서
                // 봉투를 씌우면 "알 수 없는 오류" 밖에 못 띄운다.
                return ResponseEntity.status(429)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(("{\"error\":\"" + blocked + "\"}")
                                .getBytes(StandardCharsets.UTF_8));
            }
        }

        byte[] body = request.getInputStream().readAllBytes();
        ResponseEntity<byte[]> answer = gateway.forward(
                method, harnessPath(request.getRequestURI()),
                request.getQueryString(), body, headers);

        // 시작조차 못 했으면 방금 센 한 편을 도로 물린다. 안 그러면 아무것도
        // 못 만든 사람에게 "오늘 2편 다 쓰셨어요" 가 뜬다 — 만든 적이 없으니
        // 거짓말이고, 로그인해도 오늘은 안 되는 줄 알게 된다.
        if (counted && !answer.getStatusCode().is2xxSuccessful()) {
            guests.refund(request);
        }
        return answer;
    }

    /**
     * 프론트가 부른 주소 -&gt; 하네스 주소.
     *
     * {@code /api/webtoon/nh/jobs/x} -&gt; {@code /api/nh/jobs/x}
     *
     * 접두사만 갈아 끼운다. 뒤는 손대지 않는다 — 하네스가 쓰는 경로 규칙을
     * 여기서 알 필요가 없고, 알려고 하면 양쪽이 어긋나는 자리가 하나 더 는다.
     */
    static String harnessPath(String requestUri) {
        String tail = requestUri.startsWith(PREFIX)
                ? requestUri.substring(PREFIX.length())
                : requestUri;
        return "/api" + tail;
    }
}
