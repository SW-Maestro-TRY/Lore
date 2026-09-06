package com.lore.webtoon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.webtoon.story.StoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 로그인한 사람의 웹툰. 계정과 브라우저를 이어 두고, 그 브라우저들이 만든 것을 모은다.
 *
 * 왜 이런 모양인지는 {@link BrowserLink} 머리 주석에 있다.
 */
@Service
public class MyWebtoonService {

    private static final Logger log = LoggerFactory.getLogger(MyWebtoonService.class);

    /** 하네스가 들고 다니는 uid 의 생김새. 프론트가 만드는 값(`u` + 36진수)보다 넉넉히 잡는다. */
    private static final int UID_MAX = 64;

    private final BrowserLinkRepository links;
    private final WorkLedger ledger;
    private final PageStore pages;
    private final StoryStore stories;
    private final HarnessGateway gateway;

    /**
     * 하네스가 준 JSON 을 읽을 때만 쓴다.
     *
     * 스프링이 주는 것을 받지 않고 직접 만든다 — 이 앱에는 {@code ObjectMapper}
     * 빈이 없어서 주입을 걸면 <b>서버가 아예 안 뜬다</b>(실제로 그랬다).
     * 웹 조각 테스트에서는 Jackson 자동설정이 같이 떠서 안 드러났다.
     * 여기서 하는 일은 작은 응답 하나를 읽는 것뿐이라 앱 공용 설정이 필요 없다.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    public MyWebtoonService(BrowserLinkRepository links, HarnessGateway gateway,
                            WorkLedger ledger, PageStore pages, StoryStore stories) {
        this.links = links;
        this.gateway = gateway;
        this.ledger = ledger;
        this.pages = pages;
        this.stories = stories;
    }

    /**
     * 이 브라우저를 내 계정에 잇는다. 같은 짝이 이미 있으면 아무 일도 안 한다.
     *
     * 로그인할 때마다 부른다 — 기기를 바꾸면 uid 가 새로 생기므로, 한 번만
     * 잇는 것으로는 두 번째 기기가 영영 안 붙는다.
     *
     * @return 이번에 새로 이었으면 true
     */
    @Transactional
    public boolean link(Long userId, String browserUid) {
        String uid = normalize(browserUid);
        if (uid.isEmpty()) {
            return false;                       // 값이 없으면 그냥 넘어간다 — 로그인을 막을 일이 아니다
        }
        if (links.existsByUserIdAndBrowserUid(userId, uid)) {
            return false;
        }
        links.save(BrowserLink.of(userId, uid, Instant.now()));

        /* 이 브라우저가 **이 표가 생기기 전에** 만들어 둔 작품을 옮겨 담는다.
         * 안 하면 로그인한 사람에게 옛 작품이 DB 쪽에서는 안 보인다 — 지금은
         * 하네스 쪽 길이 아직 살아 있어 목록에 뜨지만, 하네스가 없는 실서버에서
         * 는 그대로 사라진다.
         *
         * 로그인할 때 하는 이유: 그때가 "이 브라우저의 것" 을 처음 아는 순간이고,
         * 한 번만 해도 되는 일이다. 실패해도 로그인은 안 막는다. */
        try {
            List<Map<String, Object>> already = runsOf(uid);
            if (already != null && !already.isEmpty()) {
                List<String> ids = already.stream()
                        .map(run -> String.valueOf(run.get("run_id")))
                        .filter(id -> !"null".equals(id))
                        .toList();
                ledger.moveIn(Map.of(uid, ids));
            }
        } catch (RuntimeException e) {
            log.warn("옛 작품을 옮겨 담지 못했습니다 (uid={})", uid, e);
        }
        return true;
    }

    /**
     * 내 계정에 이어진 브라우저들이 만든 작품 전부.
     *
     * 하네스에 uid 마다 한 번씩 묻고 합친다. 같은 작품이 두 uid 에서 나올 일은
     * 없지만(만든 브라우저는 하나다), 한 번 더 확인하는 값이 싸므로 run_id 로
     * 겹치는 것을 걸러 준다.
     *
     * 한 uid 를 못 읽어도 나머지는 준다 — 기기 하나 때문에 목록 전체가
     * 사라지는 것이 제일 나쁘다.
     *
     * <b>다만 하나도 못 읽었으면 빈 목록을 주지 않고 실패로 답한다.</b> 빈
     * 목록은 "작품이 없다" 는 뜻인데, 못 읽은 것은 그 말이 아니다. 둘을
     * 뭉개면 하네스가 죽어 있을 때 화면이 <b>"아직 만든 웹툰이 없어요"</b> 라고
     * 말한다 — 만든 사람에게 그건 작품이 사라졌다는 소리로 읽힌다.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> myRuns(Long userId) {
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        List<BrowserLink> mine = links.findByUserId(userId);
        int failed = 0;
        for (BrowserLink one : mine) {
            List<Map<String, Object>> got = runsOf(one.getBrowserUid());
            if (got == null) {
                failed++;
                continue;
            }
            for (Map<String, Object> run : got) {
                Object id = run.get("run_id");
                if (id != null) {
                    merged.putIfAbsent(String.valueOf(id), run);
                }
            }
        }

        /* **DB 가 아는 것도 합친다.**
         *
         * 위는 하네스에게 "이 브라우저가 만든 것" 을 묻는 길인데, 하네스가 그
         * 답을 파일 두 개를 이어 붙여 만든다(landing/ownership.py) — 그 파일이
         * 없거나 어긋나면 내 작품이 조용히 빠진다. DB 는 만들 때 직접 적어 둔
         * 것이라 그럴 일이 없다.
         *
         * 둘을 합치는 이유: 지금 옮겨 가는 중이라 어느 한쪽만 아는 작품이
         * 양쪽에 다 있다. DB 에만 있는 것은 여기서 얹고, 하네스에만 있는 것은
         * 위에서 이미 들어왔다. 한 번에 갈아타지 않는다 — 갈아타다 빠지면
         * 만든 사람에게는 작품이 사라진 것으로 보인다. */
        for (String runId : ledger.runIdsOf(userId)) {
            if (runId == null || merged.containsKey(runId)) {
                continue;
            }
            Map<String, Object> card = cardOf(runId);
            if (card != null) {
                merged.put(runId, card);
            }
        }

