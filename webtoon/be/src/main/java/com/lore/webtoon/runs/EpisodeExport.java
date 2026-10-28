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
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
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
 * 보인다는 평(2026-10-01)으로 띠를 없애고 장마다 오른쪽 아래에 루 + 글 두 줄
 * 도장을 찍었다. 그 도장도 그림이 튀고 못생겼다는 사용자 피드백(2026-10-28)으로
 * 지금은 옅은 「LORE」 네 글자만 찍는다. 장마다 찍는 이유는 한 장만 잘라
 * 퍼뜨려도 딸려 가야 해서다.
 *
 * <h2>글꼴은 jar 에 실린 것을 쓴다</h2>
 *
 * 배포 서버에는 저장소 폴더도 글꼴도 없다. 시스템 글꼴에 기대면 서버에서는
 * 기본 글꼴로 떨어진다. 그래서 글꼴은 {@code webtoon/fonts/}(OFL 의 SUIT)에서
 * 읽는다. 못 읽어도 내려받기는 돼야 한다 — 글꼴 하나 때문에 막히면 안 된다.
 */
@Service
public class EpisodeExport {

    private static final Logger log = LoggerFactory.getLogger(EpisodeExport.class);

    /* ---- 도장 — 2026-10-28 시안 C(오른쪽 아래 · 옅게)로 정했다 -------------- */
    private static final String WORDMARK = "LORE";

    /* 글자 크기·여백은 장 폭에 비례한다 — 800px 짜리와 2000px 짜리에 같은 픽셀을
       쓰면 한쪽은 안 보이고 한쪽은 뒤덮는다. */
    private static final double MARK_RATIO = 0.036;
    private static final int MARK_MIN = 12;
    private static final double MARK_PAD = 0.03;
    /** 글자 사이 간격(글자 크기의 몇 배). */
    private static final float MARK_TRACKING = 0.18f;
    /** 0~1. 그림을 읽는 데 방해가 안 될 만큼만 — 흰 글자는 옅게, 그림자는 더 옅게. */
    private static final float MARK_ALPHA = 0.55f;
    private static final float SHADOW_ALPHA = 0.25f;
    /** 그림자 번짐(글자 크기의 몇 배). 밝은 장에서도 글자 테두리가 보이게 하는 몫이다. */
    private static final float SHADOW_BLUR = 0.12f;

    /** 도장 글꼴. jar 리소스. */
    private static final String FONT = "webtoon/fonts/SUIT-Bold.ttf";

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
    /* 편집실에서 고친 여백(장 뒤의 쉼). 검사에서 손으로 만들 때는 없어도 된다 — 그러면 여백 없이 잇는다. */
    private OverlayStore overlays;

    @org.springframework.beans.factory.annotation.Autowired
    public void setOverlays(OverlayStore overlays) {
        this.overlays = overlays;
    }
    private Font base;

    public EpisodeExport(PageStore pages, BakeService bakery, S3Storage storage) {
        this.pages = pages;
        this.bakery = bakery;
        this.storage = storage;
    }

