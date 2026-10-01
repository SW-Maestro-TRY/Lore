package com.lore.webtoon.runs;

import com.lore.common.s3.S3Storage;
import com.lore.webtoon.art.PageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 한 편을 <b>통째로 내려받을 파일</b>로 만든다 — 이어 붙이고 장마다 LORECOMIC 도장을 찍어서.
 *
 * <h2>내보낼 때만 찍는다</h2>
 *
 * 저장물({@code webtoon_page} 가 가리키는 S3 그림)은 안 건드린다. 도장은
 * "서비스 밖으로 나가는 파일"의 성질이지 저장물의 성질이 아니다 — 만드는
 * 동안 보는 화면과 편집실은 깨끗해야 하고, 나중에 「크레딧을 쓰면 표시를
 * 뗀다」로 갈 때 저장물에 박혀 있으면 이미 만든 작품은 영영 못 뗀다.
 *
 * <h2>미리 구워 두지 않는다</h2>
 *
 * 부를 때마다 그 자리에서 만든다. 미리 만들어 두면 <b>편집실에서 말풍선을
 * 얹은 뒤에 내려받았을 때 옛 그림이 나간다</b> — 얹어 놓고 받았더니 말풍선이
 * 없더라는 것이 가장 알아채기 어려운 실패다. 한 편이 세로로 아주 길어서
 * 그리는 값이 싸지는 않지만, 틀린 파일을 빠르게 주는 것보다 낫다.
 *
 * <h2>도장은 장마다 오른쪽 아래에</h2>
 *
 * 예전에는 그림 아래에 「LORE / 루와 함께 만든 웹툰 / 작품 · 1화」 띠를 덧대고
 * 장마다 반투명 「LORE」를 따로 찍었다. 글자가 네 군데에 흩어져 싸구려처럼
 * 보인다는 평(2026-10-01)으로 띠를 없애고, 웹툰 사이트들이 찍는 것처럼
 * 장마다 오른쪽 아래에 루 + 글 두 줄짜리 작은 도장 하나만 찍는다. 장마다
 * 찍는 이유는 한 장만 잘라 퍼뜨려도 딸려 가야 해서다.
 *
 * <h2>그림과 글꼴은 jar 에 실린 것을 쓴다</h2>
 *
 * 배포 서버에는 저장소 폴더도 한글 글꼴도 없다. 작업 폴더 상대경로로 루를
 * 찾거나 시스템 글꼴에 기대면 서버에서는 루 없이 네모 글자만 나간다(실제로
 * 그랬다).
 * 그래서 루는 하네스 리소스({@code webtoon/ai/assets/lou}, build.gradle 의
 * {@code processResources} 가 담는다)에서, 글꼴은 {@code webtoon/fonts/}(OFL 의
 * Jua)에서 읽는다. 둘 다 못 읽어도 내려받기는 돼야 한다 — 그림 하나 때문에
 * 막히면 안 된다.
 */
@Service
public class EpisodeExport {

    private static final Logger log = LoggerFactory.getLogger(EpisodeExport.class);

    /* ---- 브랜드 — web/style.css 의 --sea-* 와 같은 값이다 ------------------ */
    private static final Color SEA_DEEP = new Color(63, 111, 102);
    private static final String WORDMARK = "LORECOMIC";
    /**
     * 워드마크 위 한 줄. 비우면 루와 LORECOMIC 만 찍힌다 — 2026-10-01 시안에서
     * 이 둘(c: 문구 있음 / g: 문구 없음) 중 c 로 정했고, g 는 여기를 비우면 된다.
     */
    private static final String TAGLINE = "내 캐릭터로 만드는 웹툰";

    /* 도장 크기는 장 폭에 비례한다 — 800px 짜리와 2000px 짜리에 같은 픽셀을
       쓰면 한쪽은 안 보이고 한쪽은 뒤덮는다. */
    private static final double STAMP_RATIO = 0.17;
    private static final int STAMP_MIN = 90, STAMP_MAX = 420;
    /** 0~1. 그림을 읽는 데 방해가 안 될 만큼만. */
    private static final float STAMP_ALPHA = 0.82f;

    /** 도장에 앉는 루(하트 든 것). jar 리소스 경로 — 저장소에서 바로 돌릴 때는 같은 상대경로가 파일로 있다. */
    private static final String LOU = "webtoon/ai/assets/lou/logo-1-happy.png";
    /** 한글 글꼴. jar 리소스. */
    private static final String FONT = "webtoon/fonts/Jua-Regular.ttf";