        // 하네스를 하나도 못 읽었고 DB 도 비었을 때만 실패로 답한다. DB 에
        // 있는 것이라도 보여줄 수 있으면 그게 낫다.
        if (!mine.isEmpty() && failed == mine.size() && merged.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "작품 목록을 가져오지 못했습니다");
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * 작품 하나의 카드. 내용(제목·표지·장 수)은 여전히 하네스가 안다 — DB 가
     * 아는 것은 <b>누구 것인가</b> 뿐이다.
     *
     * @return 못 읽으면 {@code null}. 그 한 편만 빠지고 나머지는 보여준다
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> cardOf(String runId) {
        /* **DB 가 아는 것으로 먼저 만든다.** 제목·줄거리가 DB 에 있으면 하네스
           폴더가 없어도 카드를 그릴 수 있다 — 그게 그 폴더를 작업대로 만드는
           일의 전부다. 표지와 장 수는 그림 쪽(webtoon_page)이 안다. */
        Map<String, Object> fromDb = cardFromDb(runId);
        if (fromDb != null) {
            return fromDb;
        }

        ResponseEntity<byte[]> res = gateway.forward(
                HttpMethod.GET, "/api/runs/" + runId + "/result", null, null, new HttpHeaders());
        if (!res.getStatusCode().is2xxSuccessful() || res.getBody() == null) {
            return null;
        }
        try {
            Map<String, Object> got = mapper.readValue(res.getBody(), Map.class);
            if (got.get("run_id") == null) {
                return null;
            }
            List<Map<String, Object>> pages =
                    (List<Map<String, Object>>) got.getOrDefault("pages", List.of());
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("run_id", got.get("run_id"));
            card.put("character", got.getOrDefault("character", ""));
            card.put("title", got.getOrDefault("title", ""));
            card.put("genre", got.getOrDefault("genre", ""));
            card.put("style_label", got.getOrDefault("style_label", ""));
            card.put("episodes", List.of(1));
            card.put("cover_episode", 1);
            card.put("cover_page", pages.isEmpty() ? null : pages.get(0).get("no"));
            card.put("page_count", pages.size());
            return card;
        } catch (IOException e) {
            log.warn("작품 하나를 읽지 못했습니다 (run={})", runId, e);
            return null;
        }
    }

    /**
     * DB 만으로 만든 카드. 아직 안 옮겨 온 작품이면 {@code null} — 그때는
     * 위에서 하네스에게 묻는다.
     */
    private Map<String, Object> cardFromDb(String runId) {
        var story = stories.chosenOf(runId).orElse(null);
        var pageNos = pages.pageNumbersOf(runId);
        if (story == null || pageNos.isEmpty()) {
            return null;
        }
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("run_id", runId);
        card.put("character", "");          // 캐릭터 이름은 아직 DB 에 없다 (#243)
        card.put("title", story.getTitle() == null ? "" : story.getTitle());
        card.put("genre", story.getGenre() == null ? "" : story.getGenre());
        card.put("style_label", "");
        card.put("episodes", List.of(1));
        card.put("cover_episode", 1);
        card.put("cover_page", pageNos.get(0));
        card.put("page_count", pageNos.size());
        return card;
    }

