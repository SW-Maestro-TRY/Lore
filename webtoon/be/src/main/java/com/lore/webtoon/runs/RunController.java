package com.lore.webtoon.runs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.webtoon.WebtoonApi;
import com.lore.webtoon.art.PageStore;
import com.lore.webtoon.story.StoryStore;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * 다 만들어진 작품을 읽는 자리 — 둘러보기 · 완성본 · 그 그림.
 *
 * <h2>여기가 켜지면 하네스가 목록에서 빠진다</h2>
 *
 * 만들기는 이미 스프링이 직접 한다({@code JobController}). 그런데 <b>읽는
 * 쪽은 그대로 파이썬으로 새고 있었다</b> — 넓은 그물({@code WebtoonController})이
 * {@code /runs} 를 통째로 하네스로 넘긴다. 그래서 만든 것은 DB 에 다 있는데
 * 목록과 완성본만 하네스 폴더에 매여 있었다.
 *
 * 같은 스위치({@code lore.webtoon.python.direct})로 켠다 — 만들기와 읽기가
 * 따로 놀면, 스프링이 만든 작품을 파이썬 목록이 모르는 상태가 된다.
 *
 * <h2>주소를 하나씩 적는 이유</h2>
 *
 * 스프링은 더 구체적인 매핑을 먼저 고른다. 여기 적힌 것만 이 클래스가 받고,
 * 안 적은 것(편집실이 쓰는 {@code raw=1} 처럼)은 그대로 넓은 그물로 떨어져
 * 하네스가 받는다 — <b>한 번에 다 안 옮겨도 된다.</b>
 */
@Tag(name = "Webtoon", description = "웹툰 스튜디오")
@RestController
@RequestMapping(RunController.PREFIX)
@ConditionalOnProperty(name = "lore.webtoon.python.direct", havingValue = "true")
public class RunController {

    static final String PREFIX = WebtoonApi.V1 + "/runs";

    private final RunService runs;
    private final PageStore pages;
    private final EpisodeExport export;
    private final OverlayStore overlays;
    private final BakeService bakery;
    private final StoryStore stories;
    private final RegenService regen;
    /* **경계에서는 Map 으로 주고받는다.**
     *
     * 이 앱의 HTTP 변환기는 Jackson 3(tools.jackson) 인데, 얹은 것을 다루는
     * 코드는 저장소의 다른 곳과 같은 Jackson 2 를 쓴다. 그 타입을 그대로
     * 내보내면 변환기가 못 읽어서, 읽기는 <b>속살(array·boolean…)이 그대로
     * 나가고</b> 쓰기는 500 이 났다 — 실제로 그랬다. 안에서는 JsonNode 로,
     * 밖에서는 Map 으로 오간다. */
    private final ObjectMapper mapper = new ObjectMapper();

    public RunController(RunService runs, PageStore pages, EpisodeExport export,
                         OverlayStore overlays, BakeService bakery, StoryStore stories,
                         RegenService regen) {
        this.runs = runs;
        this.pages = pages;
        this.export = export;
        this.overlays = overlays;
        this.bakery = bakery;
        this.stories = stories;
        this.regen = regen;
    }

    /**
     * 제목을 고친다. <b>빈 값으로 부르면 지운다</b> — 모델이 지은 이름으로
     * 돌아간다.
     *
     * 아직 이야기를 안 고른 작품(만드는 중)이면 404 다 — 편집실은 다 만든
     * 작품에서만 연다.
     */
    @Operation(summary = "제목 고치기", description = "title 이 비어 있으면 원래 이름으로 되돌린다.")
    @PostMapping("/{runId}/title")
    public ResponseEntity<Map<String, Object>> title(@PathVariable String runId,
                                                      @RequestBody Map<String, Object> body) {
        try {
            String got = stories.editTitle(runId, String.valueOf(body.getOrDefault("title", "")));
            return ResponseEntity.ok(Map.of("title", got));
        } catch (java.util.NoSuchElementException e) {
            return ResponseEntity.status(404).body(Map.of("error", "그런 작품이 없습니다"));
        }
    }

    /* ---- 편집실 ----------------------------------------------------------- */

    /**
     * 이 작품에 얹어 둔 것 — 말풍선 · 스티커 · 효과음.
     *
     * 편집실이 열릴 때 한 번 부른다. 없으면 빈 것을 준다 — 화면은 그것을
     * "아직 안 얹었다" 로 읽는다.
     */
    @Operation(summary = "얹은 것 읽기")
    @GetMapping("/{runId}/overlay")
    public Map<String, Object> overlay(@PathVariable String runId,
                                       @RequestParam(defaultValue = "1") int ep) {
        return asMap(overlays.read(runId, ep));
    }

    /**
     * 얹은 것을 담는다. <b>편집실이 글을 고칠 때마다 부른다</b>(자동 저장).
     *
     * 통째로 덮어쓴다 — 무엇이 지워졌는지를 따로 알려 주지 않아도 되고,
     * 마지막으로 본 화면이 곧 저장된 것이 된다.
     */
    @Operation(summary = "얹은 것 저장")
    @PostMapping("/{runId}/overlay")
    public Map<String, Object> saveOverlay(@PathVariable String runId,
                                           @RequestParam(defaultValue = "1") int ep,
                                           @RequestBody(required = false) Map<String, Object> body) {
        return Map.of("ok", true, "items", overlays.save(runId, ep, asNode(body)));
    }

