package com.lore.webtoon.runs;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

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

    /**
     * 말풍선 글꼴 — 편집실 화면과 <b>같은 파일</b>인 LINE Seed KR 의 굵은 꼴(OFL, jar 리소스).
     *
     * 편집실은 말풍선 글을 이 파일로 보여 준다(Editor.css 의 "LORE Bubble Base"). 예전에는
     * Jua 로 구워서 화면과 내려받은 그림의 글자 모양이 달랐고, 글자 폭이 다르니 줄이 바뀌는
     * 자리도 달랐다(2026-10-28). 같은 파일로 그려야 폭과 줄바꿈까지 같다. 항목에 고른
     * 글꼴({@code font})이 없으면 이 글꼴이다.
     *
     * 배포 서버(도커 이미지)에는 한글 시스템 글꼴이 하나도 없다. jar 에 실은 글꼴을 먼저
     * 보므로 어느 환경에서 구워도 같은 글꼴로 나온다.
     */
    private static final String FONT = "webtoon/fonts/LINESeedKR-Bold.ttf";

    /**
     * 고를 수 있는 말풍선 글꼴. 키는 편집실이 항목에 저장하는 {@code font} 값이고, 파일은
     * 편집실 화면과 같은 글꼴이다(editorCore.ts 의 BUBBLE_FONTS, Editor.css 의 "LORE Bubble …").
     * 값이 없거나 모르는 키는 기본 글꼴({@link #FONT})로 읽는다 — 옛 항목은 그대로다.
     *
     * 굵기마다 파일을 두지 않고 글꼴마다 파일 하나만 쓴다(주아는 굵기가 하나뿐이다). 화면도
     * 같은 파일 하나로 모든 굵기를 보여 주므로 외침도 같은 꼴이다.
     */
    static final Map<String, String> FONTS = Map.of(
            "maruburi", "webtoon/fonts/MaruBuri-Bold.ttf",
            "suit", "webtoon/fonts/SUIT-Bold.ttf",
            "jua", "webtoon/fonts/Jua-Regular.ttf");

    /** jar 글꼴에 없는 글자를 넘겨받을 시스템 글꼴. 하네스(strip.py)가 찾는 곳과 같은 목록이다. */
    private static final String[] SYSTEM_FONTS = {
            "C:\\Windows\\Fonts\\malgun.ttf",
            "/usr/share/fonts/truetype/nanum/NanumGothic.ttf",
            "/System/Library/Fonts/AppleSDGothicNeo.ttc",
    };

    /**
     * 글자를 재고 그리는 기준 — 화면(크롬)처럼 글자 폭을 반올림하지 않는다. 반올림하면 글자마다
     * 폭이 조금씩 붙어서 한 줄 길이가 화면과 몇 %씩 달라지고, 줄이 바뀌는 자리가 어긋난다.
     */
    private static final FontRenderContext FRC = new FontRenderContext(null,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

    /** 읽은 글꼴(크기 1). 못 읽은 것도 null 로 남겨 매번 다시 열지 않는다. */
    private final Map<String, Font> loaded = new HashMap<>();

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
        /* 글자 크기와 폭은 소수점까지 그대로 쓴다. 정수로 자르면 글자가 화면보다 작아져서
           (15px 를 1.42배 하면 21.3px 인데 21px 로 그렸다) 줄바꿈이 어긋났다. */
        double size = Math.max(7, item.path("size").asDouble(15) * scale);
        double boxW = Math.max(8, pageW * item.path("w").asDouble(40) / 100.0);

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

    private Tile bubble(JsonNode item, double boxW, double scale, double fs) {
        String variant = item.path("variant").asText("normal");
        boolean round = !"narration".equals(variant);
        boolean shout = "shout".equals(variant);
        List<Font> faces = sized(faces(item.path("font").asText("")), fs);
        /* 안쪽 여백 — 화면(Editor.css 의 .bub 0.55em 0.85em, .bub-shout 1.1em 1.2em)과 같다.
           예전에는 외침도 보통 말풍선 여백으로 재서, 외침의 글 폭이 화면보다 넓게 잡혔다. */
        double padX = fs * (shout ? 1.2 : 0.85), padY = fs * (shout ? 1.1 : 0.55);
        int stroke = (int) Math.max(2, 2.5 * scale);
        // 외침은 뾰족한 만큼 안쪽이 좁다(안쪽 반지름이 바깥의 0.8).
        double spread = round ? Math.sqrt(2) / (shout ? 0.8 : 1.0) : 1.0;

        /* 나레이션은 가장 낮은 높이를 두지 않는다(target 무한대) — 길게 늘여 쓰는 띠라서
           폭을 따라 높이까지 커지면 빈칸만 많은 상자가 된다. 화면(editorCore.paintShape)과 같다. */
        Fit fit = fit(item.path("text").asText(""), faces, boxW, padX, padY, spread,
                "narration".equals(variant) ? Double.POSITIVE_INFINITY : 2.4);

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
        int tw = (int) Math.ceil(x1 - x0), th = (int) Math.ceil(y1 - y0);
        BufferedImage tile = new BufferedImage(Math.max(1, tw), Math.max(1, th),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = tile.createGraphics();
        hints(g);

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
        g.setColor(INK);
        double lh = fs * 1.35;
        double base = baseline(faces.get(0), lh);
        double top = offY + (fit.h - lh * fit.lines.size()) / 2.0;
        for (int i = 0; i < fit.lines.size(); i++) {
            String line = fit.lines.get(i);
            if (line.isEmpty()) {
                continue;
            }
            TextLayout tl = layout(line, faces);
            // 가운데 맞춤은 줄 끝 공백을 빼고 잰다 — 화면도 줄 끝에 걸친 공백은 셈에 넣지 않는다.
            double x = "narration".equals(variant)
                    ? offX + padX
                    : offX + (fit.w - tl.getVisibleAdvance()) / 2.0;
            tl.draw(g, (float) x, (float) (top + i * lh + base));
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
                                     double tipX, double tipY, double fs) {
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

    private record Fit(double w, double h, List<String> lines) {
    }

    /**
     * 풍선 크기와 줄바꿈.
     *
     * <b>폭은 사람이 정한 그대로다.</b> 예전에는 글에 맞춰 풍선을 도로 줄였다 —
     * 몇 가지 폭으로 끊어 보고 가로세로 비가 제일 나은 것을 고르는 식이었는데,
     * 그러면 끌어도 안 커지거나 오히려 작아졌다. 끌어서 크기를 정하는 손잡이인데
     * 그 값을 프로그램이 도로 덮어쓰면 고장 난 것과 같다.
     *
     * 글은 그 폭 안에서 줄바꿈될 뿐이고, 높이만 글에 맞춰 늘어난다. 다만 글이
     * 짧다고 납작한 국수 가락이 되지는 않게 가장 낮은 높이를 둔다 —
     * 폭÷{@code target} 보다 낮아지지 않는다. 화면과 같은 규칙이다.
     *
     * 높이는 글 상자(줄들과 위아래 여백)를 {@code spread} 배 한 것이다. 화면은 여백까지 든
     * 글 상자 높이에 배율을 곱한다. 예전에는 여백을 빼고 곱해서 여러 줄짜리 풍선이 화면보다
     * 조금 납작했다.
     */
    private Fit fit(String text, List<Font> faces, double boxW, double padX, double padY,
                    double spread, double target) {
        double lh = faces.get(0).getSize2D() * 1.35;
        double inner = Math.max(10, boxW / spread - padX * 2);
        List<String> lines = wrap(text, s -> width(s, faces), inner);
        double h = Math.max((lh * lines.size() + padY * 2) * spread,
                boxW / Math.max(0.1, target));
        return new Fit(boxW, h, lines);
    }

    /**
     * 폭에 맞춰 줄을 나눈다 — 화면의 .bub(Editor.css: word-break: keep-all,
     * white-space: pre-wrap, overflow-wrap: break-word)와 같은 규칙이다.
     *
     * 한국어도 어절(띄어쓰기)에서만 끊고, 한 어절이 한 줄보다 길 때만 글자로 자른다. 사람이
     * 친 줄바꿈과 줄 맨 앞·어절 사이의 연속 공백은 그대로 둔다. 공백은 줄을 넘기지 않는다 —
     * 화면은 줄 끝에 걸친 공백을 폭에 넣지 않는다.
     *
     * {@code width} 는 글 한 줄의 폭(px)이다. 그리는 글꼴로 재야 화면과 같은 자리에서 끊긴다.
     */
    static List<String> wrap(String text, ToDoubleFunction<String> width, double maxW) {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\n")) {
            if (para.isBlank()) {
                out.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            // 줄을 시작했는가. 빈 어절(연속 공백)도 시작으로 친다 — line.isEmpty() 로
            // 가르면 맨 앞 공백이 붙을 자리가 없어 사라진다.
            boolean started = false;
            for (String word : para.split(" ")) {
                String trial = started ? line + " " + word : word;
                started = true;
                if (word.isEmpty() || width.applyAsDouble(trial) <= maxW) {
                    line = new StringBuilder(trial);
                    continue;
                }
                if (!line.toString().isBlank()) {
                    // 이 어절부터 다음 줄로. 걸쳐 있던 공백은 앞 줄 끝에서 뗀다.
                    out.add(line.toString().replaceAll(" +$", ""));
                    trial = word;
                    if (width.applyAsDouble(word) <= maxW) {
                        line = new StringBuilder(word);
                        continue;
                    }
                }
                // 한 어절이 한 줄보다 길다 — 들어가는 만큼씩 글자로 자른다.
                line = new StringBuilder();
                for (int i = 0; i < trial.length(); ) {
                    int cp = trial.codePointAt(i);
                    i += Character.charCount(cp);
                    if (!line.isEmpty()
                            && width.applyAsDouble(line + Character.toString(cp)) > maxW) {
                        out.add(line.toString());
                        line = new StringBuilder();
                    }
                    line.appendCodePoint(cp);
                }
            }
            out.add(line.toString());
        }
        return out.isEmpty() ? List.of("") : out;
    }

    /* ---- 스티커 · 효과음 --------------------------------------------------- */

    /** 이모지 스티커. 글꼴이 이모지를 못 그리면 네모가 나오므로 크기만 맞춘다. */
    private Tile sticker(JsonNode item, double scale) {
        double fs = Math.max(8, item.path("size").asDouble(16) * 2.2 * scale);
        String text = item.path("text").asText("");
        if (text.startsWith(LOU_PREFIX)) {
            Tile lou = louSticker(text.substring(LOU_PREFIX.length()), (int) fs);
            if (lou != null) {
                return lou;
            }
        }
        return text(text, sized(faces(""), fs), PAPER, null, 0);
    }

    /** 루 고래 스티커(#548) — 화면과 같은 이름의 그림(webtoon/ai/assets/lou)을 높이 1.5em 로. */
    static final String LOU_PREFIX = "lou:";

    private Tile louSticker(String name, int fs) {
        String safe = name.replaceAll("[^a-z0-9-]", "");
        if (safe.isEmpty()) {
            return null;
        }
        try (var in = BubbleArtist.class.getResourceAsStream("/webtoon/ai/assets/lou/" + safe + ".png")) {
            if (in == null) {
                return null;
            }
            BufferedImage src = javax.imageio.ImageIO.read(in);
            if (src == null) {
                return null;
            }
            int h = Math.max(8, (int) Math.round(fs * 1.5));
            int w = Math.max(1, (int) Math.round(src.getWidth() * (h / (double) src.getHeight())));
            BufferedImage tile = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = tile.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();
            return new Tile(tile, 0, 0);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /**
     * 효과음 — 흰 글자에 굵은 검은 테두리, 그 아래 그림자.
     *
     * 레터링은 그림의 일부다. 선이 가늘면 밝은 배경에서 글자가 그냥 사라진다.
     */
    private Tile sfx(JsonNode item, double scale) {
        double fs = Math.max(8, item.path("size").asDouble(16) * 2 * scale);
        int sw = (int) Math.max(3, fs * 0.13);
        // 굵기는 글꼴 파일 그대로다 — 화면도 굵은 꼴을 그대로 쓰고 가짜 굵게를 만들지 않는다.
        return text(item.path("text").asText(""), sized(faces(""), fs), PAPER, INK, sw);
    }

    /** 글자 한 덩어리. {@code stroke} 가 0 이면 테두리 없이 그대로. */
    private Tile text(String s, List<Font> faces, Color fill, Color outline, int stroke) {
        if (s.isEmpty()) {
            return null;
        }
        TextLayout tl = layout(s, faces);
        double fs = faces.get(0).getSize2D();
        int w = (int) Math.ceil(tl.getAdvance()) + stroke * 4 + 4;
        int h = (int) Math.ceil(tl.getAscent() + tl.getDescent() + tl.getLeading())
                + stroke * 4 + Math.max(2, (int) (fs * 0.09));

        BufferedImage tile = new BufferedImage(Math.max(1, w), Math.max(1, h),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = tile.createGraphics();
        hints(g);
        float x = stroke * 2 + 2, y = stroke * 2 + tl.getAscent();

        if (outline != null && stroke > 0) {
            int drop = Math.max(2, (int) (fs * 0.09));
            Shape glyph = tl.getOutline(AffineTransform.getTranslateInstance(x, y));
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
            tl.draw(g, x, y);
        }
        g.dispose();
        return new Tile(tile, 0, 0);
    }

    /* ---- 글꼴 ------------------------------------------------------------- */

    /**
     * jar 리소스를 연다. 저장소에서 바로 돌릴 때(검사·bootRun)는 같은 상대경로가 파일로
     * 있으니 위로 올라가며 찾는다 — {@link EpisodeExport} 와 같은 방식이다.
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

    /**
     * 말풍선 글꼴들(크기 1) — 고른 글꼴, 기본 글꼴(jar), 시스템 글꼴, 자바 기본 글꼴 순이다.
     *
     * 앞의 글꼴에 없는 글자만 뒤의 글꼴로 그린다({@link #layout}). 화면도 같은 순서로 넘긴다 —
     * 주아에 없는 「…」는 화면에서도 구운 그림에서도 기본 글꼴로 나온다. <b>글꼴 하나 때문에
     * 굽기가 막히면 안 된다</b> — 아무것도 못 읽으면 자바 기본 글꼴로라도 그린다.
     *
     * @param key 항목의 {@code font}. 빈 값이나 모르는 키는 기본 글꼴만 쓴다.
     */
    List<Font> faces(String key) {
        List<Font> out = new ArrayList<>();
        String chosen = FONTS.get(key);
        if (chosen != null) {
            Font f = jarFont(chosen);
            if (f != null) {
                out.add(f);
            }
        }
        Font jar = jarFont(FONT);
        if (jar != null) {
            out.add(jar);
        }
        for (String cand : SYSTEM_FONTS) {
            Font f = systemFont(cand);
            if (f != null) {
                out.add(f);
            }
        }
        out.add(new Font(Font.SANS_SERIF, Font.PLAIN, 1));
        return out;
    }

    /** jar 글꼴 하나(크기 1). 못 읽으면 null 이고 한 번만 알린다. */
    private synchronized Font jarFont(String path) {
        if (loaded.containsKey(path)) {
            return loaded.get(path);
        }
        Font font = null;
        try (InputStream in = open(path)) {
            if (in != null) {
                font = Font.createFont(Font.TRUETYPE_FONT, in);
            }
        } catch (Exception e) {     // noqa: 다음 글꼴로 그린다
            log.debug("글꼴을 못 읽었습니다 ({})", path, e);
        }
        if (font == null) {
            log.warn("말풍선 글꼴을 못 읽었습니다 ({}) — 다음 글꼴로 그립니다", path);
        }
        loaded.put(path, font);
        return font;
    }

    /** 시스템 글꼴 파일 하나(크기 1). 없으면 null — 배포 서버에는 하나도 없는 것이 보통이다. */
    private synchronized Font systemFont(String path) {
        if (loaded.containsKey(path)) {
            return loaded.get(path);
        }
        Font font = null;
        Path p = Path.of(path);
        if (Files.isRegularFile(p)) {
            try {
                font = Font.createFont(Font.TRUETYPE_FONT, p.toFile());
            } catch (Exception e) {     // noqa: 다음 후보를 본다
                log.debug("글꼴을 못 읽었습니다 ({})", path, e);
            }
        }
        loaded.put(path, font);
        return font;
    }

    /**
     * 이 크기로 맞춘 글꼴들. 커닝과 합자를 켠다 — 화면(크롬)이 기본으로 켜는 것이라, 끄면
     * 글자 사이가 달라져 줄 길이가 화면과 어긋난다.
     */
    static List<Font> sized(List<Font> faces, double size) {
        Map<TextAttribute, Object> attrs = Map.of(
                TextAttribute.SIZE, (float) size,
                TextAttribute.KERNING, TextAttribute.KERNING_ON,
                TextAttribute.LIGATURES, TextAttribute.LIGATURES_ON);
        List<Font> out = new ArrayList<>(faces.size());
        for (Font f : faces) {
            out.add(f.deriveFont(attrs));
        }
        return out;
    }

    /** 글 한 줄을 놓는다. 글자마다 그것을 그릴 수 있는 첫 글꼴이 맡는다. */
    static TextLayout layout(String s, List<Font> faces) {
        AttributedString as = new AttributedString(s);
        int i = 0;
        while (i < s.length()) {
            Font f = faceFor(s.codePointAt(i), faces);
            int j = i + Character.charCount(s.codePointAt(i));
            while (j < s.length() && faceFor(s.codePointAt(j), faces) == f) {
                j += Character.charCount(s.codePointAt(j));
            }
            as.addAttribute(TextAttribute.FONT, f, i, j);
            i = j;
        }
        return new TextLayout(as.getIterator(), FRC);
    }

    /** 이 글자를 그릴 수 있는 첫 글꼴. 아무도 못 그리면 첫 글꼴이다. */
    private static Font faceFor(int cp, List<Font> faces) {
        for (Font f : faces) {
            if (f.canDisplay(cp)) {
                return f;
            }
        }
        return faces.get(0);
    }

    /** 글 한 줄의 폭(px, 소수점까지). */
    static double width(String s, List<Font> faces) {
        return s.isEmpty() ? 0 : layout(s, faces).getAdvance();
    }

    /**
     * 줄 맨 위에서 글자 바닥선까지. 화면(CSS)은 줄 높이(1.35em)에서 글꼴 높이를 뺀 나머지를
     * 위아래로 반씩 나눠 글자를 줄 가운데에 놓는다 — 줄 높이는 첫 글꼴로 잡는다. 예전에는
     * 나누지 않고 줄 맨 위에 붙였다.
     */
    private static double baseline(Font font, double lh) {
        LineMetrics lm = font.getLineMetrics("가", FRC);
        return (lh - lm.getAscent() - lm.getDescent()) / 2 + lm.getAscent();
    }

    /** 그리는 쪽 설정. 재는 쪽({@link #FRC})과 같아야 잰 폭대로 찍힌다. */
    private static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }
}
