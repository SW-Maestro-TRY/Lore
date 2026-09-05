package com.lore.webtoon;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
    private final ObjectMapper mapper;

    public MyWebtoonService(BrowserLinkRepository links, HarnessGateway gateway, ObjectMapper mapper) {
        this.links = links;
        this.gateway = gateway;
        this.mapper = mapper;
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
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> myRuns(Long userId) {
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (BrowserLink one : links.findByUserId(userId)) {
            for (Map<String, Object> run : runsOf(one.getBrowserUid())) {
                Object id = run.get("run_id");
                if (id != null) {
                    merged.putIfAbsent(String.valueOf(id), run);
                }
            }
        }
        return new ArrayList<>(merged.values());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> runsOf(String uid) {
        String query = "owner=" + URLEncoder.encode(uid, StandardCharsets.UTF_8);
        ResponseEntity<byte[]> res =
                gateway.forward(HttpMethod.GET, "/api/runs", query, null, new HttpHeaders());
        if (!res.getStatusCode().is2xxSuccessful() || res.getBody() == null) {
            log.warn("작품 목록을 못 받았습니다 (uid={}, status={})", uid, res.getStatusCode());
            return List.of();
        }
        try {
            Map<String, Object> body = mapper.readValue(res.getBody(), Map.class);
            Object runs = body.get("runs");
            return runs instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
        } catch (IOException e) {
            log.warn("작품 목록을 읽지 못했습니다 (uid={})", uid, e);
            return List.of();
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
