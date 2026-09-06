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
    private final PrivateArt art;
    /** 그림을 읽어 주는 곳. 비어 있으면 같은 도메인의 상대경로로 준다. */
    private final String cdn;
    private final Clock clock;

    @Autowired
    public PageStore(WebtoonPageRepository pages, PrivateArt art,
                     @Value("${lore.webtoon.cdn-base:}") String cdn) {
        this(pages, art, cdn, Clock.systemUTC());
    }

    PageStore(WebtoonPageRepository pages, PrivateArt art, String cdn, Clock clock) {
        this.pages = pages;
        this.art = art;
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
        return record(runId, uploads, true);
    }

    /**
     * @param isPublic 지금 공개인가. 비공개면 올라온 그림을 <b>CloudFront 가 안
     *                 내주는 자리로 옮긴다</b> — 하네스는 공개 여부를 모르고
     *                 늘 공개 자리에 올리므로, 받는 쪽에서 맞춰 준다.
     */
    @Transactional
    public int record(String runId, List<Upload> uploads, boolean isPublic) {
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
        if (!isPublic) {
            moveAll(runId, false);
        }
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
                .map(p -> PrivateArt.isPrivate(p.getS3Key())
                        // 비공개 자리에 있는 것은 CloudFront 가 안 내준다.
                        // 잠깐 열리는 주소를 만들어 준다 — 여기까지 왔다는 것은
                        // 부르는 쪽이 이미 주인 확인을 마쳤다는 뜻이다.
                        ? art.temporaryUrl(p.getS3Key())
                        : url(p.getS3Key()))
                .orElse(null);
    }

    /**
     * 이 작품의 그림을 공개 자리 / 비공개 자리로 옮긴다.
     *
     * 자리로 가르므로 공개 여부를 바꾸면 실제로 옮겨야 한다. <b>안 옮기면
     * 비공개로 내려도 계속 열린다.</b>
     *
     * 한 장을 못 옮겨도 나머지는 옮긴다 — 하나 때문에 전부 옛 자리에 남는
     * 것이 더 나쁘다. 못 옮긴 것은 크게 남긴다.
     *
     * @return 옮긴 장 수
     */
    @Transactional
    public int moveAll(String runId, boolean toPublic) {
        int moved = 0;
        for (WebtoonPage page : pages.findByRunIdOrderByPageNoAscWidthAsc(runId)) {
            if (PrivateArt.isPrivate(page.getS3Key()) != toPublic) {
                continue;                       // 이미 맞는 자리에 있다
            }
            String to = art.move(page.getS3Key(), toPublic);
            if (to != null && !to.equals(page.getS3Key())) {
                page.movedTo(to, page.getBytes(), Instant.now(clock));
                pages.save(page);
                moved++;
            }
        }
        if (moved > 0) {
            log.info("작품 그림을 {} 자리로 옮겼습니다 (run={}, {}장)",
                    toPublic ? "공개" : "비공개", runId, moved);
        }
        return moved;
    }

    /** 이 작품의 장 번호들. 표지와 장 수를 낼 때 쓴다. */
    @Transactional(readOnly = true)
    public List<Integer> pageNumbersOf(String runId) {
        return pages.findByRunIdOrderByPageNoAscWidthAsc(runId).stream()
                .map(WebtoonPage::getPageNo)
                .distinct()
                .toList();
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
