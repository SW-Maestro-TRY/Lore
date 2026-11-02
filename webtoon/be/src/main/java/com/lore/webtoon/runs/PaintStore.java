package com.lore.webtoon.runs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 편집실에서 장 위에 직접 그린 것(리터치)을 읽고 쓴다 — <b>담기 전에 값을 깎는다.</b>
 *
 * <h2>모양</h2>
 *
 * <pre>{"v": 1, "strokes": [
 *   {"k": "b", "c": "#1a1a1a", "w": 60, "p": [x, y, x, y, …]},   // 붓
 *   {"k": "e",                 "w": 140, "p": [x, y, …]}         // 지우개
 * ]}</pre>
 *
 * 자리({@code p})와 굵기({@code w})는 그림 크기에 대한 만분율이다 — x 와 굵기는 그림 폭의, y 는
 * 그림 높이의 1/{@value #UNIT}. 그래서 화면에서 몇 px 로 보며 그렸든, 1080 짜리에 굽든 원본에 굽든
 * 같은 자리 같은 굵기다. 지우개는 <b>그린 층만</b> 지운다(그림은 원래 안 바뀐다). 선은 그린 순서대로
 * 겹친다 — 지우개 뒤에 그린 붓 선은 안 지워진다. 화면({@code editorCore.ts} 의 paintStrokes)과
 * 굽는 쪽({@link BubbleArtist#paint})이 같은 순서 · 같은 곡선으로 그린다.
 *
 * <h2>크기 한도</h2>
 *
 * 한 장에 점 {@value #MAX_POINTS}개 · 선 {@value #MAX_STROKES}개 · 선 하나에 점 {@value #MAX_STROKE_POINTS}개까지.
 * 점 하나가 JSON 으로 열 글자 남짓이라 한 장이 많아야 150KB 쯤이다. 편집실이 같은 한도에서 먼저
 * 멈추므로, 여기서 넘는 것을 잘라 내는 것은 화면을 거치지 않고 온 값일 때뿐이다.
 */
@Service
public class PaintStore {

    private static final Logger log = LoggerFactory.getLogger(PaintStore.class);

    /** 자리·굵기의 눈금 — 그림 폭(높이)을 이만큼으로 나눈다. */
    public static final int UNIT = 10000;
    static final int MAX_POINTS = 12000;
    static final int MAX_STROKES = 500;
    static final int MAX_STROKE_POINTS = 4000;
    /** 굵기 — 폭의 0.05% ~ 20%. */
    static final int W_MIN = 5;
    static final int W_MAX = 2000;

    private static final Pattern COLOR = Pattern.compile("^#[0-9a-f]{6}$");

    private final PaintRepository paints;
    private final ObjectMapper mapper = new ObjectMapper();

    public PaintStore(PaintRepository paints) {
        this.paints = paints;
    }

    /** 이 회차에 그린 것. {장 번호: {"v":1,"strokes":[…]}} — 안 그린 장은 없다. */
    @Transactional(readOnly = true)
    public Map<Integer, JsonNode> read(String runId, int episode) {
        Map<Integer, JsonNode> out = new LinkedHashMap<>();
        for (WebtoonPaint one : paints.findByRunIdAndEpisode(runId, episode)) {
            try {
                JsonNode got = mapper.readTree(one.getDataJson());
                if (got.path("strokes").size() > 0) {
                    out.put(one.getPageNo(), got);
                }
            } catch (Exception e) {     // 담을 때 깎았으므로 여기서 깨지면 사람 손이다
                log.error("그린 것을 못 읽었습니다 — 그 장은 비워서 엽니다 (run={}, 장={})",
                        runId, one.getPageNo(), e);
            }
        }
        return out;
    }

    /**
     * 한 장에 그린 것을 통째로 담는다. -> 담긴 선 수
     *
     * 다 지웠으면(선이 하나도 없으면) 줄을 지운다 — 다시 구울 때 옛 선이 되살아나면 안 된다.
     */
    @Transactional
    public int save(String runId, int episode, int pageNo, JsonNode raw) {
        ObjectNode clean = clean(raw);
        int n = clean.path("strokes").size();
        var had = paints.findByRunIdAndEpisodeAndPageNo(runId, episode, pageNo);
        if (n == 0) {
            had.ifPresent(paints::delete);
            return 0;
        }
        String json = clean.toString();
        Instant now = Instant.now();
        had.ifPresentOrElse(one -> one.update(json, now),
                () -> paints.save(WebtoonPaint.of(runId, episode, pageNo, json, now)));
        return n;
    }

    /* ---- 깎기 ------------------------------------------------------------- */

    ObjectNode clean(JsonNode raw) {
        ObjectNode out = mapper.createObjectNode();
        out.put("v", 1);
        ArrayNode strokes = out.putArray("strokes");
        if (raw == null || !raw.isObject()) {
            return out;
        }
        int points = 0;
        for (JsonNode s : raw.path("strokes")) {
            if (strokes.size() >= MAX_STROKES) {
                break;
            }
            ObjectNode one = cleanStroke(s, MAX_POINTS - points);
            if (one == null) {
                continue;
            }
            points += one.path("p").size() / 2;
            strokes.add(one);
            if (points >= MAX_POINTS) {
                break;
            }
        }
        return out;
    }

    /** 선 하나. 점이 없거나 종류를 모르면 {@code null}. */
    ObjectNode cleanStroke(JsonNode raw, int room) {
        if (raw == null || !raw.isObject() || room <= 0) {
            return null;
        }
        String kind = raw.path("k").asText("");
        if (!"b".equals(kind) && !"e".equals(kind)) {
            return null;
        }
        JsonNode p = raw.path("p");
        if (!p.isArray() || p.size() < 2) {
            return null;
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("k", kind);
        if ("b".equals(kind)) {
            String c = raw.path("c").asText("").trim().toLowerCase();
            out.put("c", COLOR.matcher(c).matches() ? c : "#111111");
        }
        out.put("w", (int) Math.round(clamp(raw.path("w"), W_MIN, W_MAX, 60)));
        ArrayNode pts = out.putArray("p");
        int pairs = Math.min(p.size() / 2, Math.min(room, MAX_STROKE_POINTS));
        for (int i = 0; i < pairs; i++) {
            JsonNode x = p.get(2 * i), y = p.get(2 * i + 1);
            if (x == null || y == null || !x.isNumber() || !y.isNumber()) {
                continue;            // 숫자가 아닌 점만 빼고 나머지 선은 살린다
            }
            pts.add((int) Math.round(clamp(x, 0, UNIT, 0)));
            pts.add((int) Math.round(clamp(y, 0, UNIT, 0)));
        }
        return pts.size() >= 2 ? out : null;
    }

    private static double clamp(JsonNode node, double lo, double hi, double fallback) {
        if (node == null || !node.isNumber()) {
            return fallback;
        }
        double v = node.asDouble(fallback);
        if (Double.isNaN(v)) {
            return fallback;
        }
        return Math.max(lo, Math.min(hi, v));
    }
}