    /**
     * 이 작품을 둘러보기에 걸거나 내린다.
     *
     * <h2>왜 프록시로 안 넘기고 여기서 하는가</h2>
     *
     * 하네스의 같은 주소는 <b>하네스 자기 계정 세션</b>을 본다. 웹툰 탭은 앱
     * 계정(JWT)으로 로그인하므로 그 세션이 없다 — 그대로 넘기면 눌러도 늘
     * 401 이었다(실제로 그랬다).
     *
     * 그래서 여기서 <b>내 계정에 이어진 브라우저가 만든 것인지</b> 먼저 보고,
     * 맞으면 그 uid 를 실어 하네스에 넘긴다. 하네스도 uid 가 만든 이의 것인지
     * 한 번 더 본다 — 그 주소를 직접 부를 수도 있어서 양쪽이 다 본다.
     *
     * @return 바뀐 뒤의 공개 여부
     * @throws BusinessException 내 작품이 아니거나 하네스가 못 바꿨을 때
     */
    /* **읽기 전용이면 안 된다.** 예전에는 하네스로 넘기기만 해서 읽기 전용이
       맞았는데, 지금은 공개 여부와 그림 자리를 DB 에 쓴다. 읽기 전용 트랜잭션은
       쓴 것을 예외 없이 **조용히 버린다**(Hibernate 가 flush 를 안 한다) —
       실제로 그랬다: S3 의 그림 18개는 비공개 자리로 옮겨졌는데 DB 는 그대로라,
       화면은 "공개" 라고 말하면서 그림은 아무도 못 보는 상태가 됐다. 응답은
       성공이었다. */
    @Transactional
    public boolean setVisibility(Long userId, String runId, boolean isPublic) {
        // 주인 확인을 **DB 로도** 한다. 아래 하네스 쪽 길은 파일 두 개를 이어
        // 붙인 것이라, 그 파일이 없으면(하네스가 죽었거나 옮겨 간 뒤) 내 작품인데도
        // 못 바꾼다.
        String owner = ownerUidOf(userId, runId);
        if (owner == null && !ledger.mayChange(runId, userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "내가 만든 작품만 바꿀 수 있습니다");
        }

        if (owner != null) {
            byte[] body = ("{\"public\":" + isPublic + ",\"uid\":\"" + owner + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<byte[]> res = gateway.forward(HttpMethod.POST,
                    "/api/runs/" + runId + "/visibility", null, body, headers);

            // 하네스가 안 바꿨는데 화면에 바뀐 것으로 보이면 제일 나쁘다 —
            // 껐다고 믿는데 실제로는 걸려 있게 된다.
            if (!res.getStatusCode().is2xxSuccessful()) {
                log.warn("공개 여부를 못 바꿨습니다 (run={}, status={})", runId, res.getStatusCode());
                throw new BusinessException(ErrorCode.INVALID_INPUT, "공개 여부를 바꾸지 못했습니다");
            }
        }

        ledger.setPublic(runId, isPublic);

        /* **그림도 옮긴다.** 공개/비공개를 자리로 가르기 때문에(PrivateArt),
           안 옮기면 비공개로 내려도 CloudFront 가 계속 내준다 — 스위치가
           거짓말을 하는 셈이다.

           옮기다 실패해도 여기서 안 던진다: 이미 DB 와 하네스는 바뀌었고,
           목록에서는 내려가 있다. 남은 것은 "주소를 아는 사람에게 아직
           열린다" 이고, 그건 크게 남겨 두고 다시 시도할 일이다. */
        try {
            pages.moveAll(runId, isPublic);
        } catch (RuntimeException e) {
            log.error("공개 여부는 바꿨는데 그림을 못 옮겼습니다 (run={}, public={})",
                    runId, isPublic, e);
        }
        return isPublic;
    }

    /** 내 계정에 이어진 브라우저 중 이 작품을 만든 uid. 내 것이 아니면 null. */
    private String ownerUidOf(Long userId, String runId) {
        for (BrowserLink one : links.findByUserId(userId)) {
            List<Map<String, Object>> got = runsOf(one.getBrowserUid());
            if (got == null) {
                continue;                       // 못 읽은 기기는 건너뛴다
            }
            for (Map<String, Object> run : got) {
                if (runId.equals(String.valueOf(run.get("run_id")))) {
                    return one.getBrowserUid();
                }
            }
        }
        return null;
    }

    /** @return 그 브라우저가 만든 것. <b>못 읽었으면 null</b> — 빈 목록과 다르다(위 참고). */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> runsOf(String uid) {
        String query = "owner=" + URLEncoder.encode(uid, StandardCharsets.UTF_8);
        ResponseEntity<byte[]> res =
                gateway.forward(HttpMethod.GET, "/api/runs", query, null, new HttpHeaders());
        if (!res.getStatusCode().is2xxSuccessful() || res.getBody() == null) {
            log.warn("작품 목록을 못 받았습니다 (uid={}, status={})", uid, res.getStatusCode());
            return null;
        }
        try {
            Map<String, Object> body = mapper.readValue(res.getBody(), Map.class);
            Object runs = body.get("runs");
            return runs instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
        } catch (IOException e) {
            log.warn("작품 목록을 읽지 못했습니다 (uid={})", uid, e);
            return null;
        }
    }

    /** 저장 전에 다듬는다 — 길이를 넘거나 이상한 글자가 섞인 값은 안 받는다. */
    static String normalize(String uid) {
        if (uid == null) {
            return "";
        }
        String trimmed = uid.trim();
        if (trimmed.isEmpty() || trimmed.length() > UID_MAX) {
            return "";
        }
        // 프론트가 만드는 값은 영숫자뿐이다(`u` + Date·랜덤의 36진수).
        // 그 밖의 글자가 오면 남이 만든 값이거나 장난이므로 안 받는다.
        return trimmed.matches("[A-Za-z0-9_-]+") ? trimmed : "";
    }
}
