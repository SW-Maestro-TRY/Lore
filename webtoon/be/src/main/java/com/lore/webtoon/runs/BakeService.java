package com.lore.webtoon.runs;

import com.fasterxml.jackson.databind.JsonNode;
import com.lore.common.s3.S3Service;
import com.lore.common.s3.S3Storage;
import com.lore.webtoon.art.PageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 편집실에서 얹은 것을 <b>그림에 굽는다</b> — 그리고 S3 에 올린다.
 *
 * <h2>왜 자바가 굽는가</h2>
 *
 * 여태 굽는 일은 파이썬이 했고, 그래서 <b>서버에서는 편집실이 아예 안 됐다</b>
 * — 그 폴더도 그 스크립트도 서버에 없기 때문이다. 사람이 말풍선을 얹어도
 * 저장할 데가 없고 구울 수도 없었다.
 *
 * <h2>원본은 안 건드린다</h2>
 *
 * 구운 것은 따로 올리고 따로 적는다({@link BakedPage}). 편집실은 계속 밑그림을
 * 보고, 보는 자리는 구운 것을 본다. 다시 구우면 구운 줄만 바뀐다 — 몇 번을
 * 구워도 말풍선이 겹쳐 쌓이지 않는다.
 */
@Service
public class BakeService {

    private static final Logger log = LoggerFactory.getLogger(BakeService.class);

    /** S3 키 앞자리. 그림 올리는 쪽({@code PageUploader})과 같은 자리를 쓴다. */
    private static final String DOMAIN = "webtoon";

    private final PageStore pages;
    private final BakedPageRepository baked;
    private final OverlayStore overlays;
    private final BubbleArtist artist;
    private final S3Storage storage;

    public BakeService(PageStore pages, BakedPageRepository baked, OverlayStore overlays,
                       BubbleArtist artist, S3Storage storage) {
        this.pages = pages;
        this.baked = baked;
        this.overlays = overlays;
        this.artist = artist;
        this.storage = storage;
    }

    /**
     * 이 작품을 통째로 굽는다. -> 구운 장 수
     *
     * 얹은 것이 <b>하나도 없는 장은 안 굽는다</b> — 구울 것이 없는데 파일만 한
     * 벌 더 만들면 S3 만 무거워지고, 보는 쪽은 어차피 원본과 같은 그림을 본다.
     * 전에 구웠는데 이번에 다 지운 장은 구운 줄을 지운다.
     */
    @Transactional
    public int bake(String runId, int episode) {
        JsonNode data = overlays.read(runId, episode);
        Map<Integer, String> keys = pages.keysOf(runId);
        int done = 0;
        for (Map.Entry<Integer, String> page : keys.entrySet()) {
            int no = page.getKey();
            JsonNode scene = data.path("scenes").path(String.valueOf(no));
            if (scene.path("items").isEmpty()) {
                dropBaked(runId, no);
                continue;
            }
            if (bakeOne(runId, no, page.getValue(), scene)) {
                done++;
            }
        }
        log.info("얹은 것을 구웠습니다 (run={}, {}장)", runId, done);
        return done;
    }

    /** 한 장. 못 구우면 {@code false} — 그 장만 원본으로 남는다. */
    private boolean bakeOne(String runId, int no, String rawKey, JsonNode scene) {
        Path from = null;
        Path to = null;
        try {
            from = Files.createTempFile("lore-raw-", ".png");
            storage.download(rawKey, from);
            BufferedImage page = ImageIO.read(from.toFile());
            if (page == null) {
                log.warn("밑그림을 못 읽었습니다 (run={}, 장={})", runId, no);
                return false;
            }
            BufferedImage drawn = artist.draw(page, scene);

            to = Files.createTempFile("lore-baked-", ".png");
            ImageIO.write(drawn, "png", to.toFile());

            String key = S3Service.newKey(DOMAIN);
            storage.upload(key, to, "image/png");
            record(runId, no, widthOf(runId, no), key);
            return true;
        } catch (IOException | RuntimeException e) {
            // 한 장 때문에 통째로 막지 않는다 — 나머지는 구워진다.
            log.error("한 장을 못 구웠습니다 (run={}, 장={})", runId, no, e);
            return false;
        } finally {
            delete(from);
            delete(to);
        }
    }

    /** 이 장을 어느 폭으로 적을 것인가 — 원본이 가진 가장 큰 폭과 같게 둔다. */
    private int widthOf(String runId, int no) {
        return pages.widthsOf(runId).getOrDefault(no, 1080);
    }

    private void record(String runId, int no, int width, String key) {
        baked.findByRunIdAndPageNoAndWidth(runId, no, width)
                .ifPresentOrElse(one -> one.movedTo(key, Instant.now()),
                        () -> baked.save(BakedPage.of(runId, no, width, key, Instant.now())));
    }

    /**
     * 이 장의 밑그림이 바뀌었다 — 구운 것을 지운다.
     *
     * 다시 그리기(regen)가 부른다. 밑그림이 새로 나왔는데 <b>옛 밑그림 위에
     * 구운 말풍선</b>이 그대로 남아 있으면, 보는 자리는 새 그림에 옛 말풍선을
     * 얹은 것을 계속 보여준다 — 둘이 안 맞는 그림이다. 다시 구우려면 편집실이
     * 다시 열어야 한다.
     */
    @Transactional
    public void invalidate(String runId, int pageNo) {
        dropBaked(runId, pageNo);
    }

    /** 이번에 얹은 것이 하나도 없으면 구운 줄을 거둔다 — 옛 말풍선이 되살아나면 안 된다. */
    private void dropBaked(String runId, int no) {
        baked.findByRunIdOrderByPageNoAscWidthAsc(runId).stream()
                .filter(one -> one.getPageNo() == no)
                .forEach(baked::delete);
    }

    /* ---- 보는 쪽이 묻는 것 ------------------------------------------------- */

    /** 이 장의 구운 그림이 S3 어디에 있나. 안 구웠으면 {@code null}. */
    @Transactional(readOnly = true)
    public String keyOf(String runId, int pageNo) {
        return baked.findByRunIdOrderByPageNoAscWidthAsc(runId).stream()
                .filter(one -> one.getPageNo() == pageNo)
                .map(BakedPage::getS3Key)
                .findFirst()
                .orElse(null);
    }

    /** 장 번호 -> 구운 그림 키. 내려받기가 원본 대신 이것을 쓴다. */
    @Transactional(readOnly = true)
    public Map<Integer, String> keysOf(String runId) {
        Map<Integer, String> out = new LinkedHashMap<>();
        for (BakedPage one : baked.findByRunIdOrderByPageNoAscWidthAsc(runId)) {
            out.putIfAbsent(one.getPageNo(), one.getS3Key());
        }
        return out;
    }

    private static void delete(Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("임시 파일을 못 지웠습니다 ({})", p, e);
        }
    }
}
