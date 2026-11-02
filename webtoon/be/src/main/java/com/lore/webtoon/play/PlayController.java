package com.lore.webtoon.play;

import com.lore.webtoon.WebtoonApi;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Webtoon", description = "웹툰 스튜디오")
@RestController
@RequestMapping(WebtoonApi.V1 + "/play")
public class PlayController {

    private final PlayCatalog catalog;

    public PlayController(PlayCatalog catalog) {
        this.catalog = catalog;
    }

    @Operation(summary = "세계관 체험의 세계와 사건 목록",
            description = "세계마다 lorebook 과 사건들이 온다. 만들기는 /nh/create 에 lorebook 과 사건 text(settings)를 실어 보낸다.")
    @GetMapping("/worlds")
    public Map<String, Object> worlds() {
        return Map.of("worlds", catalog.worlds());
    }
}
