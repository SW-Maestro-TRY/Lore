package com.lore.webtoon.art;

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
    /** 로컬에서만 켠다 — 서명 주소로 준다(아래 url 주석). */
    private final boolean presignLocally;
    private final Clock clock;

    @Autowired
    public PageStore(WebtoonPageRepository pages, PrivateArt art,
                     @Value("${lore.webtoon.presign-locally:false}") boolean presignLocally) {
        this(pages, art, presignLocally, Clock.systemUTC());
    }

    PageStore(WebtoonPageRepository pages, PrivateArt art,
              boolean presignLocally, Clock clock) {
        this.pages = pages;
        this.art = art;
        this.presignLocally = presignLocally;
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

    /**
     * 장 번호 -> S3 키. <b>가장 큰 폭</b>의 것을 준다.
     *
     * 한 편을 통째로 내보낼 때 쓴다 — 그때는 화면에 맞춰 줄인 것이 아니라
     * 가진 것 중 제일 큰 것을 이어야 한다. 목록 썸네일(320)로 이으면 받은
     * 파일이 뿌옇다.
     *
     * 장 번호 순서를 지킨 채로 준다({@code LinkedHashMap}) — 순서가 흐트러지면
     * 한 편이 뒤죽박죽으로 이어진다.
     */
    /**
     * 장 번호 -> <b>원본</b>(폭 0) S3 키.
     *
     * {@link #keysOf} 는 <b>제일 큰 폭</b>을 주므로 1080 짜리 jpg 가 나온다 —
     * 보여 주는 데는 그게 맞다. 그런데 <b>다시 그리기</b>는 다르다: 파이프라인이
     * 직전 장 그림을 참조로 붙여 이어 그리므로, 화면용으로 줄이고 jpg 로 구운
     * 것이 아니라 그려진 그대로의 PNG 가 있어야 한다.
     *
     * 서버 디스크의 원본은 S3 에 올린 뒤 지운다(편당 31MB 가 안 지워지고
     * 쌓이던 것). 그래서 다시 그릴 때 <b>여기서 받은 키로 되살린다.</b>
     */
    @Transactional(readOnly = true)
    public Map<Integer, String> originalKeys(String runId) {
        Map<Integer, String> out = new LinkedHashMap<>();
        for (WebtoonPage page : pages.findByRunIdOrderByPageNoAscWidthAsc(runId)) {
            if (page.getWidth() == 0) {
                out.put(page.getPageNo(), page.getS3Key());
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<Integer, String> keysOf(String runId) {
        Map<Integer, String> out = new LinkedHashMap<>();
        Map<Integer, Integer> best = new LinkedHashMap<>();
        // 폭 오름차순으로 오므로 나중 것이 늘 더 크다 — 그대로 덮어쓰면 된다.
        for (WebtoonPage page : pages.findByRunIdOrderByPageNoAscWidthAsc(runId)) {
            Integer had = best.get(page.getPageNo());
            if (had == null || page.getWidth() >= had) {
                best.put(page.getPageNo(), page.getWidth());
                out.put(page.getPageNo(), page.getS3Key());
            }
        }
        return out;
    }

    /**
     * 키 하나를 읽을 수 있는 주소로. 키가 없으면 {@code null}.
     *
     * 구운 그림처럼 <b>이 표에 없는 것</b>을 내보낼 때 쓴다 — 자리(공개/비공개)에
     * 따라 CloudFront 주소나 잠깐 열리는 주소를 고르는 규칙은 같아야 한다.
     */
    @Transactional(readOnly = true)
    public String urlOfKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return PrivateArt.isPrivate(key) ? art.temporaryUrl(key) : url(key);
    }

    /** 장 번호 -> 가진 것 중 가장 큰 폭. 구운 것을 같은 폭으로 적을 때 쓴다. */
    @Transactional(readOnly = true)
    public Map<Integer, Integer> widthsOf(String runId) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        for (WebtoonPage page : pages.findByRunIdOrderByPageNoAscWidthAsc(runId)) {
            Integer had = out.get(page.getPageNo());
            if (had == null || page.getWidth() >= had) {
                out.put(page.getPageNo(), page.getWidth());
            }
        }
        return out;
    }

    /** S3 에 올라와 있는 작품인가. */
    @Transactional(readOnly = true)
    public boolean has(String runId) {
        return runId != null && !runId.isBlank() && pages.existsByRunId(runId);
    }

    /**
     * 키 -> 읽을 수 있는 주소. <b>도메인을 절대 안 붙인다.</b>
     *
     * <h2>왜 도메인을 못 붙이게 막았나</h2>
     *
     * 예전에는 {@code lore.webtoon.cdn-base} 에 적힌 주소를 앞에 붙였다. 그
     * 설정이 2026-09-19 에 서비스를 통째로 멈춰 세웠다 — 거기 적혀 있던
     * {@code dev.lorecomic.com} 이 스테이징 서버로 갈아끼워지면서 basic auth 가
     * 붙었고, 운영 화면이 <b>그림마다 브라우저 로그인 팝업</b>을 띄웠다. 홈부터
     * 아무 화면도 못 쓰는 상태였다.
     *
     * 값을 고치는 것으로는 다시 안 막힌다 — 누가 또 다른 호스트를 적으면 그날로
     * 같은 일이 난다. 그래서 <b>붙일 자리 자체를 없앴다.</b> 지금은 언제나
     * {@code /images/...} 상대경로이고, 그러면:
     *
     * <ul>
     *   <li>보고 있는 그 도메인에서 그림이 나온다 — 운영 CloudFront 는
     *       {@code /images/*} 를 S3 로 보낸다</li>
     *   <li>인증 걸린 남의 호스트를 가리킬 방법이 없다 — 이 사고가 구조적으로
     *       재발하지 않는다</li>
     *   <li>주소가 안 만료되고 CDN 캐시를 그대로 탄다</li>
     * </ul>
     *
     * <h2>로컬만 예외</h2>
     *
     * 개발 기계에는 {@code /images/*} 를 받아 줄 것이 없다(next.config 의
     * rewrites 는 {@code /api/*} 만 넘긴다). 그래서 로컬은
     * {@code lore.webtoon.presign-locally=true} 로 켜서 잠깐 열리는 S3 서명
     * 주소를 받는다. 운영에서는 켜지 않는다.
     */
    String url(String key) {
        if (presignLocally) {
            String signed = art.ready() ? art.temporaryUrl(key) : null;
            if (signed != null) return signed;
        }
        return "/" + key;
    }

    /**
     * 하네스가 보내는 한 줄.
     *
     * @param width {@code 0} 이면 원본
     */
    public record Upload(int pageNo, int width, String key, long bytes) {
    }
}