    /**
     * jar 에 글꼴이 없을 때 볼 시스템 글꼴 후보. 하네스({@code webtoon-harness/strip.py})가
     * 찾는 곳과 같은 목록이다.
     */
    private static final String[] SYSTEM_FONTS = {
            "C:\\Windows\\Fonts\\malgun.ttf",
            "/usr/share/fonts/truetype/nanum/NanumGothic.ttf",
            "/System/Library/Fonts/AppleSDGothicNeo.ttc",
    };

    private final PageStore pages;
    private final BakeService bakery;
    private final S3Storage storage;
    private Font base;
    private BufferedImage lou;
    private boolean louTried;

    public EpisodeExport(PageStore pages, BakeService bakery, S3Storage storage) {
        this.pages = pages;
        this.bakery = bakery;
        this.storage = storage;
    }

    /** 이 작품 한 편. 그림이 없으면 {@code null} — 부르는 쪽이 404 를 낸다. */
    public byte[] png(String runId) {
        List<BufferedImage> sheets = load(runId);
        if (sheets.isEmpty()) {
            return null;
        }
        BufferedImage out = stitch(sheets);
        stampEachSheet(out, sheets);
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            ImageIO.write(out, "png", bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            log.error("한 편을 png 로 못 만들었습니다 (run={})", runId, e);
            return null;
        }
    }

    /**
     * 한 컷만. {@link #png} 처럼 도장을 찍지만 <b>잇지 않는다</b> — 그
     * 컷 하나에만 찍어 준다. 결과 화면에서 컷을 체크박스로 골라 몇 장만
     * 받을 때 쓴다({@code RunController#pageDownload}). 그 장이 없으면
     * {@code null}.
     */
    public byte[] pagePng(String runId, int no) {
        BufferedImage sheet = loadOne(runId, no);
        if (sheet == null) {
            return null;
        }
        BufferedImage out = new BufferedImage(sheet.getWidth(), sheet.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(sheet, 0, 0, null);
        g.dispose();
        stampEachSheet(out, List.of(out));
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            ImageIO.write(out, "png", bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            log.error("한 컷을 png 로 못 만들었습니다 (run={}, 장={})", runId, no, e);
            return null;
        }
    }

    /** 이 장 하나. {@link #load} 와 같은 자리(구운 것 우선)에서 하나만 읽는다. */
    private BufferedImage loadOne(String runId, int no) {
        Map<Integer, String> keys = new java.util.LinkedHashMap<>(pages.keysOf(runId));
        keys.putAll(bakery.keysOf(runId));
        String key = keys.get(no);
        if (key == null) {
            return null;
        }
        Path tmp = null;
        try {
            tmp = Files.createTempFile("lore-page-", ".png");
            storage.download(key, tmp);
            return ImageIO.read(tmp.toFile());
        } catch (IOException | RuntimeException e) {
            log.warn("장을 못 가져왔습니다 (run={}, 장={})", runId, no, e);
            return null;
        } finally {
            deleteQuietly(tmp);
        }
    }

    /* ---- 낱장 가져오기 ---------------------------------------------------- */

    /**
     * S3 에 있는 낱장을 장 번호 순서로. 못 읽은 장은 건너뛴다.
     *
     * <b>구운 것이 있으면 그것을 쓴다.</b> 얹어 놓고 받았더니 말풍선이 없더라는
     * 것이 가장 알아채기 어려운 실패다 — 화면에서 본 것과 받은 파일이 다르면
     * 사람은 자기가 저장을 안 한 줄 안다.
     */
    private List<BufferedImage> load(String runId) {
        List<BufferedImage> out = new ArrayList<>();
        Map<Integer, String> keys = new java.util.LinkedHashMap<>(pages.keysOf(runId));
        keys.putAll(bakery.keysOf(runId));
        for (Map.Entry<Integer, String> one : keys.entrySet()) {
            Path tmp = null;
            try {
                tmp = Files.createTempFile("lore-page-", ".png");
                storage.download(one.getValue(), tmp);
                BufferedImage img = ImageIO.read(tmp.toFile());
                if (img != null) {
                    out.add(img);
                } else {
                    log.warn("장을 못 읽었습니다 (run={}, 장={})", runId, one.getKey());
                }
            } catch (IOException | RuntimeException e) {
                // 한 장 때문에 통째로 막지 않는다 — 나머지는 그대로 내보낸다.
                log.warn("장을 못 가져왔습니다 (run={}, 장={})", runId, one.getKey(), e);
            } finally {
                deleteQuietly(tmp);
            }
        }
        return out;
    }

    private static void deleteQuietly(Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("임시 파일을 못 지웠습니다 ({})", p, e);
        }
    }

