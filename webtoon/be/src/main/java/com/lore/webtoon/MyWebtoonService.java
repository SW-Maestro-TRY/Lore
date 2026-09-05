package com.lore.webtoon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
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

    public MyWebtoonService(BrowserLinkRepository links, HarnessGateway gateway) {
        this.links = links;
        this.gateway = gateway;
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
        if (!mine.isEmpty() && failed == mine.size()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "작품 목록을 가져오지 못했습니다");
        }
        return new ArrayList<>(merged.values());
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
    @Transactional(readOnly = true)
    public boolean setVisibility(Long userId, String runId, boolean isPublic) {
        String owner = ownerUidOf(userId, runId);
        if (owner == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "내가 만든 작품만 바꿀 수 있습니다");
        }
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
