package com.lore.webtoon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;
import java.util.Set;

/**
 * 생성 하네스(serve.py)로 요청을 그대로 넘기고 응답을 그대로 돌려준다.
 *
 * <h2>여기서 응답을 감싸지 않는다</h2>
 *
 * 다른 API 는 {@code ApiResponse} 로 감싸는 것이 이 저장소의 규약인데,
 * <b>이 자리만 예외다.</b> 넘어오는 것이 두 가지라서다.
 *
 * <ul>
 *   <li>하네스가 만든 JSON — 프론트(프로토타입에서 옮겨 온 화면)가 이미 그
 *       모양 그대로 읽는다. 여기서 한 겹 씌우면 화면을 전부 고쳐야 하고,
 *       그러면 원본 프로토타입과 이식본이 서로 다른 응답을 읽게 된다.</li>
 *   <li><b>이미지</b> — 페이지 그림 · 시트 · 한 편으로 이어 붙인 것. JSON 이
 *       아니라 바이트다. 감쌀 수가 없다.</li>
 * </ul>
 *
 * 그래서 이 클래스는 상태 · 헤더 · 본문을 <b>안 건드리고</b> 옮긴다.
 * 자바가 뜻을 갖고 판단하는 API(인증 · 크레딧 · 내 작품)는 나중에 별도
 * 컨트롤러로 만들고 거기서는 규약대로 감싼다.
 *
 * <h2>지금은 바이트를 다 읽어서 넘긴다</h2>
 *
 * 한 편으로 이어 붙인 그림이 15MB 쯤 된다. 스트리밍이 아니라 통째로 읽어
 * 넘기므로 그만큼 힙을 쓴다 — 사람이 몇 명 안 되는 지금은 이게 단순해서
 * 낫고, 동시 사용자가 늘면 그때 스트리밍으로 바꾼다. 바꾸는 자리는 이
 * 클래스 하나다.
 */
@Component
public class HarnessGateway {

    private static final Logger log = LoggerFactory.getLogger(HarnessGateway.class);

    /**
     * 넘기지 않는 헤더.
     *
     * {@code Host} 는 이쪽 주소라 그대로 보내면 하네스가 딴 데를 가리킨다.
     * 길이 · 인코딩 관련 헤더는 본문을 다시 실으면서 값이 달라지므로,
     * 우리가 적지 않고 클라이언트가 새로 계산하게 둔다.
     */
    private static final Set<String> DROP = Set.of(
            "host", "connection", "content-length", "transfer-encoding",
            "accept-encoding", "upgrade", "keep-alive", "expect");

    private final RestClient client;

    public HarnessGateway(HarnessProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofSeconds(5));
        factory.setReadTimeout(props.getTimeout());
        this.client = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * @param method 원래 요청의 메서드
     * @param path   하네스 기준 경로. 반드시 {@code /api/} 로 시작한다
     * @param query  물음표 뒤. 없으면 {@code null}
     * @param body   요청 본문. 없으면 {@code null}
     * @param headers 원래 요청 헤더 (위 DROP 에 든 것은 빼고 넘긴다)
     */
    public ResponseEntity<byte[]> forward(HttpMethod method, String path, String query,
                                          byte[] body, HttpHeaders headers) {
        URI uri = URI.create(path + (query == null || query.isBlank() ? "" : "?" + query));

        RestClient.RequestBodySpec spec = client.method(method).uri(uri);
        headers.forEach((name, values) -> {
            if (!DROP.contains(name.toLowerCase())) {
                spec.header(name, values.toArray(new String[0]));
            }
        });
        if (body != null && body.length > 0) {
            spec.body(body);
        }

        try {
            return spec
                    // 4xx·5xx 도 그대로 프론트에 전한다. 하네스가 사유를 한글로
                    // 적어 보내는데(예: "크레딧이 모자랍니다") 여기서 삼키면
                    // 화면이 "알 수 없는 오류" 밖에 못 띄운다.
                    .exchange((req, res) -> ResponseEntity
                            .status(res.getStatusCode())
                            .headers(copyOut(res.getHeaders()))
                            .body(res.getBody().readAllBytes()), false);
        } catch (ResourceAccessException e) {
            // 하네스가 안 떠 있는 것이 가장 흔하다. 그 사실을 그대로 말한다 —
            // 502 만 던지면 받는 사람이 무엇을 켜야 하는지 알 수 없다.
            log.warn("하네스에 못 닿았습니다: {} {}", method, uri, e);
            return ResponseEntity.status(502)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(("{\"error\":\"웹툰 생성 서버에 닿지 못했습니다. "
                            + "haeun/landing/serve.py 가 떠 있는지 확인해 주세요.\"}")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /** 응답 헤더 중 넘겨도 되는 것만. 길이는 본문을 다시 실으면서 새로 계산된다. */
    private HttpHeaders copyOut(HttpHeaders from) {
        HttpHeaders out = new HttpHeaders();
        from.forEach((name, values) -> {
            if (!DROP.contains(name.toLowerCase())) {
                out.put(name, List.copyOf(values));
            }
        });
        return out;
    }
}