    /* ---- 이어 붙이기 ------------------------------------------------------ */

    /**
     * 위아래로 잇는다.
     *
     * 폭이 장마다 다르면(캔버스를 바꿔 가며 그린 작품) <b>가장 넓은 폭에 맞춰
     * 가운데 정렬</b>한다 — 자르지도 늘리지도 않는다. 하네스의
     * {@code stitch.py} 와 같은 규칙이다.
     */
    static BufferedImage stitch(List<BufferedImage> sheets) {
        int width = sheets.stream().mapToInt(BufferedImage::getWidth).max().orElse(1);
        int height = sheets.stream().mapToInt(BufferedImage::getHeight).sum();
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        int y = 0;
        for (BufferedImage one : sheets) {
            g.drawImage(one, (width - one.getWidth()) / 2, y, null);
            y += one.getHeight();
        }
        g.dispose();
        return out;
    }

    /* ---- 도장 ------------------------------------------------------------- */

    /**
     * <b>장마다</b> 자기 자리 오른쪽 아래에 도장을 찍는다.
     *
     * 한 편 전체에 하나만 찍으면, 한 장만 잘라(스크린샷·크롭) 퍼뜨렸을 때
     * 표시가 안 딸려 간다. 컷별 내려받기도 같은 함수를 장 하나로 부른다.
     */
    void stampEachSheet(BufferedImage canvas, List<BufferedImage> sheets) {
        Graphics2D g = canvas.createGraphics();
        int width = canvas.getWidth();
        int y = 0;
        for (BufferedImage one : sheets) {
            int w = one.getWidth(), h = one.getHeight();
            stamp(g, (width - w) / 2, y, w, h);
            y += h;
        }
        g.dispose();
    }

