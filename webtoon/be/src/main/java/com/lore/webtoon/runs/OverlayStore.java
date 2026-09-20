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
import java.util.List;
import java.util.Set;

/**
 * 얹은 것을 읽고 쓴다 — <b>담기 전에 값을 깎는다.</b>
 *
 * 브라우저가 보낸 값이라 무엇이든 올 수 있다. 모르는 값은 조용히 기본값으로
 * 떨어뜨린다 — 여기서 세우면 항목 하나가 이상할 때 화 전체를 못 굽는다.
 *
 * 깎는 규칙은 파이썬({@code landing/overlay.py} 의 {@code clean_item})과 같다.
 * 한쪽만 바꾸면 같은 편집실이 어디에 저장하느냐에 따라 다르게 굽힌다.
 */
@Service
public class OverlayStore {

    private static final Logger log = LoggerFactory.getLogger(OverlayStore.class);

    static final Set<String> TYPES = Set.of("bubble", "sticker", "sfx");
    static final List<String> VARIANTS =
            List.of("normal", "shout", "whisper", "thought", "narration", "flash");
    /** 꼬리가 달리는 종류. 나레이션·회상에는 애초에 꼬리가 없다. */
    static final Set<String> TAILED = Set.of("normal", "shout", "whisper", "thought");

    /** 말풍선 하나가 화 전체를 덮는 것을 막는다. */
    private static final int TEXT_MAX = 400;

    private final OverlayRepository overlays;
    private final ObjectMapper mapper = new ObjectMapper();

    public OverlayStore(OverlayRepository overlays) {
        this.overlays = overlays;
    }

    /** 이 작품에 얹은 것. 없으면 빈 것 — 화면은 그것을 "아직 안 얹었다" 로 읽는다. */
    @Transactional(readOnly = true)
    public JsonNode read(String runId, int episode) {
        return overlays.findByRunIdAndEpisode(runId, episode)
                .map(one -> parse(one.getDataJson()))
                .orElseGet(this::empty);
    }

    /**
     * 얹은 것을 담는다. -> 담긴 항목 수
     *
     * 화면은 글을 고칠 때마다 이걸 부른다(자동 저장). 그래서 통째로 덮어쓴다 —
     * 무엇이 지워졌는지를 따로 알려 주지 않아도 되고, 마지막으로 본 화면이 곧
     * 저장된 것이 된다.
     */
    @Transactional
    public int save(String runId, int episode, JsonNode raw) {
        ObjectNode clean = clean(raw);
        String json = clean.toString();
        Instant now = Instant.now();
        overlays.findByRunIdAndEpisode(runId, episode)
                .ifPresentOrElse(one -> one.update(json, now),
                        () -> overlays.save(WebtoonOverlay.of(runId, episode, json, now)));
        return count(clean);
    }

    /** 얹은 것이 몇 개인가. 화면이 "n개 얹음" 을 적는다. */
    public int count(JsonNode data) {
        int n = 0;
        JsonNode scenes = data.path("scenes");
        for (JsonNode scene : scenes) {
            n += scene.path("items").size();
        }
        return n;
    }

    /* ---- 깎기 ------------------------------------------------------------- */

