package com.lore.webtoon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 작품의 장이 S3 어디에 있나 — 적고, 주소로 바꿔 준다.
 *
 * <h2>왜 주소를 여기서 만드나</h2>
 *
 * DB 에는 <b>S3 키</b>만 있다(도메인이 안 들어간다). 도메인은 배포마다 다를 수
 * 있고, 한번 박아 두면 옮길 때 줄마다 고쳐야 한다. 읽어 줄 때 앞에 붙인다.
 */
@Service
public class PageStore {

    private static final Logger log = LoggerFactory.getLogger(PageStore.class);

    private final WebtoonPageRepository pages;
    /** 그림을 읽어 주는 곳. 비어 있으면 같은 도메인의 상대경로로 준다. */
    private final String cdn;
    private final Clock clock;

    @Autowired
    public PageStore(WebtoonPageRepository pages,
                     @Value("${lore.webtoon.cdn-base:}") String cdn) {
        this(pages, cdn, Clock.systemUTC());
    }

    PageStore(WebtoonPageRepository pages, String cdn, Clock clock) {
        this.pages = pages;
        this.cdn = cdn == null ? "" : cdn.replaceAll("/+$", "");
        this.clock = clock;
    }

    /**
     * 올린 것을 적는다. 같은 자리(작품·장·폭)가 이미 있으면 주소만 바꾼다 —
     * 다시 구운 그림이 올라온 것이므로 옛 주소를 들고 있으면 안 된다.
     *
     * @return 이번에 새로 적은 줄 수 (바꾼 것은 안 센다)
     */
    @Transactional
    public int record(String runId, List<Upload> uploads) {
        if (runId == null || runId.isBlank() || uploads == null) {
            return 0;
        }
        int fresh = 0;
        Instant now = Instant.now(clock);
        for (Upload one : uploads) {
            if (one == null || one.key() == null || one.key().isBlank()) {
                continue;
            }
            var found = pages.findByRunIdAndPageNoAndWidth(runId, one.pageNo(), one.width());
            if (found.isPresent()) {
                found.get().movedTo(one.key(), one.bytes(), now);
                pages.save(found.get());
            } else {
                pages.save(WebtoonPage.of(runId, one.pageNo(), one.width(),
                                          one.key(), one.bytes(), now));
                fresh++;
            }
        }
        log.info("작품 그림 주소를 적었습니다 (run={}, 새로 {}개)", runId, fresh);
        return fresh;
    }

    /**
     * 이 작품의 장 -> 폭 -> 주소.
     *
     * 화면이 "이 장의 1080 폭" 을 바로 집을 수 있는 모양이다. 없는 작품이면
     * 빈 것 — 그때 화면은 예전처럼 하네스에게 묻는다.
     */
    @Transactional(readOnly = true)
    public Map<Integer, Map<Integer, String>> urlsOf(String runId) {
        Map<Integer, Map<Integer, String>> out = new LinkedHashMap<>();
        for (WebtoonPage page : pages.findByRunIdOrderByPageNoAscWidthAsc(runId)) {
            out.computeIfAbsent(page.getPageNo(), k -> new LinkedHashMap<>())
               .put(page.getWidth(), url(page.getS3Key()));
        }
        return out;
    }

    /**
     * 이 장 이 폭이 S3 어디에 있나. 없으면 {@code null} — 그때는 부르는 쪽이
     * 예전처럼 하네스에게 묻는다.
     */
    @Transactional(readOnly = true)
    public String urlOf(String runId, int pageNo, int width) {
        return pages.findByRunIdAndPageNoAndWidth(runId, pageNo, width)
                .map(p -> url(p.getS3Key()))
                .orElse(null);
    }

    /** S3 에 올라와 있는 작품인가. */
    @Transactional(readOnly = true)
    public boolean has(String runId) {
        return runId != null && !runId.isBlank() && pages.existsByRunId(runId);
    }

    /** 키 -> 읽을 수 있는 주소. */
    String url(String key) {
        return cdn.isEmpty() ? "/" + key : cdn + "/" + key;
    }

    /**
     * 하네스가 보내는 한 줄.
     *
     * @param width {@code 0} 이면 원본
     */
    public record Upload(int pageNo, int width, String key, long bytes) {
    }
}
