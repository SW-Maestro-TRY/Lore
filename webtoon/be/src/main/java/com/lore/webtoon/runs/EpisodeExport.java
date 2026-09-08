package com.lore.webtoon.runs;

import com.lore.common.s3.S3Storage;
import com.lore.webtoon.art.PageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 한 편을 <b>통째로 내려받을 파일</b>로 만든다 — 이어 붙이고 LORE 표시를 찍어서.
 *
 * <h2>내보낼 때만 찍는다</h2>
 *
 * 저장물({@code webtoon_page} 가 가리키는 S3 그림)은 안 건드린다. 표시는
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
 * <h2>파이썬과 같은 값을 쓴다</h2>
 *
 * 색·비율·문구를 {@code haeun/landing/watermark.py} 에서 그대로 가져왔다.
 * 한쪽을 바꾸면 같은 작품이 어디서 받느냐에 따라 다르게 나온다.
 */
@Service
public class EpisodeExport {

    private static final Logger log = LoggerFactory.getLogger(EpisodeExport.class);

    /* ---- 브랜드 — web/style.css 의 --sea-* 와 같은 값이다 ------------------ */
    private static final Color SEA_DEEP = new Color(63, 111, 102);
    private static final Color SEA_MINT = new Color(161, 198, 187);
    private static final Color PAPER = new Color(255, 253, 247);
    private static final String WORDMARK = "LORE";
    private static final String TAGLINE = "루와 함께 만든 웹툰";

    /* 띠 크기는 그림 폭에 비례한다 — 800px 짜리와 2000px 짜리에 같은 픽셀을
       쓰면 한쪽은 안 보이고 한쪽은 뒤덮는다. */
    private static final double BAND_RATIO = 0.085;
    private static final int BAND_MIN = 64, BAND_MAX = 190;
    private static final double MARK_RATIO = 0.05;
    private static final int MARK_MIN = 22, MARK_MAX = 64;
    /** 0~255. 그림을 읽는 데 방해가 안 될 만큼만. */
    private static final int MARK_ALPHA = 150;

    /** 띠 왼쪽에 앉는 루. 없으면 글자만 나간다 — 그림 하나 때문에 막히면 안 된다. */
    private static final String LOU = "haeun/landing/web/lou/react/idle/01.png";

    /**
     * 한글 글꼴 후보. 하네스({@code webtoon-harness/strip.py})가 찾는 곳과
     * 같은 목록이다 — 다른 글꼴로 찍으면 같은 작품의 표시가 서버마다 달라진다.
     */
    private static final String[] FONTS = {
            "C:\\Windows\\Fonts\\malgun.ttf",
            "/usr/share/fonts/truetype/nanum/NanumGothic.ttf",
            "/System/Library/Fonts/AppleSDGothicNeo.ttc",
    };

    private final PageStore pages;
    private final BakeService bakery;
    private final S3Storage storage;
    private Font base;

    public EpisodeExport(PageStore pages, BakeService bakery, S3Storage storage) {
        this.pages = pages;
        this.bakery = bakery;
        this.storage = storage;
    }

