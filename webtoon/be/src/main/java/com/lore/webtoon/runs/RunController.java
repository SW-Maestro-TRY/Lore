package com.lore.webtoon.runs;

import com.lore.webtoon.WebtoonApi;
import com.lore.webtoon.art.PageStore;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
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

    public RunController(RunService runs, PageStore pages, EpisodeExport export) {
        this.runs = runs;
        this.pages = pages;
        this.export = export;
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

    /** 띠 오른쪽에 적을 한 줄 — 파이썬의 {@code episode_caption} 과 같은 모양. */
    private static String captionOf(Map<String, Object> meta) {
        Object name = meta.get("character");
        String who = name == null ? "" : String.valueOf(name).trim();
        return who.isEmpty() ? "1화" : who + " · 1화";
    }

    /* {@code raw=1} 은 안 받는다 — 그건 <b>얹은 것(말풍선) 없는 밑그림</b>을
       달라는 뜻이고 편집실만 쓴다. 편집실은 아직 하네스에 있으므로(얹는 것을
       그 폴더에 저장한다) 그 요청은 넓은 그물로 그냥 흘려 보낸다. 여기서
       받아 S3 것을 주면 이미 구워진 그림 위에 또 얹게 된다. */
    /**
     * 완성본의 한 장. <b>그림을 실어 보내지 않고 있는 자리를 알려 준다.</b>
     *
     * 그림은 이미 S3 에 있고 공개된 것은 CloudFront 가 내준다 — 그걸 이
     * 서버가 받아서 다시 흘려보내면 같은 파일이 두 번 오가고, 한 장에 수백
     * KB 인 것이 목록 한 화면에 수십 장이다. 비공개 자리에 있는 것은 잠깐
     * 열리는 주소를 만들어 준다({@code PageStore.urlOf}).
     *
     * 여기 그림에는 <b>LORE 표시가 안 붙는다</b> — 표시는 밖으로 나가는 파일에만
     * 붙는다({@link #episode}).
     */
    @Operation(summary = "완성본의 한 장",
            description = "S3(또는 잠깐 열리는 주소)로 넘긴다. 없으면 404.")
    @GetMapping(value = "/{runId}/page/{no}", params = "!raw")
    public ResponseEntity<Void> page(@PathVariable String runId, @PathVariable int no,
                                     @RequestParam(defaultValue = "1080") int w) {
        String where = pages.urlOf(runId, no, w);
        return where == null
                ? ResponseEntity.notFound().build()
                : ResponseEntity.status(302).location(URI.create(where)).build();
    }
}
