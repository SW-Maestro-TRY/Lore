package com.lore.webtoon.runs;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 편집실에서 얹은 것을 <b>그림에 실제로 그린다</b> — 말풍선 · 스티커 · 효과음.
 *
 * <h2>화면과 같은 모양이어야 한다</h2>
 *
 * 사람은 편집실에서 본 대로 나올 것이라 믿고 저장한다. 그래서 여기서 그리는
 * 규칙은 화면({@code webtoon/fe 의 editorCore.paintShape})과 <b>같은 계산</b>
 * 이다 — 글에 맞춰 줄어드는 풍선, √2 만큼 키운 타원, 가장자리에서 자라는 꼬리,
 * 꼬리 끝을 재는 기준까지.
 *
 * <h2>꼬리는 몸통과 한 덩어리다</h2>
 *
 * 몸통과 꼬리를 따로 그리면 이어진 자리에 선이 남고, 타원은 가장자리가 안으로
 * 휘므로 꼬리가 공중에 뜬 것처럼 보인다. 여기서는 두 도형을 <b>합친 뒤</b>
 * 그 테두리만 긋는다({@link Area}) — 안쪽에는 선이 생길 수가 없다.
 */
@Service
public class BubbleArtist {

    private static final Logger log = LoggerFactory.getLogger(BubbleArtist.class);

    private static final Color INK = new Color(17, 17, 17);
    private static final Color PAPER = Color.WHITE;
    /** 꼬리가 달리는 종류. 나레이션·회상에는 애초에 꼬리가 없다. */
    private static final List<String> TAILED =
            List.of("normal", "shout", "whisper", "thought");

    /** 하네스가 찾는 곳과 같은 목록 — 다른 글꼴로 그리면 화면과 어긋난다. */
    private static final String[] FONTS = {
            "C:\\Windows\\Fonts\\malgun.ttf",
            "/usr/share/fonts/truetype/nanum/NanumGothic.ttf",
            "/System/Library/Fonts/AppleSDGothicNeo.ttc",
    };

    private Font base;

