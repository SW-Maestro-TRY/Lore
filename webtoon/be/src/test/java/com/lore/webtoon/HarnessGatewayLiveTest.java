package com.lore.webtoon;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 진짜 하네스에 대고 넘겨 본다.
 *
 * <b>하네스가 안 떠 있으면 통째로 건너뛴다.</b> CI 에는 파이썬 서버가 없고,
 * 그것 때문에 빌드가 빨개지면 사람들이 이 검사를 지운다 — 그러면 정작
 * 확인해야 할 때 확인할 것이 없다. 로컬에서 서버를 띄워 놓고 돌리면 실제로
 * 확인된다.
 *
 * <pre>
 *   cd haeun/landing &amp;&amp; python3 serve.py            # 8800
 *   ./gradlew test --tests '*HarnessGatewayLiveTest'
 * </pre>
 */
class HarnessGatewayLiveTest {

    private static final HarnessProperties PROPS = new HarnessProperties();
    private static HarnessGateway gateway;

    @BeforeAll
    static void 하네스가_떠_있을_때만() {
        URI base = URI.create(PROPS.getBaseUrl());
        boolean up;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(base.getHost(), base.getPort()), 400);
            up = true;
        } catch (Exception e) {
            up = false;
        }
        Assumptions.assumeTrue(up, "하네스(" + PROPS.getBaseUrl() + ")가 안 떠 있어 건너뜁니다");
        gateway = new HarnessGateway(PROPS);
    }

    private ResponseEntity<byte[]> get(String path, String query) {
        return gateway.forward(HttpMethod.GET, path, query, null, new HttpHeaders());
    }

    @Test
    @DisplayName("JSON 을 그대로 가져온다")
    void json() {
        ResponseEntity<byte[]> r = get("/api/config", null);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getHeaders().getContentType()).isNotNull();
        assertThat(r.getHeaders().getContentType().toString()).contains("application/json");
        assertThat(new String(r.getBody(), java.nio.charset.StandardCharsets.UTF_8))
                .startsWith("{");
    }

    @Test
    @DisplayName("그림도 그대로 가져온다 — 바이트를 안 건드린다")
    void image() {
        // 어떤 작품이 있는지 모르니 목록에서 하나 집는다.
        ResponseEntity<byte[]> runs = get("/api/runs", null);
        Assumptions.assumeTrue(runs.getStatusCode().value() == 200, "작품 목록을 못 읽었습니다");
        String body = new String(runs.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("\"run_id\"\\s*:\\s*\"([\\w.-]+)\"").matcher(body);
        Assumptions.assumeTrue(m.find(), "완성된 작품이 하나도 없어 건너뜁니다");

        ResponseEntity<byte[]> img = get("/api/runs/" + m.group(1) + "/page/1", "w=200");
        assertThat(img.getStatusCode().value()).isEqualTo(200);
        assertThat(img.getHeaders().getContentType()).isNotNull();
        assertThat(img.getHeaders().getContentType().getType()).isEqualTo("image");
        // 실제 그림이 왔는가 (JPEG/PNG 머리)
        byte[] b = img.getBody();
        assertThat(b.length).isGreaterThan(1000);
        boolean jpeg = (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8;
        boolean png = (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
        assertThat(jpeg || png).as("JPEG 나 PNG 머리여야 한다").isTrue();
    }

    @Test
    @DisplayName("없는 작품의 404 와 한글 사유를 그대로 전한다")
    void notFound() {
        // run id 는 [\w.-]+ 라야 그 라우트에 걸린다 — 한글을 넣으면 라우트를
        // 아예 못 만나서 "없는 주소입니다" 라는 다른 404 가 온다(그것도 404 라
        // 무심코 보면 통과처럼 보인다). 있을 법한 모양으로 없는 것을 부른다.
        ResponseEntity<byte[]> r = get("/api/runs/20990101T000000-nope/result", null);
        assertThat(r.getStatusCode().value()).isEqualTo(404);
        // 사유가 한글 그대로 와야 한다 — 여기서 깨지면 화면이 "알 수 없는
        // 오류" 밖에 못 띄운다.
        assertThat(new String(r.getBody(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("찾지 못했습니다");
    }

    @Test
    @DisplayName("하네스가 안 떠 있으면 무엇을 켜야 하는지 말해 준다")
    void 하네스가_없을_때() {
        HarnessProperties nowhere = new HarnessProperties();
        nowhere.setBaseUrl("http://127.0.0.1:9");      // 아무도 안 듣는 포트
        ResponseEntity<byte[]> r = new HarnessGateway(nowhere)
                .forward(HttpMethod.GET, "/api/config", null, null, new HttpHeaders());
        assertThat(r.getStatusCode().value()).isEqualTo(502);
        assertThat(r.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(new String(r.getBody(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("serve.py");
    }
}