    /** 이 작품 한 편. 그림이 없으면 {@code null} — 부르는 쪽이 404 를 낸다. */
    public byte[] png(String runId) {
        Map<Integer, BufferedImage> loaded = load(runId);
        if (loaded.isEmpty()) {
            return null;
        }
        List<BufferedImage> sheets = new ArrayList<>(loaded.values());
        int[] gaps = gapsPx(runId, new ArrayList<>(loaded.keySet()), sheets);
        BufferedImage out = stitch(sheets, gaps);
        stampEachSheet(out, sheets, gaps);
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
    private Map<Integer, BufferedImage> load(String runId) {
        Map<Integer, BufferedImage> out = new java.util.LinkedHashMap<>();
        Map<Integer, String> keys = new java.util.LinkedHashMap<>(pages.keysOf(runId));
        keys.putAll(bakery.keysOf(runId));
        for (Map.Entry<Integer, String> one : keys.entrySet()) {
            Path tmp = null;
            try {
                tmp = Files.createTempFile("lore-page-", ".png");
                storage.download(one.getValue(), tmp);
                BufferedImage img = ImageIO.read(tmp.toFile());
                if (img != null) {
                    out.put(one.getKey(), img);
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
        return stitch(sheets, new int[sheets.size()]);
    }

    /**
     * 장 뒤마다 {@code gaps[i]} 픽셀을 흰 여백으로 비우고 잇는다. 마지막 장 뒤는
     * 비우지 않는다 — 공개본 화면({@code Result.tsx})과 같은 규칙이다.
     */
    static BufferedImage stitch(List<BufferedImage> sheets, int[] gaps) {
        int width = sheets.stream().mapToInt(BufferedImage::getWidth).max().orElse(1);
        int height = sheets.stream().mapToInt(BufferedImage::getHeight).sum() + sumGaps(gaps, sheets.size());
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        int y = 0;
        for (int i = 0; i < sheets.size(); i++) {
            BufferedImage one = sheets.get(i);
            g.drawImage(one, (width - one.getWidth()) / 2, y, null);
            y += one.getHeight() + gapAfter(gaps, i, sheets.size());
        }
        g.dispose();
        return out;
    }

    private static int gapAfter(int[] gaps, int i, int count) {
        return i < count - 1 && gaps != null && i < gaps.length ? Math.max(0, gaps[i]) : 0;
    }

    private static int sumGaps(int[] gaps, int count) {
        int sum = 0;
        for (int i = 0; i < count; i++) {
            sum += gapAfter(gaps, i, count);
        }
        return sum;
    }

    /**
     * 편집실에서 고친 여백을 픽셀로. 여백은 지면 폭의 몇 배라, 이을 때 쓰는
     * 폭(가장 넓은 장)에 곱한다 — 화면이 여백을 폭 기준 %로 그리는 것과 같다.
     */
    private int[] gapsPx(String runId, List<Integer> numbers, List<BufferedImage> sheets) {
        int[] out = new int[numbers.size()];
        if (overlays == null) {
            return out;
        }
        Map<Integer, Integer> steps = overlays.gapsOf(runId, 1);
        int width = sheets.stream().mapToInt(BufferedImage::getWidth).max().orElse(1);
        for (int i = 0; i < numbers.size(); i++) {
            out[i] = (int) Math.round(width * OverlayStore.gapRatio(steps.getOrDefault(numbers.get(i), 0)));
        }
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
        stampEachSheet(canvas, sheets, new int[sheets.size()]);
    }

    void stampEachSheet(BufferedImage canvas, List<BufferedImage> sheets, int[] gaps) {
        Graphics2D g = canvas.createGraphics();
        int width = canvas.getWidth();
        int y = 0;
        for (int i = 0; i < sheets.size(); i++) {
            BufferedImage one = sheets.get(i);
            int w = one.getWidth(), h = one.getHeight();
            stamp(g, (width - w) / 2, y, w, h);
            y += h + gapAfter(gaps, i, sheets.size());
        }
        g.dispose();
    }

    /**
     * 도장 하나 — 「LORE」 네 글자를 장의 오른쪽 아래 구석에 옅게 얹는다.
     * 흰 글자 밑에 번진 그림자를 깔아, 흰 장에서도 어두운 장에서도 글자
     * 테두리가 보이게 한다. 짙은 테두리를 긋던 예전 도장은 그림 위에서 튀었다.
     *
     * 크기는 장 폭에 비례한다(글자 높이가 폭의 3.6%).
     */
    private void stamp(Graphics2D g, int x0, int y0, int bw, int bh) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        Font font = font(Math.max(MARK_MIN, (int) Math.round(bw * MARK_RATIO)));
        Shape word = tracked(g, WORDMARK, font, font.getSize2D() * MARK_TRACKING);
        Rectangle2D b = word.getBounds2D();
        int pad = Math.max(6, (int) Math.round(bw * MARK_PAD));
        Shape placed = AffineTransform
                .getTranslateInstance(x0 + bw - pad - b.getMaxX(), y0 + bh - pad - b.getMaxY())
                .createTransformedShape(word);

        float sigma = Math.max(1.5f, font.getSize2D() * SHADOW_BLUR);
        Rectangle r = placed.getBounds();
        r.grow((int) Math.ceil(sigma * 3) + 2, (int) Math.ceil(sigma * 3) + 2);
        BufferedImage shadow = new BufferedImage(r.width, r.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D sg = shadow.createGraphics();
        sg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        sg.translate(-r.x, -r.y);
        sg.setColor(new Color(0, 0, 0, Math.round(255 * SHADOW_ALPHA)));
        sg.fill(placed);
        sg.dispose();
        g.drawImage(blur(shadow, sigma), r.x, r.y, null);

        g.setColor(new Color(255, 255, 255, Math.round(255 * MARK_ALPHA)));
        g.fill(placed);
    }

    /** 글자 사이를 {@code track} 만큼 벌린 글자 모양. 기준선이 y=0 이다. */
    private static Shape tracked(Graphics2D g, String text, Font font, float track) {
        Path2D out = new Path2D.Float();
        double x = 0;
        for (int i = 0; i < text.length(); i++) {
            GlyphVector gv = font.createGlyphVector(g.getFontRenderContext(), text.substring(i, i + 1));
            out.append(AffineTransform.getTranslateInstance(x, 0).createTransformedShape(gv.getOutline()), false);
            x += gv.getLogicalBounds().getWidth() + track;
        }
        return out;
    }

    /** 가우스 번짐 — 가로·세로로 한 번씩. 가장자리는 비워 두고 부르므로 그대로 둔다. */
    static BufferedImage blur(BufferedImage src, float sigma) {
        int r = (int) Math.ceil(sigma * 3);
        float[] k = new float[r * 2 + 1];
        float sum = 0;
        for (int i = -r; i <= r; i++) {
            k[i + r] = (float) Math.exp(-(i * i) / (2.0 * sigma * sigma));
            sum += k[i + r];
        }
        for (int i = 0; i < k.length; i++) {
            k[i] /= sum;
        }
        BufferedImage h = new ConvolveOp(new Kernel(k.length, 1, k), ConvolveOp.EDGE_NO_OP, null).filter(src, null);
        return new ConvolveOp(new Kernel(1, k.length, k), ConvolveOp.EDGE_NO_OP, null).filter(h, null);
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
     * 도장 글꼴. jar 의 SUIT 를 먼저 보고, 없으면 시스템 글꼴, 그것도 없으면
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
            log.warn("도장 글꼴을 못 찾았습니다 — 기본 글꼴로 찍습니다");
            base = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        }
        return base.deriveFont(Font.PLAIN, (float) size);
    }

}