    /**
     * 얹은 것을 그림에 굽는다 — 그리고 받을 주소를 알려 준다.
     *
     * 화면은 굽자마자 그 주소로 파일을 받는다. 원본은 안 건드리므로 편집실은
     * 계속 밑그림을 보고, 몇 번을 구워도 말풍선이 겹쳐 쌓이지 않는다.
     */
    @Operation(summary = "구워서 파일로",
            description = "본문에 얹은 것이 실려 오면 먼저 저장하고 굽는다.")
    @PostMapping("/{runId}/bake")
    public Map<String, Object> bake(@PathVariable String runId,
                                    @RequestParam(defaultValue = "1") int ep,
                                    @RequestBody(required = false) Map<String, Object> body) {
        int items = body != null && body.get("scenes") != null
                ? overlays.save(runId, ep, asNode(body))
                : overlays.count(overlays.read(runId, ep));
        int sheets = bakery.bake(runId, ep);
        return Map.of("ok", true, "items", items, "pages", sheets,
                "url", PREFIX + "/" + runId + "/episode.png");
    }

    /**
     * 둘러보기 목록. {@code mine=1} 이면 이 브라우저가 만든 것만.
     *
     * 봉투를 안 씌운다 — 이 화면은 프로토타입에서 옮겨 온 것이라 파이썬이
     * 주던 {@code {"runs": [...]}} 를 그대로 읽는다.
     */
    @Operation(summary = "작품 목록",
            description = "mine=1 이면 uid 가 만든 것만 (비공개 포함).")
    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String mine,
                                    @RequestParam(required = false) String uid) {
        boolean onlyMine = "1".equals(mine) || "true".equalsIgnoreCase(mine);
        List<Map<String, Object>> found = onlyMine ? runs.madeBy(uid) : runs.browse();
        return Map.of("runs", found);
    }

    @Operation(summary = "완성본 한 편")
    @GetMapping("/{runId}/result")
    public ResponseEntity<Map<String, Object>> result(@PathVariable String runId) {
        Map<String, Object> found = runs.result(runId);
        return found == null
                ? ResponseEntity.status(404).body(Map.of("error", "그런 작품이 없습니다"))
                : ResponseEntity.ok(found);
    }

    /**
     * 편집실이 여는 자리(#281). {@code ep} 는 지금 늘 1이다 — 한 편짜리라
     * 다른 값을 줘도 같은 작품이 열린다(이어그리기가 붙으면 여기서 갈린다).
     *
     * 이 주소가 없어서 편집실이 죽은 파이썬 프록시(8800)로 떨어져 502 가
     * 났다 — 만들기·완성본 읽기는 스프링으로 옮겨 왔는데 이 자리만 빠져
     * 있었다.
     */
    @Operation(summary = "편집실 데이터", description = "컷은 페이지당 하나다 — 파이프라인이 컷 경계를 안 남긴다.")
    @GetMapping("/{runId}/episode")
    public ResponseEntity<Map<String, Object>> episode(@PathVariable String runId,
                                                        @RequestParam(defaultValue = "1") int ep) {
        Map<String, Object> found = runs.episode(runId, ep);
        return found == null
                ? ResponseEntity.status(404).body(Map.of("error", "그 회차에 그려진 장이 없습니다"))
                : ResponseEntity.ok(found);
    }

    /**
     * 한 편을 통째로 내려받는다 — <b>LORE 표시가 붙는 유일한 길이다.</b>
     *
     * 만든 사람은 결과물을 SNS 에 올린다. 그때 그림만 돌아다니고 어디서 만든
     * 것인지가 안 남으면 퍼질수록 우리는 아무것도 못 얻는다. 반대로 표시가
     * 있으면 그림 한 장이 그대로 유입 경로가 된다.
     *
     * 화면에서 보는 그림에는 안 붙는다({@link #page}) — 표시는 밖으로 나가는
     * 파일의 성질이지 저장물의 성질이 아니다.
     */
    @Operation(summary = "한 편 내려받기",
            description = "낱장을 이어 붙이고 LORE 표시를 찍어서 준다.")
    @GetMapping(value = "/{runId}/episode.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> episode(@PathVariable String runId) {
        Map<String, Object> meta = runs.result(runId);
        if (meta == null) {
            return ResponseEntity.notFound().build();
        }
        byte[] png = export.png(runId, captionOf(meta));
        if (png == null) {
            return ResponseEntity.notFound().build();
        }
        /* 받는 파일 이름은 작품 번호다. 제목을 쓰면 한글·따옴표가 섞여 브라우저마다
           다르게 저장되고, 같은 작품을 두 번 받으면 이름이 겹친다. */
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + runId + ".png\"")
                .contentType(MediaType.IMAGE_PNG)
                .body(png);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(JsonNode node) {
        return mapper.convertValue(node, Map.class);
    }

    private JsonNode asNode(Map<String, Object> body) {
        return body == null ? null : mapper.valueToTree(body);
    }

    /** 띠 오른쪽에 적을 한 줄 — 파이썬의 {@code episode_caption} 과 같은 모양. */
    private static String captionOf(Map<String, Object> meta) {
        Object name = meta.get("character");
        String who = name == null ? "" : String.valueOf(name).trim();
        return who.isEmpty() ? "1화" : who + " · 1화";
    }

    /* ---- 다시 그리기 ------------------------------------------------------- */

    /**
     * 이 장을 다시 그린다. <b>여기서부터 실제로 돈이 나간다.</b>
     *
     * 곧바로 그리지 않는다 — 이미지 호출이라 줄을 선다({@link RegenService}).
     * 화면은 돌려받은 번호로 {@link #regenStatus} 를 2초 간격으로 물어야 한다.
     */
    @Operation(summary = "장 다시 그리기", description = "곧바로 안 그린다 — id 로 진행을 물어야 한다.")
    @PostMapping("/{runId}/scenes/{no}/regen")
    public ResponseEntity<Map<String, Object>> regen(@PathVariable String runId,
                                                      @PathVariable int no,
                                                      @RequestBody(required = false)
                                                      Map<String, Object> body) {
        try {
            String note = body == null ? "" : String.valueOf(body.getOrDefault("feedback", ""));
            String id = regen.start(runId, no, note);
            return ResponseEntity.ok(regen.statusOf(id));
        } catch (java.util.NoSuchElementException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 지난 판 목록. 편집실이 되돌리기 목록을 그릴 때 쓴다.
     */
    @Operation(summary = "지난 판 목록")
    @GetMapping("/{runId}/scenes/{no}/versions")
    public Map<String, Object> versions(@PathVariable String runId, @PathVariable int no) {
        return Map.of("versions", regen.versionsOf(runId, no));
    }

    /** 지난 판 그림 하나. 목록 썸네일 크기(w)로 줄여 준다. */
    @Operation(summary = "지난 판 그림")
    @GetMapping(value = "/{runId}/scenes/{no}/versions/{v}", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> versionImage(@PathVariable String runId, @PathVariable int no,
                                               @PathVariable int v,
                                               @RequestParam(defaultValue = "1080") int w) {
        byte[] img = regen.versionImage(runId, no, v, w);
        return img == null
                ? ResponseEntity.notFound().build()
                : ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(img);
    }

    /** 지난 판으로 되돌린다. 되돌리기 전 그림도 판본으로 남는다. */
    @Operation(summary = "지난 판으로 되돌리기")
    @PostMapping("/{runId}/scenes/{no}/revert")
    public ResponseEntity<Map<String, Object>> revert(@PathVariable String runId,
                                                       @PathVariable int no,
                                                       @RequestBody Map<String, Object> body) {
        try {
            int version = Integer.parseInt(String.valueOf(body.get("version")));
            List<Map<String, Object>> versions = regen.revert(runId, no, version);
            return ResponseEntity.ok(Map.of("ok", true, "versions", versions));
        } catch (NumberFormatException e) {
            return ResponseEntity.status(400).body(Map.of("error", "version 이 필요합니다"));
        } catch (java.util.NoSuchElementException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 완성본의 한 장. <b>구운 것이 있으면 그것</b>을 준다.
     *
     * 그림을 실어 보내지 않고 있는 자리를 알려 준다 — 그림은 이미 S3 에 있고
     * 공개된 것은 CloudFront 가 내준다. 이 서버가 받아서 다시 흘려보내면 같은
     * 파일이 두 번 오가고, 한 장에 수백 KB 인 것이 목록 한 화면에 수십 장이다.
     *
     * {@code raw=1} 은 <b>얹은 것 없는 밑그림</b>을 달라는 뜻이고 편집실만
     * 쓴다 — 편집실은 말풍선을 따로 그려 얹으므로, 밑그림에까지 구워져 있으면
     * 두 겹으로 보인다.
     *
     * 여기 그림에는 <b>LORE 표시가 안 붙는다</b> — 표시는 밖으로 나가는 파일에만
     * 붙는다({@link #episode}).
     */
    @Operation(summary = "완성본의 한 장",
            description = "구운 것이 있으면 그것. raw=1 이면 밑그림. 없으면 404.")
    @GetMapping("/{runId}/page/{no}")
    public ResponseEntity<Void> page(@PathVariable String runId, @PathVariable int no,
                                     @RequestParam(defaultValue = "1080") int w,
                                     @RequestParam(required = false) String raw) {
        boolean wantRaw = raw != null && !raw.isBlank() && !"0".equals(raw);
        String where = wantRaw ? null : pages.urlOfKey(bakery.keyOf(runId, no));
        if (where == null) {
            where = pages.urlOf(runId, no, w);
        }
        return where == null
                ? ResponseEntity.notFound().build()
                : ResponseEntity.status(302).location(URI.create(where)).build();
    }
}