    /**
     * 한 장에 얹은 것을 다 그린다. -> 그려진 새 그림 (원본은 안 건드린다)
     *
     * @param scene {@code {"ref_w": …, "items": [...]}}
     */
    public BufferedImage draw(BufferedImage page, JsonNode scene) {
        BufferedImage out = new BufferedImage(page.getWidth(), page.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(page, 0, 0, null);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        /* 편집실이 보던 지면 폭에 대한 배율. 화면에서 720px 로 보며 15px 글자를
           얹었다면, 1024px 그림에서는 그만큼 커져야 같은 자리 같은 크기다. */
        double refW = scene.path("ref_w").asDouble(720.0);
        double scale = page.getWidth() / Math.max(1.0, refW);

        for (JsonNode item : scene.path("items")) {
            try {
                one(g, page.getWidth(), page.getHeight(), item, scale);
            } catch (RuntimeException e) {
                // 하나 때문에 나머지를 못 그리면 안 된다 — 크게 남기고 넘어간다.
                log.warn("얹은 것 하나를 못 그렸습니다 ({})", item.path("text").asText(""), e);
            }
        }
        g.dispose();
        return out;
    }

    /* ---- 하나 그리기 ------------------------------------------------------ */

    private void one(Graphics2D g, int pageW, int pageH, JsonNode item, double scale) {
        String type = item.path("type").asText("");
        int size = (int) Math.max(7, item.path("size").asDouble(15) * scale);
        int boxW = (int) Math.max(8, pageW * item.path("w").asDouble(40) / 100.0);

        Tile tile = switch (type) {
            case "bubble" -> bubble(item, boxW, scale, size);
            case "sfx" -> sfx(item, scale);
            default -> sticker(item, scale);
        };
        if (tile == null) {
            return;
        }

        /* 자리 — 편집실은 왼쪽 위를 x%·y% 에 두고 가운데를 기준으로 돌린다.
           꼬리가 밖으로 뻗어 타일이 커졌으면 그만큼 되민다(off) — 안 그러면
           꼬리를 끌 때마다 몸통까지 따라 움직인다. */
        double left = pageW * item.path("x").asDouble(20) / 100.0 - tile.offX;
        double top = pageH * item.path("y").asDouble(30) / 100.0 - tile.offY;
        double rot = item.path("rot").asDouble(0);

        AffineTransform was = g.getTransform();
        if (Math.abs(rot) > 0.01) {
            g.rotate(Math.toRadians(rot),
                    left + tile.image.getWidth() / 2.0,
                    top + tile.image.getHeight() / 2.0);
        }
        g.drawImage(tile.image, (int) Math.round(left), (int) Math.round(top), null);
        g.setTransform(was);
    }

    /** 그려 둔 조각과, 몸통이 얼마나 밀렸는가. */
    private record Tile(BufferedImage image, double offX, double offY) {
    }

    /* ---- 말풍선 ----------------------------------------------------------- */

    private Tile bubble(JsonNode item, int boxW, double scale, int fs) {
        String variant = item.path("variant").asText("normal");
        boolean round = !"narration".equals(variant);
        Font font = font(fs, "shout".equals(variant));
        int padX = (int) (fs * 0.85), padY = (int) (fs * 0.55);
        int stroke = (int) Math.max(2, 2.5 * scale);
        // 외침은 뾰족한 만큼 안쪽이 좁다(안쪽 반지름이 바깥의 0.8).
        double spread = round ? Math.sqrt(2) / ("shout".equals(variant) ? 0.8 : 1.0) : 1.0;

        Fit fit = fit(item.path("text").asText(""), font, boxW, padX, padY, spread,
                "narration".equals(variant) ? 4.0 : 2.4);

        boolean tailed = TAILED.contains(variant) && !"none".equals(item.path("tail").asText());
        double tipX = fit.w * item.path("tx").asDouble(22) / 100.0;
        double tipY = fit.h * item.path("ty").asDouble(152) / 100.0;

        // 끝이 몸통 밖으로 나가면 조각을 넓힌다.
        int m = stroke + 2;
        double x0 = tailed ? Math.min(0, tipX) - m : -m;
        double y0 = tailed ? Math.min(0, tipY) - m : -m;
        double x1 = tailed ? Math.max(fit.w, tipX) + m : fit.w + m;
        double y1 = tailed ? Math.max(fit.h, tipY) + m : fit.h + m;

        int offX = (int) -x0, offY = (int) -y0;
        int tw = (int) (x1 - x0), th = (int) (y1 - y0);
        BufferedImage tile = new BufferedImage(Math.max(1, tw), Math.max(1, th),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = tile.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        double cx = offX + fit.w / 2.0, cy = offY + fit.h / 2.0;
        double a = fit.w / 2.0, b = fit.h / 2.0;
        Shape body = switch (variant) {
            case "narration" -> new RoundRectangle2D.Double(offX, offY, fit.w, fit.h,
                    6 * scale, 6 * scale);
            case "shout" -> spikes(cx, cy, a, b);
            default -> new Ellipse2D.Double(offX, offY, fit.w, fit.h);
        };

        // 몸통과 꼬리를 **합친 뒤** 테두리를 긋는다 — 이어진 자리에 선이 없다.
        Area shape = new Area(body);
        if (tailed) {
            shape.add(new Area("thought".equals(variant)
                    ? thoughtDots(cx, cy, a, b, tipX + offX, tipY + offY, fs)
                    : tail(cx, cy, a, b, tipX + offX, tipY + offY, Math.max(5, fs * 0.62))));
        }

        g.setColor("flash".equals(variant) ? new Color(255, 255, 255, 214) : PAPER);
        g.fill(shape);
        g.setColor("flash".equals(variant) ? new Color(17, 17, 17, 214) : INK);
        g.setStroke(dashed(variant) ? dash(stroke, scale) : new BasicStroke(stroke));
        g.draw(shape);

        // 글 — 나레이션만 왼쪽 정렬이다 (화면 CSS 의 text-align: left).
        g.setFont(font);
        g.setColor(INK);
        FontMetrics fm = g.getFontMetrics();
        int lh = Math.max(1, (int) (fs * 1.35));
        double top = offY + (fit.h - lh * fit.lines.size()) / 2.0 + fm.getAscent();
        for (int i = 0; i < fit.lines.size(); i++) {
            String line = fit.lines.get(i);
            double x = "narration".equals(variant)
                    ? offX + padX
                    : offX + (fit.w - fm.stringWidth(line)) / 2.0;
            g.drawString(line, (float) x, (float) (top + i * lh));
        }
        g.dispose();
        return new Tile(tile, offX, offY);
    }

    private static boolean dashed(String variant) {
        return "whisper".equals(variant) || "flash".equals(variant);
    }

    private static BasicStroke dash(int stroke, double scale) {
        float on = (float) Math.max(2, 7 * scale), off = (float) Math.max(2, 8 * scale);
        return new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                10f, new float[]{on, off}, 0f);
    }

    /** 외침 — 뾰족뾰족한 테두리. 안쪽 반지름이 바깥의 0.8 이다. */
    private static Shape spikes(double cx, double cy, double a, double b) {
        Path2D.Double p = new Path2D.Double();
        int spikes = 12;
        for (int i = 0; i < spikes * 2; i++) {
            double ang = Math.PI * i / spikes - Math.PI / 2;
            double f = i % 2 == 0 ? 1.0 : 0.80;
            double x = cx + Math.cos(ang) * a * f, y = cy + Math.sin(ang) * b * f;
            if (i == 0) {
                p.moveTo(x, y);
            } else {
                p.lineTo(x, y);
            }
        }
        p.closePath();
        return p;
    }

    /**
     * 풍선 <b>가장자리에서 자라나</b> 끝점을 가리키는 세모.
     *
     * 뿌리를 타원 위에서 잡는 것이 요점이다 — 아래쪽 고정 자리에 세모를 두면
     * 어느 쪽으로 뻗든 붙어 있지 않은 것처럼 보인다.
     */
    private static Shape tail(double cx, double cy, double a, double b,
                              double tipX, double tipY, double rootHalf) {
        double t = Math.atan2((tipY - cy) / Math.max(1e-6, b), (tipX - cx) / Math.max(1e-6, a));
        double spread = rootHalf / Math.max(8.0, (a + b) / 2);
        Path2D.Double p = new Path2D.Double();
        p.moveTo(cx + a * Math.cos(t - spread), cy + b * Math.sin(t - spread));
        p.lineTo(cx + a * Math.cos(t + spread), cy + b * Math.sin(t + spread));
        p.lineTo(tipX, tipY);
        p.closePath();
        return p;
    }

    /** 속마음 — 세모가 아니라 점점 작아지는 동그라미 둘. 가장자리에서 출발한다. */
    private static Shape thoughtDots(double cx, double cy, double a, double b,
                                     double tipX, double tipY, int fs) {
        double t = Math.atan2((tipY - cy) / Math.max(1e-6, b), (tipX - cx) / Math.max(1e-6, a));
        double ex = cx + a * Math.cos(t), ey = cy + b * Math.sin(t);
        double dx = tipX - ex, dy = tipY - ey;
        Area out = new Area();
        double[][] dots = {{0.16, Math.max(6, fs * 0.42)}, {0.56, Math.max(4, fs * 0.26)}};
        for (double[] d : dots) {
            double px = ex + dx * d[0], py = ey + dy * d[0], r = d[1];
            out.add(new Area(new Ellipse2D.Double(px - r, py - r, r * 2, r * 2)));
        }
        return out;
    }

    /* ---- 글 넣기 ---------------------------------------------------------- */

    private record Fit(int w, int h, List<String> lines) {
    }

    /**
     * 글을 어디서 끊을지 <b>모양을 보고</b> 고른다.
     *
     * 한 줄로 다 들어간다고 그냥 두면 대사가 길수록 풍선이 국수 가락이 된다.
     * 몇 가지 폭으로 끊어 보고 가로세로 비가 가장 보기 좋은 것을 고른다 —
     * 사람이 정한 폭은 넘지 않는다. 화면과 같은 규칙이다.
     */
    private Fit fit(String text, Font font, int boxW, int padX, int padY,
                    double spread, double target) {
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = probe.createGraphics();
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int lh = Math.max(1, (int) (font.getSize() * 1.35));

        Fit best = null;
        double bestScore = Double.MAX_VALUE;
        for (double frac : new double[]{1.0, 0.82, 0.68, 0.56, 0.46, 0.38}) {
            int inner = Math.max(10, (int) (boxW * frac / spread) - padX * 2);
            List<String> lines = wrap(text, fm, inner);
            int tw = lines.stream().mapToInt(fm::stringWidth).max().orElse(0);
            int th = lh * lines.size();
            int w = Math.min(boxW, (int) (tw * spread) + padX * 2);
            int h = (int) (th * spread) + padY * 2;
            double score = Math.abs((double) w / Math.max(1, h) - target);
            if (score < bestScore) {
                bestScore = score;
                best = new Fit(w, h, lines);
            }
        }
        g.dispose();
        return best;
    }

    /** 폭에 맞춰 줄을 나눈다. 한국어는 어절에서 끊고, 한 어절이 넘치면 글자로 자른다. */
    private static List<String> wrap(String text, FontMetrics fm, int maxW) {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\n")) {
            if (para.isBlank()) {
                out.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                String trial = line.isEmpty() ? word : line + " " + word;
                if (fm.stringWidth(trial) <= maxW || line.isEmpty()) {
                    if (fm.stringWidth(trial) > maxW && line.isEmpty()) {
                        // 한 어절이 폭을 넘으면 글자 단위로 쪼갠다.
                        StringBuilder piece = new StringBuilder();
                        for (char c : word.toCharArray()) {
                            if (fm.stringWidth(piece.toString() + c) > maxW
                                    && !piece.isEmpty()) {
                                out.add(piece.toString());
                                piece.setLength(0);
                            }
                            piece.append(c);
                        }
                        line = new StringBuilder(piece);
                        continue;
                    }
                    line = new StringBuilder(trial);
                } else {
                    out.add(line.toString());
                    line = new StringBuilder(word);
                }
            }
            out.add(line.toString());
        }
        return out.isEmpty() ? List.of("") : out;
    }