    /**
     * 도장 하나 — 위에 루, 아래에 글 두 줄(소개 · LORECOMIC)을 가운데 맞춰 세로로
     * 쌓고, 장의 오른쪽 아래 구석에 반투명으로 얹는다. 글자는 흰 속에 짙은
     * 테두리라 어두운 장에서도 흰 장에서도 읽힌다.
     *
     * 크기는 장 폭에 비례한다(폭의 17%). 800px 짜리와 2000px 짜리에 같은 픽셀을
     * 쓰면 한쪽은 안 보이고 한쪽은 뒤덮는다.
     */
    private void stamp(Graphics2D g, int x0, int y0, int bw, int bh) {
        int s = clamp(bw * STAMP_RATIO, STAMP_MIN, STAMP_MAX);
        int pad = Math.max(8, (int) (bw * 0.015));

        BufferedImage lou = lou();
        int louH = lou == null ? 0 : (int) (s * 0.42);
        int louW = lou == null ? 0 : Math.max(1, louH * lou.getWidth() / Math.max(1, lou.getHeight()));

        Font tagFont = font(Math.max(9, (int) (s * 0.16)));
        Font markFont = font(Math.max(11, (int) (s * 0.24)));
        FontMetrics tm = g.getFontMetrics(tagFont);
        FontMetrics mm = g.getFontMetrics(markFont);
        boolean tag = !TAGLINE.isBlank();
        int tagH = tag ? (int) (tm.getAscent() * 1.15) : 0;
        int markH = (int) (mm.getAscent() * 1.15);
        int blockW = Math.max(louW, Math.max(tag ? tm.stringWidth(TAGLINE) : 0, mm.stringWidth(WORDMARK)));
        int blockH = louH + (lou == null ? 0 : 2) + tagH + markH;

        int cx = x0 + bw - pad - blockW / 2;      // 덩어리의 가운데 x
        int top = y0 + bh - pad - blockH;

        java.awt.Composite was = g.getComposite();
        g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, STAMP_ALPHA));
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        int y = top;
        if (lou != null) {
            g.drawImage(lou, cx - louW / 2, y, louW, louH, null);
            y += louH + 2;
        }
        float stroke = Math.max(1.5f, s / 85f);
        if (tag) {
            outlinedText(g, TAGLINE, tagFont, cx, y + tm.getAscent(), stroke);
            y += tagH;
        }
        outlinedText(g, WORDMARK, markFont, cx, y + mm.getAscent(), stroke);
        g.setComposite(was);
    }

    /** 가운데(cx) 맞춘 글자 — 짙은 테두리를 먼저 긋고 흰 속을 채운다. */
    private static void outlinedText(Graphics2D g, String text, Font font, int cx, int baseline, float stroke) {
        java.awt.font.GlyphVector gv = font.createGlyphVector(g.getFontRenderContext(), text);
        java.awt.Shape shape = gv.getOutline();
        double w = shape.getBounds2D().getWidth();
        java.awt.geom.AffineTransform at = java.awt.geom.AffineTransform.getTranslateInstance(cx - w / 2, baseline);
        shape = at.createTransformedShape(shape);
        g.setStroke(new java.awt.BasicStroke(stroke * 2, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
        g.setColor(SEA_DEEP);
        g.draw(shape);
        g.setColor(Color.WHITE);
        g.fill(shape);
    }

    /**
     * 도장에 앉힐 루. 한 번만 읽어 둔다. 못 읽으면 {@code null} — 글자만 나간다.
     *
     * 원본은 투명 여백이 있어 그대로 앉히면 작게 보인다 — 투명하지 않은 부분만
     * 잘라 둔다.
     */
    private synchronized BufferedImage lou() {
        if (louTried) {
            return lou;
        }
        louTried = true;
        try (InputStream in = open(LOU)) {
            BufferedImage raw = in == null ? null : ImageIO.read(in);
            lou = raw == null ? null : trimTransparent(raw);
        } catch (IOException | RuntimeException e) {
            log.debug("도장에 앉힐 루를 못 읽었습니다", e);
        }
        if (lou == null) {
            log.warn("도장에 앉힐 루 그림을 못 찾았습니다 ({}) — 글자만 찍습니다", LOU);
        }
        return lou;
    }

    /** 투명(알파 0)인 테두리를 잘라 낸다. 전부 투명하면 그대로 돌려준다. */
    static BufferedImage trimTransparent(BufferedImage img) {
        if (!img.getColorModel().hasAlpha()) {
            return img;
        }
        int w = img.getWidth(), h = img.getHeight();
        int x0 = w, y0 = h, x1 = -1, y1 = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (((img.getRGB(x, y) >>> 24) & 0xFF) > 8) {
                    x0 = Math.min(x0, x); x1 = Math.max(x1, x);
                    y0 = Math.min(y0, y); y1 = Math.max(y1, y);
                }
            }
        }
        if (x1 < x0 || y1 < y0) {
            return img;
        }
        return img.getSubimage(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
    }

    /**
     * jar 리소스를 먼저, 없으면 저장소 폴더의 같은 상대경로를 본다 — 테스트와
     * IDE 에서 바로 돌릴 때는 리소스가 아직 안 담겨 있을 수 있다.
     */
    private static InputStream open(String path) throws IOException {
        ClassPathResource res = new ClassPathResource(path);
        if (res.exists()) {
            return res.getInputStream();
        }
        for (Path up = Path.of("").toAbsolutePath(); up != null; up = up.getParent()) {
            Path cand = up.resolve(path);
            if (Files.isRegularFile(cand)) {
                return Files.newInputStream(cand);
            }
        }
        return null;
    }

    /* ---- 글꼴 ------------------------------------------------------------- */

    /**
     * 한글 글꼴. jar 의 Jua 를 먼저 보고, 없으면 시스템 글꼴, 그것도 없으면
     * 기본 글꼴로 떨어진다 — 표시가 예뻐지지 않을 뿐이고, <b>글꼴 하나 때문에
     * 내려받기가 막히면 안 된다.</b>
     */
    private synchronized Font font(int size) {
        if (base == null) {
            try (InputStream in = open(FONT)) {
                if (in != null) {
                    base = Font.createFont(Font.TRUETYPE_FONT, in);
                }
            } catch (Exception e) {     // noqa: 시스템 글꼴을 본다
                log.debug("글꼴을 못 읽었습니다 ({})", FONT, e);
            }
        }
        if (base == null) {
            for (String cand : SYSTEM_FONTS) {
                Path p = Path.of(cand);
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                try {
                    base = Font.createFont(Font.TRUETYPE_FONT, p.toFile());
                    break;
                } catch (Exception e) {     // noqa: 다음 후보를 본다
                    log.debug("글꼴을 못 읽었습니다 ({})", cand, e);
                }
            }
        }
        if (base == null) {
            log.warn("한글 글꼴을 못 찾았습니다 — 도장을 기본 글꼴로 찍습니다");
            base = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        }
        return base.deriveFont(Font.PLAIN, (float) size);
    }

    private static int clamp(double value, int lo, int hi) {
        return Math.max(lo, Math.min(hi, (int) Math.round(value)));
    }
}
