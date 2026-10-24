package com.lore.webtoon.series;

import com.lore.webtoon.WebtoonApi;
import com.lore.webtoon.credit.CreditGate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping(WebtoonApi.V1 + "/series")
@ConditionalOnProperty(name = "lore.webtoon.python.direct", havingValue = "true")
public class SeriesController {
    private final SeriesService series;
    public SeriesController(SeriesService series) { this.series=series; }
    @GetMapping("/{runId}")
    public Map<String,Object> chapters(@PathVariable String runId) {
        return series.chapters(runId, CreditGate.currentUser());
    }
    @GetMapping("/{runId}/draft")
    public Map<String,Object> draft(@PathVariable String runId) {
        return series.draft(runId, CreditGate.currentUser());
    }
    @PostMapping("/{runId}/draft")
    public Map<String,Object> save(@PathVariable String runId, @RequestBody SeriesService.Form form) {
        return series.saveDraft(runId, CreditGate.currentUser(), form);
    }
    @PostMapping("/{runId}/digest")
    public Map<String,Object> digest(@PathVariable String runId) {
        return series.startDigest(runId, CreditGate.currentUser());
    }
    @PostMapping("/{runId}/next")
    public Map<String,Object> next(@PathVariable String runId, @RequestBody SeriesService.Form form) {
        return series.createNext(runId, CreditGate.currentUser(), form);
    }
}
