package com.lore.webtoon.runs;

import com.lore.webtoon.WebtoonApi;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 다시 그리기 진행을 묻는 자리 — {@code /runs/{id}} 와 <b>같은 층이 아니다.</b>
 *
 * 다시 그리기는 작품이 아니라 <b>요청 하나</b>에 붙는 번호라({@link PageRegen})
 * {@code /runs/...} 아래에 안 둔다. 편집실이 2초 간격으로 이 주소만 두드린다.
 */
@Tag(name = "Webtoon", description = "웹툰 스튜디오")
@RestController
@ConditionalOnProperty(name = "lore.webtoon.python.direct", havingValue = "true")
public class RegenController {

    private final RegenService regen;

    public RegenController(RegenService regen) {
        this.regen = regen;
    }

    @Operation(summary = "다시 그리기 진행", description = "2초 간격으로 물으면 된다.")
    @GetMapping(WebtoonApi.V1 + "/regens/{id}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String id) {
        try {
            return ResponseEntity.ok(regen.statusOf(id));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(Map.of("error", "그런 작업이 없습니다"));
        }
    }
}