    /**
     * 이 작품 한 편. 그림이 없으면 {@code null} — 부르는 쪽이 404 를 낸다.
     *
     * @param caption 띠 오른쪽에 적을 한 줄 ("이올 · 1화")
     */
    public byte[] png(String runId, String caption) {
        List<BufferedImage> sheets = load(runId);
        if (sheets.isEmpty()) {
            return null;
        }
        BufferedImage joined = stitch(sheets);
        markEachSheet(joined, sheets);
        BufferedImage out = withBand(joined, caption);
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            ImageIO.write(out, "png", bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            log.error("한 편을 png 로 못 만들었습니다 (run={})", runId, e);
            return null;
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
    private static BufferedImage stitch(List<BufferedImage> sheets) {
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

    /* ---- 표시 ------------------------------------------------------------- */

    /**
     * <b>장마다</b> 자기 자리 오른쪽 아래에 표시를 찍는다.
     *
     * 한 편 전체에 하나만 찍으면, 한 장만 잘라(스크린샷·크롭) 퍼뜨렸을 때
     * 표시가 안 딸려 간다.
     */
    private void markEachSheet(BufferedImage canvas, List<BufferedImage> sheets) {
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int width = canvas.getWidth();
        int y = 0;
        for (BufferedImage one : sheets) {
            int w = one.getWidth(), h = one.getHeight();
            int x = (width - w) / 2;
            mark(g, canvas, x, y, w, h);
            y += h;
        }
        g.dispose();
    }

    /**
     * 표시 하나. <b>밑그림을 보고 색을 뒤집는다.</b>
     *
     * 늘 밝은 색으로만 찍으면 이 서비스의 그림처럼 흰 여백이 많은 경우 —
     * 장 오른쪽 아래가 하얀 일이 잦다 — 흰 바탕에 흰 글자가 되어 사실상
     * 안 보인다(실측으로 한 편 6장 중 4장이 그랬다).
     */
    private void mark(Graphics2D g, BufferedImage under, int x0, int y0, int bw, int bh) {
        int size = clamp(bw * MARK_RATIO, MARK_MIN, MARK_MAX);
        g.setFont(font(size, true));
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(WORDMARK);
        int th = fm.getAscent();
        int pad = Math.max(10, size / 2);
        int x = x0 + bw - pad - tw;
        int baseline = y0 + bh - pad;

        boolean light = luma(under, x, baseline - th, x + tw, baseline) < 150;
        Color ink = alpha(light ? PAPER : SEA_DEEP, MARK_ALPHA);
        Color shade = light
                ? new Color(0, 0, 0, MARK_ALPHA / 3)
                : new Color(255, 255, 255, MARK_ALPHA / 2);

        int off = Math.max(1, size / 20);
        g.setColor(shade);
        g.drawString(WORDMARK, x + off, baseline + off);
        g.setColor(ink);
        g.drawString(WORDMARK, x, baseline);
    }

    /**
     * 그 자리 평균 밝기(0~255).
     *
     * 픽셀을 다 훑지 않는다 — 한 편은 세로가 수만 px 이라 눈에 띄게 느려진다.
     * 파이썬처럼 8×8 로 줄여 본다.
     */
    private static double luma(BufferedImage img, int x0, int y0, int x1, int y1) {
        int lo = Math.max(0, x0), to = Math.max(0, y0);
        int hi = Math.min(img.getWidth(), x1), bo = Math.min(img.getHeight(), y1);
        if (hi <= lo || bo <= to) {
            return 128.0;
        }
        double sum = 0;
        int n = 0;
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                int px = lo + (hi - lo) * i / 8;
                int py = to + (bo - to) * j / 8;
                int rgb = img.getRGB(px, py);
                sum += 0.299 * ((rgb >> 16) & 0xFF)
                     + 0.587 * ((rgb >> 8) & 0xFF)
                     + 0.114 * (rgb & 0xFF);
                n++;
            }
        }
        return sum / n;
    }

    /* ---- 브랜드 띠 -------------------------------------------------------- */

    /**
     * 그림 아래에 덧대는 띠 — 어디서 만들었는지와 작품·회차를 적는다.
     *
     * 덧대는 것이지 덮는 것이 아니다. 그림을 안 가린다.
     */
    private BufferedImage withBand(BufferedImage img, String caption) {
        int w = img.getWidth(), h = img.getHeight();
        int band = clamp(w * BAND_RATIO, BAND_MIN, BAND_MAX);

        BufferedImage out = new BufferedImage(w, h + band, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(PAPER);
        g.fillRect(0, 0, w, h + band);
        g.drawImage(img, 0, 0, null);

        // 그림과 띠 사이 가는 선 — 띠가 작품의 일부로 안 읽히게 경계를 준다.
        g.setColor(SEA_MINT);
        g.fillRect(0, h, w, 2);

        int pad = Math.max(12, band / 5);
        int middle = h + 2 + (band - 2) / 2;
        int left = pad + drawLou(g, pad, h + 2, band - 2);

        g.setFont(font(clamp(band * 0.42, 14, 64), true));
        FontMetrics logo = g.getFontMetrics();
        g.setColor(SEA_DEEP);
        g.drawString(WORDMARK, left, middle - clamp(band * 0.10, 2, 12));

        g.setFont(font(clamp(band * 0.24, 10, 34), false));
        FontMetrics small = g.getFontMetrics();
        g.drawString(TAGLINE, left, middle + small.getAscent() + clamp(band * 0.06, 2, 10));

        // 오른쪽 — 작품·회차. 왼쪽 글자와 겹칠 만큼 길면 아예 안 적는다.
        if (caption != null && !caption.isBlank()) {
            int cw = small.stringWidth(caption);
            if (cw < w - pad - left - logo.stringWidth(WORDMARK) - pad) {
                g.setColor(new Color(120, 120, 120));
                g.drawString(caption, w - pad - cw, middle + small.getAscent() / 2);
            }
        }
        g.dispose();
        return out;
    }

    /** 띠 왼쪽에 루를 앉힌다. -> 글자가 시작할 x 오프셋 (못 그리면 0). */
    private int drawLou(Graphics2D g, int x, int top, int bandHeight) {
        try {
            Path p = Path.of(LOU).toAbsolutePath();
            if (!Files.isRegularFile(p)) {
                return 0;
            }
            BufferedImage lou = ImageIO.read(p.toFile());
            if (lou == null) {
                return 0;
            }
            int size = Math.max(16, (int) (bandHeight * 0.86));
            g.drawImage(lou, x, top + (bandHeight - size) / 2, size, size, null);
            return size + Math.max(6, size / 8);
        } catch (IOException | RuntimeException e) {
            // 루가 없어도 내려받기는 돼야 한다 — 글자만 나간다.
            log.debug("띠에 루를 못 그렸습니다", e);
            return 0;
        }
    }

    /* ---- 글꼴 ------------------------------------------------------------- */

    /**
     * 한글 글꼴. 못 찾으면 시스템 기본으로 떨어진다 — 표시가 예뻐지지 않을
     * 뿐이고, <b>글꼴 하나 때문에 내려받기가 막히면 안 된다.</b>
     */
    private Font font(int size, boolean bold) {
        if (base == null) {
            for (String cand : FONTS) {
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
            if (base == null) {
                log.warn("한글 글꼴을 못 찾았습니다 — 표시를 기본 글꼴로 찍습니다");
                base = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
            }
        }
        return base.deriveFont(bold ? Font.BOLD : Font.PLAIN, (float) size);
    }

    private static int clamp(double value, int lo, int hi) {
        return Math.max(lo, Math.min(hi, (int) Math.round(value)));
    }

    private static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }
}