    /**
     * 보낸 것 전체 -> 담을 모양.
     *
     * <pre>{"scenes": {"1": {"ref_w": 720, "items": [...]}}, "gaps": {"1": 2}}</pre>
     *
     * {@code gaps} 는 사람이 편집실에서 고친 여백(장 뒤의 쉼, 0~3)이다.
     */
    ObjectNode clean(JsonNode raw) {
        ObjectNode out = mapper.createObjectNode();
        ObjectNode scenes = out.putObject("scenes");
        ObjectNode gaps = out.putObject("gaps");
        if (raw == null || !raw.isObject()) {
            return out;
        }

        JsonNode from = raw.path("scenes");
        from.fieldNames().forEachRemaining(key -> {
            int no = intOf(key, 0);
            JsonNode val = from.path(key);
            if (no < 1 || !val.isObject()) {
                return;
            }
            ObjectNode scene = scenes.putObject(String.valueOf(no));
            scene.put("ref_w", clamp(val.path("ref_w"), 80, 8000, 720));
            ArrayNode items = scene.putArray("items");
            for (JsonNode item : val.path("items")) {
                ObjectNode one = cleanItem(item);
                if (one != null) {
                    items.add(one);
                }
            }
            /* **빈 장도 남긴다.** "여기 있던 말풍선을 지웠다" 와 "한 번도 안
               열었다" 가 구분돼야, 다시 구울 때 옛 말풍선이 되살아나지 않는다. */
        });

        JsonNode gapsFrom = raw.path("gaps");
        gapsFrom.fieldNames().forEachRemaining(key -> {
            int no = intOf(key, 0);
            int g = gapsFrom.path(key).asInt(-1);
            if (no >= 1 && g >= 0 && g <= 3) {
                gaps.put(String.valueOf(no), g);
            }
        });
        return out;
    }

    /** 항목 하나. 종류를 모르거나 글이 비면 {@code null} — 굽는 쪽이 어차피 못 그린다. */
    ObjectNode cleanItem(JsonNode raw) {
        if (raw == null || !raw.isObject()) {
            return null;
        }
        String kind = raw.path("type").asText("").trim().toLowerCase();
        if (!TYPES.contains(kind)) {
            return null;
        }
        String text = raw.path("text").asText("");
        if (text.isBlank()) {
            return null;
        }
        String variant = raw.path("variant").asText("").trim().toLowerCase();
        if ("bubble".equals(kind) && !VARIANTS.contains(variant)) {
            variant = "normal";
        }
        String tail = raw.path("tail").asText("").trim().toLowerCase();
        if (!List.of("left", "right", "none").contains(tail)) {
            tail = "left";
        }

        ObjectNode out = mapper.createObjectNode();
        out.put("type", kind);
        out.put("variant", variant);
        out.put("text", text.length() <= TEXT_MAX ? text : text.substring(0, TEXT_MAX));
        out.put("x", clamp(raw.path("x"), -20, 110, 20));
        out.put("y", clamp(raw.path("y"), -20, 110, 30));
        out.put("w", clamp(raw.path("w"), 3, 100, 40));
        out.put("size", clamp(raw.path("size"), 4, 200, 15));
        out.put("rot", clamp(raw.path("rot"), -180, 180, 0));
        out.put("tail", tail);
        /* 꼬리 끝은 사람이 끌어다 놓은 자리다. 풍선 크기에 대한 %(가로·세로)라
           그림 크기가 달라져도 같은 곳을 가리킨다. 안 보내면 옛 규칙(왼쪽/
           오른쪽)에서 만든다 — 예전에 저장한 것이 그대로 열려야 한다. */
        boolean hasTip = raw.hasNonNull("tx") && raw.hasNonNull("ty");
        out.put("tx", clamp(raw.path("tx"), -300, 400, "right".equals(tail) ? 78 : 22));
        out.put("ty", clamp(raw.path("ty"), -300, 400, 152));
        if (!hasTip && "none".equals(tail)) {
            out.put("tx", 22);
            out.put("ty", 152);
        }
        return out;
    }

    private ObjectNode empty() {
        ObjectNode out = mapper.createObjectNode();
        out.putObject("scenes");
        out.putObject("gaps");
        return out;
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {     // noqa: 담을 때 깎았으므로 여기서 깨지면 사람 손이다
            log.error("얹은 것을 못 읽었습니다 — 빈 것으로 엽니다", e);
            return empty();
        }
    }

    private static int intOf(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double clamp(JsonNode node, double lo, double hi, double fallback) {
        if (node == null || !node.isNumber()) {
            return fallback;
        }
        return Math.max(lo, Math.min(hi, node.asDouble(fallback)));
    }
}
