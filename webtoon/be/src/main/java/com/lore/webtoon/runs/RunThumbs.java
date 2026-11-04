package com.lore.webtoon.runs;

import com.lore.common.s3.S3Service;
import com.lore.common.s3.S3Storage;
import com.lore.webtoon.art.PageStore;
import com.lore.webtoon.art.PrivateArt;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 작품 카드의 썸네일 — 장 그림 하나에서 잘라 낸 한 장면(2026-11-04).
 *
 * <p>표지가 없는 작품(세계관 체험 만화)은 카드에 장 그림을 통째로 얹으면 흰 여백과 작은 컷 여럿이 보여서 누르고
 * 싶지 않다(하은). 그래서 <b>기본은 서버가 그 장에서 가장 큰 컷 하나를 골라 자르고</b>({@link #auto}), 주인이 장과
 * 자리를 골라 바꿀 수 있다({@link #set}).
 *
 * <p>잘라 낸 그림은 늘 비공개 자리에 두고 잠깐 열리는 주소로 낸다 — 작품을 공개 · 비공개로 바꿔도 옮길 필요가 없다.
 * 읽어도 되는지는 부르는 쪽(ThumbController)이 작품 장 그림과 같은 문지기로 본다.
 *
 * <p>기본 썸네일은 처음 불릴 때 뒤에서 한 번 만든다 — 카드 목록을 그리다 그림을 받아 자르느라 화면이 늦어지면 안 된다.
 * 만들어지기 전에는 부르는 쪽이 지금처럼 장 그림을 보여 준다.
 */
@Service
public class RunThumbs {

    private static final Logger log = LoggerFactory.getLogger(RunThumbs.class);
    private static final String DOMAIN = "webtoon";
    /** 잘라 낸 그림의 가로 — 카드는 이보다 작게 그린다. */
    static final int WIDTH = 720;

    /** 장 안의 자리(0~1 비율). */
    public record Box(int page, double x, double y, double w, double h) {
        Box clamp() {
            double cx = Math.max(0, Math.min(1, x)), cy = Math.max(0, Math.min(1, y));
            double cw = Math.max(0.05, Math.min(1 - cx, w)), ch = Math.max(0.05, Math.min(1 - cy, h));
            return new Box(page, cx, cy, cw, ch);
        }
    }

    private final NamedParameterJdbcTemplate sql;
    private final PagePixels pixels;
    private final PageStore pages;
    private final S3Storage storage;
    /** 썸네일이 있는 작품 — 카드에 표시를 달 때 목록마다 DB 를 안 부르려고 들고 있는다. */
    private final Set<String> made = ConcurrentHashMap.newKeySet();
    private final Set<String> busy = ConcurrentHashMap.newKeySet();
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "webtoon-thumb");
        t.setDaemon(true);
        return t;
    });

    public RunThumbs(NamedParameterJdbcTemplate sql, PagePixels pixels, PageStore pages, S3Storage storage) {
        this.sql = sql;
        this.pixels = pixels;
        this.pages = pages;
        this.storage = storage;
    }

    @PostConstruct
    void load() {
        try {
            made.addAll(sql.queryForList("select run_id from webtoon_run_thumb", Map.of(), String.class));
        } catch (RuntimeException e) {
            log.warn("썸네일 목록을 못 읽었습니다: {}", e.toString());
        }
    }

    @PreDestroy
    void stop() {
        pool.shutdownNow();
    }

    /** 썸네일이 있나 — 카드의 {@code thumb} 표시. */
    public boolean has(String runId) {
        return runId != null && made.contains(runId);
    }

    /** 썸네일 주소. 아직 없으면 {@code null}. */
    public String urlOf(String runId) {
        List<String> keys = sql.queryForList("select s3_key from webtoon_run_thumb where run_id = :r",
                Map.of("r", runId), String.class);
        return keys.isEmpty() ? null : pages.urlOfKey(keys.getFirst());
    }

    /** 지금 자리 — 고치는 창이 처음에 보여 준다. 없으면 {@code null}. */
    public Map<String, Object> describe(String runId) {
        List<Map<String, Object>> rows = sql.queryForList(
                "select page, x, y, w, h, by_owner from webtoon_run_thumb where run_id = :r", Map.of("r", runId));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 기본 썸네일이 없으면 뒤에서 만든다. 이미 만드는 중이면 아무것도 안 한다. */
    public void ensure(String runId, int page) {
        if (runId == null || made.contains(runId) || !busy.add(runId)) {
            return;
        }
        pool.submit(() -> {
            try {
                make(runId, page, null, false);
            } catch (RuntimeException e) {
                log.warn("기본 썸네일을 못 만들었습니다 (run={}): {}", runId, e.toString());
            } finally {
                busy.remove(runId);
            }
        });
    }

    /** 주인이 고른 자리로 바꾼다. 못 자르면 {@code false}. */
    public boolean set(String runId, Box box) {
        return make(runId, box.page(), box.clamp(), true);
    }

    /** 주인이 고른 것을 버리고 기본으로 돌아간다 — 다음에 불릴 때 다시 기본을 만든다. */
    public void reset(String runId) {
        sql.update("delete from webtoon_run_thumb where run_id = :r", Map.of("r", runId));
        made.remove(runId);
    }

    private boolean make(String runId, int page, Box chosen, boolean byOwner) {
        byte[] bytes = pixels.raw(runId, page);
        if (bytes == null) {
            return false;
        }
        Path tmp = null;
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) {
                return false;
            }
            Box box = chosen != null ? chosen : auto(img, page);
            BufferedImage cut = crop(img, box);
            tmp = Files.createTempFile("lore-thumb-", ".jpg");
            ImageIO.write(cut, "jpg", tmp.toFile());
            String key = PrivateArt.moved(S3Service.newKey(DOMAIN), false);
            storage.upload(key, tmp, "image/jpeg");
            sql.update("""
                    insert into webtoon_run_thumb(run_id, page, x, y, w, h, s3_key, by_owner, updated_at)
                    values (:r, :p, :x, :y, :w, :h, :k, :o, now())
                    on conflict (run_id) do update set page = excluded.page, x = excluded.x, y = excluded.y,
                        w = excluded.w, h = excluded.h, s3_key = excluded.s3_key, by_owner = excluded.by_owner,
                        updated_at = now()""",
                    new MapSqlParameterSource().addValue("r", runId).addValue("p", box.page())
                            .addValue("x", box.x()).addValue("y", box.y()).addValue("w", box.w()).addValue("h", box.h())
                            .addValue("k", key).addValue("o", byOwner));
            made.add(runId);
            return true;
        } catch (IOException e) {
            log.warn("썸네일을 못 잘랐습니다 (run={}, 장={}): {}", runId, page, e.toString());
            return false;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // 임시 파일 — 운영체제가 치운다
                }
            }
        }
    }

    /**
     * 가장 큰 컷 — 장 그림은 흰 바탕 위에 컷이 세로로 놓인다. 거의 흰 줄(가로줄의 92% 이상이 밝음)을 컷 사이 여백으로
     * 보고, 여백 사이 띠 중 가장 높은 것을 고른 뒤 그 띠 안에서 그림이 있는 가로 범위를 잡는다. 여백이 없는 장(표지처럼
     * 그림이 꽉 찬 장)은 장 전체다.
     */
    static Box auto(BufferedImage img, int page) {
        int step = Math.max(1, img.getWidth() / 256);
        int w = img.getWidth(), h = img.getHeight();
        int bestTop = 0, bestBottom = h, bestHeight = -1;
        int y = 0;
        while (y < h) {
            if (whiteRow(img, y, step)) {
                y += step;
                continue;
            }
            int top = y;
            while (y < h && !whiteRow(img, y, step)) {
                y += step;
            }
            int bottom = Math.min(h, y);
            if (bottom - top > bestHeight) {
                bestHeight = bottom - top;
                bestTop = top;
                bestBottom = bottom;
            }
        }
        if (bestHeight < h / 10) {
            return new Box(page, 0, 0, 1, 1);
        }
        int left = 0, right = w;
        while (left < w && whiteColumn(img, left, bestTop, bestBottom, step)) {
            left += step;
        }
        while (right > left + step && whiteColumn(img, right - 1, bestTop, bestBottom, step)) {
            right -= step;
        }
        /* 테두리 선과 여백 찌꺼기를 조금 덜어 낸다 */
        int inset = Math.max(2, w / 170);
        double x0 = Math.min(w - 1, left + inset), y0 = Math.min(h - 1, bestTop + inset);
        double x1 = Math.max(x0 + 1, right - inset), y1 = Math.max(y0 + 1, bestBottom - inset);
        return new Box(page, x0 / w, y0 / h, (x1 - x0) / w, (y1 - y0) / h).clamp();
    }

    private static boolean whiteRow(BufferedImage img, int y, int step) {
        int n = 0, bright = 0;
        for (int x = 0; x < img.getWidth(); x += step) {
            n++;
            if (luma(img.getRGB(x, y)) > 235) {
                bright++;
            }
        }
        return bright >= n * 0.92;
    }

    private static boolean whiteColumn(BufferedImage img, int x, int top, int bottom, int step) {
        int n = 0, bright = 0;
        for (int y = top; y < bottom; y += step) {
            n++;
            if (luma(img.getRGB(x, y)) > 235) {
                bright++;
            }
        }
        return n == 0 || bright >= n * 0.5;
    }

    private static int luma(int rgb) {
        int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    static BufferedImage crop(BufferedImage img, Box box) {
        int x = (int) Math.round(box.x() * img.getWidth()), y = (int) Math.round(box.y() * img.getHeight());
        int cw = Math.max(1, Math.min(img.getWidth() - x, (int) Math.round(box.w() * img.getWidth())));
        int ch = Math.max(1, Math.min(img.getHeight() - y, (int) Math.round(box.h() * img.getHeight())));
        int outW = Math.min(WIDTH, cw), outH = Math.max(1, (int) Math.round((double) ch * outW / cw));
        BufferedImage out = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(img, 0, 0, outW, outH, x, y, x + cw, y + ch, null);
        g.dispose();
        return out;
    }
}
