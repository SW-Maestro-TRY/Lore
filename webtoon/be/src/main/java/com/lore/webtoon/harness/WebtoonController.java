package com.lore.webtoon.harness;

import com.lore.webtoon.WebtoonApi;
import com.lore.webtoon.art.PageStore;
import com.lore.webtoon.credit.CreditGate;
import com.lore.webtoon.credit.GuestGate;
import com.lore.webtoon.usage.SpendGuard;
import com.lore.webtoon.work.WorkLedger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Webtoon 도메인 진입점.
 *
 * <h2>지금은 프록시 한 자리뿐이다</h2>
 *
 * {@code /api/webtoon/v1/**} 로 온 것을 생성 하네스(serve.py)의 {@code /api/**}
 * 로 그대로 넘긴다.
 *
 * <pre>
 *   POST /api/webtoon/v1/nh/create                -&gt; POST http://…:8800/api/nh/create
 *   GET  /api/webtoon/v1/nh/jobs/{id}             -&gt; GET  …/api/nh/jobs/{id}
 *   GET  /api/webtoon/v1/runs/{id}/result         -&gt; GET  …/api/runs/{id}/result
 *   POST /api/webtoon/v1/runs/{id}/scenes/3/regen -&gt; …/api/runs/{id}/scenes/3/regen
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
 * {@code POST /api/webtoon/v1/nh/create} 는 <b>여기서부터 실제로 돈이 나가는</b>
 * 유일한 자리다(실측 한 편 1,148원). 그래서 이 주소만 넘기기 전에 두 번
 * 멈춰 세운다 — 오늘 <b>전체</b> 몫이 남았는지({@link SpendGuard}), 그리고
 * 로그인 안 한 <b>이 사람</b>의 몫이 남았는지({@link GuestGate}). 나머지는
 * 그대로 흘러간다.
 */
@RestController
public class WebtoonController {

    private static final Logger log = LoggerFactory.getLogger(WebtoonController.class);

    /** 프론트가 부르는 접두사. 이 뒤가 하네스의 {@code /api} 뒤와 같다. */
    static final String PREFIX = WebtoonApi.V1;

    /** 이 주소만 지나가기 전에 한 번 멈춰 세운다 — 여기서부터 돈이 나간다. */
    static final String CREATE = PREFIX + "/nh/create";

    /** 진행 상황을 묻는 자리. 작품 번호가 여기 실려 오므로 지나가는 김에 적는다. */
    private static final Pattern JOB = Pattern.compile(
            Pattern.quote(PREFIX) + "/nh/jobs/([\\w.-]+)");

    /** 그림 한 장을 달라는 자리. S3 에 올라와 있으면 거기로 보낸다. */
    /** 둘러보기 목록. 여기서 비공개를 걷어낸다. */
    private static final Pattern LIST = Pattern.compile(Pattern.quote(PREFIX) + "/runs");

    private static final Pattern PAGE = Pattern.compile(
            Pattern.quote(PREFIX) + "/runs/([\\w.-]+)/page/(\\d+)");

    private final HarnessGateway gateway;
    private final SpendGuard guard;
    private final GuestGate guests;
    private final CreditGate credits;
    private final WorkLedger ledger;
    private final PageStore pages;
    /* 응답에서 작업 id 하나만 꺼내려고 쓴다. 스프링이 만들어 주는 빈이 없어서
       (앞서 주입받게 썼다가 서버가 안 떴다) 여기서 만든다. */
    private final ObjectMapper mapper = new ObjectMapper();

    public WebtoonController(HarnessGateway gateway, SpendGuard guard,
                             GuestGate guests, CreditGate credits, WorkLedger ledger,
                             PageStore pages) {
        this.gateway = gateway;
        this.guard = guard;
        this.guests = guests;
        this.credits = credits;
        this.ledger = ledger;
        this.pages = pages;
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
        boolean creating = HttpMethod.POST.equals(method)
                && CREATE.equals(request.getRequestURI());
        Long me = creating ? CreditGate.currentUser() : null;

        // 만들기만 먼저 확인한다 — 시작한 뒤에 막으면 이미 돈이 나간 뒤다.
        // 나머지(읽기·목록·편집)는 그냥 지나간다.
        if (creating) {
            // 전체 몫을 먼저 본다. 오늘 다 찼으면 로그인해도 못 만들므로,
            // 게스트에게 "로그인하면 됩니다" 라고 말하면 거짓말이 된다.
            String blocked = guard.whyBlocked();
            if (blocked == null && me == null) {
                // 게스트만 IP 로 센다. 로그인한 사람은 바로 아래에서 계정
                // 크레딧으로 내므로, 여기서 또 세면 로그인한 쪽이 더 막힌다.
                blocked = guests.useOrBlock(request);
                counted = blocked == null;      // 셌으면 실패했을 때 돌려줘야 한다
            }
            // 막힌 이유에 따라 코드가 다르다. **몫이 다 찬 것(429)과 값이
            // 모자란 것(402)은 다른 일이다** — 앞엣것은 기다리면 풀리고
            // 뒤엣것은 충전해야 풀린다. 화면이 "내일 다시 오세요" 를 띄울지
            // "충전하러 가기" 를 띄울지가 여기서 갈린다.
            int code = 429;
            if (blocked == null) {
                blocked = credits.whyBlocked(me);   // 로그인 안 했으면 null
                if (blocked != null) {
                    code = 402;
                }
            }
            if (blocked != null) {
                // 하네스가 사유를 한글로 적어 보내는 것과 **같은 모양**으로 답한다.
                // 화면(프로토타입에서 옮겨 온 것)이 그 모양만 읽어서, 여기서
                // 봉투를 씌우면 "알 수 없는 오류" 밖에 못 띄운다.
                return ResponseEntity.status(code)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(("{\"error\":\"" + blocked + "\"}")
                                .getBytes(StandardCharsets.UTF_8));
            }
        }

        /* 그림은 **S3 에 있으면 그리로 보낸다.**
         *
         * 하네스를 거치면 원본을 열어 폭을 줄여 내보내는데(그때마다 CPU 를
         * 쓴다), 이미 줄여서 올려 둔 것이 있으면 그럴 이유가 없다. 무엇보다
         * 하네스가 없는 서버에서도 그림이 보여야 한다 — 그것이 S3 로 옮긴
         * 이유다.
         *
         * 없으면 그냥 아래로 흘러가 예전처럼 하네스가 내보낸다. 한 번에
         * 갈아타지 않는다: 아직 안 올라간 옛 작품이 그대로 보여야 한다. */
        if (HttpMethod.GET.equals(method)) {
            Matcher page = PAGE.matcher(request.getRequestURI());
            if (page.matches()) {
                String runId = page.group(1);

                /* **비공개는 주인만 본다.** 지금까지는 목록에서 가려질 뿐이라
                   작품 번호만 알면 누구나 그림을 받을 수 있었다(실측). */
                if (!ledger.mayRead(runId, me())) {
                    return ResponseEntity.status(404)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"error\":\"그 장의 그림이 없습니다\"}"
                                    .getBytes(StandardCharsets.UTF_8));
                }

                String at = pages.urlOf(runId, Integer.parseInt(page.group(2)),
                                        widthOf(request.getQueryString()));
                if (at != null) {
                    return ResponseEntity.status(302).location(URI.create(at)).build();
                }
            }
        }

        byte[] body = request.getInputStream().readAllBytes();

        /* 하네스에 **몫은 여기서 이미 셌다**고 알린다.
         *
         * 안 알리면 하네스가 자기 uid 크레딧에서 또 받는다. 로그인한 사람은
         * 계정과 uid 두 곳에서 두 번 내고, 게스트는 위 {@link GuestGate} 의
         * 하루 몇 편과 uid 잔액에 **두 겹으로** 막힌다 — 화면은 「오늘 무료
         * 3편」이라 적는데 하네스가 「크레딧이 모자랍니다」로 튕길 수 있다.
         *
         * 이 표시를 하네스가 믿어도 되는 이유는 하네스가 밖에 안 열려 있기
         * 때문이다. 브라우저는 늘 이 스프링을 거치고, 하네스 주소는 서버
         * 안에서만 닿는다. 그 전제가 깨지면 이 표시부터 다시 봐야 한다. */
        HttpHeaders out = headers;
        if (me != null || counted) {
            out = new HttpHeaders();
            out.addAll(headers);
            if (me != null) {
                out.set(CreditGate.BILLED_HEADER, String.valueOf(credits.cost()));
            } else {
                out.set(GuestGate.GATED_HEADER, "1");
            }
        }

        ResponseEntity<byte[]> answer = gateway.forward(
                method, harnessPath(request.getRequestURI()),
                request.getQueryString(), body, out);

        boolean ok = answer.getStatusCode().is2xxSuccessful();

        // 지나가는 김에 **누가 만든 것인지** 적어 둔다. 하네스는 계정을 모르고
        // (게스트도 만들 수 있어서 알 수가 없다) 계정을 아는 것은 여기뿐이다.
        // 적는 일은 만들기를 막지 않는다 — WorkLedger 안에서 다 삼킨다.
        if (ok) {
            if (creating) {
                ledger.started(answer.getBody(), me, uidOf(body));
            } else {
                Matcher job = JOB.matcher(request.getRequestURI());
                if (job.matches()) {
                    ledger.progressed(job.group(1), answer.getBody(),
                                      CreditGate.currentUser());
                }
            }
        }

        // 시작조차 못 했으면 방금 센 한 편을 도로 물린다. 안 그러면 아무것도
        // 못 만든 사람에게 "오늘 2편 다 쓰셨어요" 가 뜬다 — 만든 적이 없으니
        // 거짓말이고, 로그인해도 오늘은 안 되는 줄 알게 된다.
        if (counted && !ok) {
            guests.refund(request);
        }

        // 작업이 만들어진 **뒤에** 받는다. 먼저 받으면 만들기가 실패했을 때
        // 낸 것만 사라진다(하네스가 같은 순서를 쓴다 — 이슈 #16).
        if (creating && ok && me != null) {
            credits.charge(me, jobIdOf(answer.getBody()));
        }

        /* **비공개로 내린 작품을 둘러보기에서 뺀다.**
         *
         * 공개 여부가 두 곳에 갈려 있었다 — 마이페이지 스위치는 스프링 DB
         * (webtoon_work.is_public)에 쓰는데, 둘러보기 목록은 하네스가 만들고
         * 하네스는 자기 파일(landing/data/hidden_runs.json)만 본다. 그래서
         * 비공개로 내려도 목록에 그대로 남았다 — 제목·장르·캐릭터 이름이
         * 다 보이고 그림만 막혔다(실측으로 확인).
         *
         * 절반만 숨겨지는 것이 제일 나쁘다. 지나가는 길에 여기서 거른다:
         * 진실은 DB 에 있고(#241 이 그리로 옮겼다) 그것을 아는 곳이 여기다.
         */
        if (ok && LIST.matcher(request.getRequestURI()).matches()) {
            return hidePrivate(answer);
        }
        return answer;
    }

    /** 목록 응답에서 비공개 작품을 걷어낸다. 못 읽으면 원본 그대로 — 목록이
     *  통째로 안 뜨는 것보다는 덜 걸러진 편이 낫다. */
    private ResponseEntity<byte[]> hidePrivate(ResponseEntity<byte[]> answer) {
        byte[] body = answer.getBody();
        if (body == null || body.length == 0) {
            return answer;
        }
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode runs = root.path("runs");
            if (!runs.isArray()) {
                return answer;
            }
            ArrayNode kept = mapper.createArrayNode();
            for (JsonNode one : runs) {
                String runId = one.path("run_id").asText("");
                /* **내 것이어도 뺀다.** 둘러보기는 공개 갤러리라, 비공개로
                   내린 것이 주인에게만 보이면 "내렸는데 왜 아직 있지" 가 된다.
                   자기 작품을 보는 자리는 마이페이지다 — 거기서는 비공개도
                   다 보인다(MyWebtoonService). */
                if (runId.isBlank() || ledger.isPublic(runId)) {
                    kept.add(one);
                }
            }
            ((ObjectNode) root).set("runs", kept);
            return ResponseEntity.status(answer.getStatusCode())
                    .headers(stripLength(answer.getHeaders()))
                    .body(mapper.writeValueAsBytes(root));
        } catch (Exception e) {              // noqa: 못 걸러도 목록은 내보낸다
            log.warn("둘러보기에서 비공개를 못 걸렀습니다", e);
            return answer;
        }
    }

    /** 본문 길이가 바뀌었으므로 옛 Content-Length 를 뗀다. */
    private static HttpHeaders stripLength(HttpHeaders from) {
        HttpHeaders out = new HttpHeaders();
        from.forEach((k, v) -> {
            if (!HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(k)) {
                out.addAll(k, v);
            }
        });
        return out;
    }

    /**
     * `?w=` 로 달라고 한 폭. 없으면 본문 크기(1080).
     *
     * 올려 둔 폭과 <b>정확히 같은 값일 때만</b> S3 로 보낸다(PageStore 가 그렇게
     * 찾는다). 어중간한 폭을 달라고 하면 하네스가 그 자리에서 줄여 준다 —
     * 아무 폭이나 S3 에 만들어 두면 끝이 없다.
     */
    private static int widthOf(String query) {
        if (query == null) {
            return 1080;
        }
        Matcher m = WIDTH.matcher(query);
        return m.find() ? Integer.parseInt(m.group(1)) : 1080;
    }

    private static final Pattern WIDTH = Pattern.compile("(?:^|&)w=(\\d{1,4})(?:&|$)");

    /** 지금 로그인한 사람. 안 했으면 null. */
    private static Long me() {
        return CreditGate.currentUser();
    }

    /**
     * 만들기 요청에서 브라우저 값만 꺼낸다.
     *
     * 이 클래스는 본문을 해석하지 않는 것이 원칙이지만(위 proxy 참고), 게스트가
     * 만든 작품의 주인을 적으려면 이 값이 있어야 한다 — 게스트에게는 계정이
     * 없고 이것 말고 가리킬 것이 없다. 못 읽으면 안 적고 넘어간다.
     */
    private String uidOf(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            JsonNode uid = mapper.readTree(body).path("uid");
            return uid.isTextual() ? uid.asText() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 하네스가 돌려준 것에서 작업 id 만 꺼낸다.
     *
     * 이 클래스는 원래 본문을 <b>해석하지 않는다</b>(위 proxy 참고). 여기만
     * 예외인 이유는 크레딧을 "무엇에 대해" 받았는지 적어야 하기 때문이다 —
     * 그 값이 없으면 같은 사람이 두 번 눌렀을 때 두 번 빠지고, 돌려줄 때도
     * 무엇을 돌려주는지 알 수 없다.
     *
     * 못 읽어도 던지지 않는다. 이미 만들어진 작업을 오류로 만들 수는 없다.
     */
    private String jobIdOf(byte[] answer) {
        if (answer == null || answer.length == 0) {
            return null;
        }
        try {
            JsonNode id = mapper.readTree(answer).path("id");
            return id.isTextual() ? id.asText() : null;
        } catch (IOException e) {
            log.warn("만들기 응답에서 작업 id 를 못 읽어 크레딧을 못 받았습니다", e);
            return null;
        }
    }

    /**
     * 프론트가 부른 주소 -&gt; 하네스 주소.
     *
     * {@code /api/webtoon/v1/nh/jobs/x} -&gt; {@code /api/nh/jobs/x}
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
