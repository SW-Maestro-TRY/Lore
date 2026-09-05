package com.lore.webtoon;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

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
 * <h2>아직 안 하는 것</h2>
 *
 * 인증 · 크레딧 차감 · 내 작품 목록은 자바가 맡아야 할 일이지만 지금은
 * 하네스 쪽 구현이 그대로 돈다(파일 기반). DB 로 옮길 때 이 클래스 옆에
 * 진짜 컨트롤러를 만들고 그 주소만 더 구체적으로 매핑하면 된다 — 스프링은
 * 더 구체적인 매핑을 먼저 고르므로 프록시보다 앞선다.
 */
@RestController
public class WebtoonController {

    /** 프론트가 부르는 접두사. 이 뒤가 하네스의 {@code /api} 뒤와 같다. */
    static final String PREFIX = "/api/webtoon";

    private final HarnessGateway gateway;

    public WebtoonController(HarnessGateway gateway) {
        this.gateway = gateway;
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
        byte[] body = request.getInputStream().readAllBytes();
        return gateway.forward(method, harnessPath(request.getRequestURI()),
                               request.getQueryString(), body, headers);
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
