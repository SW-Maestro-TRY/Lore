package com.lore.webtoon.work;

import com.lore.webtoon.art.PageStore;
import com.lore.webtoon.runs.RunService;
import com.lore.webtoon.art.PrivateArt;
import com.lore.webtoon.credit.BrowserLink;
import com.lore.webtoon.credit.BrowserLinkRepository;
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
    private final WorkLedger ledger;
    private final PageStore pages;
    private final RunService runs;

    /**
     * 하네스가 준 JSON 을 읽을 때만 쓴다.
     *
     * 스프링이 주는 것을 받지 않고 직접 만든다 — 이 앱에는 {@code ObjectMapper}
     * 빈이 없어서 주입을 걸면 <b>서버가 아예 안 뜬다</b>(실제로 그랬다).
     * 웹 조각 테스트에서는 Jackson 자동설정이 같이 떠서 안 드러났다.
     * 여기서 하는 일은 작은 응답 하나를 읽는 것뿐이라 앱 공용 설정이 필요 없다.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    public MyWebtoonService(BrowserLinkRepository links,
                            WorkLedger ledger, PageStore pages, RunService runs) {
        this.links = links;
        this.ledger = ledger;
        this.pages = pages;
        this.runs = runs;
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

        /* 예전에는 여기서 하네스에게 "이 브라우저가 만든 것" 을 물어 옛 작품을
         * 옮겨 담았다. 2026-09-12에 걷어냈다 — 하네스(serve.py)를 더 이상 띄우지
         * 않고, 만들 때 DB(WorkLedger)에 직접 적으므로 옮겨 담을 것이 없다. */
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
        for (String runId : ledger.runIdsOf(userId)) {
            if (runId == null || merged.containsKey(runId)) {
                continue;
            }
            Map<String, Object> card = cardOf(runId);
            if (card != null) {
                merged.put(runId, card);
            }
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * 작품 하나의 카드. 내용(제목·표지·장 수)은 여전히 하네스가 안다 — DB 가
     * 아는 것은 <b>누구 것인가</b> 뿐이다.
     *
     * @return 못 읽으면 {@code null}. 그 한 편만 빠지고 나머지는 보여준다
     */
    private Map<String, Object> cardOf(String runId) {
        /* DB 가 아는 것으로만 만든다. 제목·줄거리는 여기 있고, 표지와 장 수는
           그림 쪽(webtoon_page)이 안다.

           예전에는 DB 에 없으면 하네스에게 물었다(`/api/runs/{id}/result`).
           2026-09-12에 걷어냈다 — 그 길은 serve.py 가 떠 있어야만 되는데,
           이제 파이썬은 서버가 아니라 CLI 파이프라인으로만 쓴다. DB 에 없는
           옛 작품은 목록에 안 뜬다. */
        return runs.cardOf(runId);
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
        /* 주인 확인은 DB 로만 한다. 예전에는 하네스에게도 물어보고 하네스
           파일에도 공개 여부를 한 번 더 적었는데(2026-09-12 제거), serve.py 를
           안 띄우는 지금은 적을 곳이 없고 적을 이유도 없다 — 공개 여부와 그림
           자리는 DB 와 S3 가 정한다. */
        if (!ledger.mayChange(runId, userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "내가 만든 작품만 바꿀 수 있습니다");
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