    /* ---- 스티커 · 효과음 --------------------------------------------------- */

    /** 이모지 스티커. 글꼴이 이모지를 못 그리면 네모가 나오므로 크기만 맞춘다. */
    private Tile sticker(JsonNode item, double scale) {
        int fs = (int) Math.max(8, item.path("size").asDouble(16) * 2.2 * scale);
        return text(item.path("text").asText(""), font(fs, false), PAPER, null, 0);
    }

    /**
     * 효과음 — 흰 글자에 굵은 검은 테두리, 그 아래 그림자.
     *
     * 레터링은 그림의 일부다. 선이 가늘면 밝은 배경에서 글자가 그냥 사라진다.
     */
    private Tile sfx(JsonNode item, double scale) {
        int fs = (int) Math.max(8, item.path("size").asDouble(16) * 2 * scale);
        int sw = (int) Math.max(3, fs * 0.13);
        return text(item.path("text").asText(""), font(fs, true), PAPER, INK, sw);
    }

    /** 글자 한 덩어리. {@code stroke} 가 0 이면 테두리 없이 그대로. */
    private Tile text(String s, Font font, Color fill, Color outline, int stroke) {
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        pg.setFont(font);
        FontMetrics fm = pg.getFontMetrics();
        int w = fm.stringWidth(s) + stroke * 4 + 4;
        int h = fm.getHeight() + stroke * 4 + Math.max(2, (int) (font.getSize() * 0.09));
        pg.dispose();

        BufferedImage tile = new BufferedImage(Math.max(1, w), Math.max(1, h),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = tile.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(font);
        float x = stroke * 2 + 2, y = stroke * 2 + g.getFontMetrics().getAscent();

        if (outline != null && stroke > 0) {
            int drop = Math.max(2, (int) (font.getSize() * 0.09));
            Shape glyph = g.getFont().createGlyphVector(g.getFontRenderContext(), s)
                    .getOutline(x, y);
            g.setStroke(new BasicStroke(stroke * 2f, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            // 그림자 먼저 — 같은 글자를 아래로 밀어 검게만.
            g.translate(0, drop);
            g.setColor(new Color(17, 17, 17, 90));
            g.draw(glyph);
            g.fill(glyph);
            g.translate(0, -drop);
            g.setColor(outline);
            g.draw(glyph);
            g.setColor(fill);
            g.fill(glyph);
        } else {
            g.setColor(INK);
            g.drawString(s, x, y);
        }
        g.dispose();
        return new Tile(tile, 0, 0);
    }

    /* ---- 글꼴 ------------------------------------------------------------- */

    /** 한글 글꼴. 못 찾으면 시스템 기본으로 — 글꼴 하나 때문에 굽기가 막히면 안 된다. */
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
                log.warn("한글 글꼴을 못 찾았습니다 — 기본 글꼴로 그립니다");
                base = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
            }
        }
        return base.deriveFont(bold ? Font.BOLD : Font.PLAIN, (float) size);
    }
}
