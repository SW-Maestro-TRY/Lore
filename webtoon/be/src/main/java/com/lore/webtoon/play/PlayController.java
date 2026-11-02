package com.lore.webtoon.play;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.webtoon.WebtoonApi;
import com.lore.webtoon.credit.CreditGate;
import com.lore.webtoon.job.JobController;
import com.lore.webtoon.job.JobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Webtoon", description = "웹툰 스튜디오")
@RestController
@RequestMapping(WebtoonApi.V1 + "/play")
@ConditionalOnProperty(name = "lore.webtoon.python.direct", havingValue = "true")
public class PlayController {

    private final PlayCatalog catalog;
    private final JobController jobs;
    private final JobService service;

    public PlayController(PlayCatalog catalog, JobController jobs, JobService service) {
        this.catalog = catalog;
        this.jobs = jobs;
        this.service = service;
    }

    @Operation(summary = "세계관 체험의 세계와 사건 목록")
    @GetMapping("/worlds")
    public Map<String, Object> worlds() {
        return Map.of("worlds", catalog.worlds());
    }

    /** 세계·사건·캐릭터로 바로 만든다. 값·크레딧·안전 검사는 /nh/create 그대로다. */
    @Operation(summary = "세계관 체험 시작", description = "응답의 id 는 일반 작업 id 다. 후보 고르기 없이 끝까지 간다.")
    @PostMapping("/runs")
    public ResponseEntity<Map<String, Object>> start(HttpServletRequest request, @RequestBody RunRequest form) {
        Map<String, Object> world = catalog.world(form.worldId());
        Map<String, Object> event = catalog.event(world, form.eventId());
        if (world == null || event == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "없는 세계나 사건이에요.");
        }
        String name = service.characterName(form.characterId(), CreditGate.currentUser(), form.uid());
        if (name == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "캐릭터를 골라 주세요.");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> lorebook = (Map<String, Object>) world.get("lorebook");
        return jobs.create(request, new JobService.CreateRequest(
                name, null, (String) world.get("genre"), null, form.style(), null, null, null,
                form.agreeIp(), false, form.uid(), form.characterId(), null, form.quality(), form.language(),
                null, (String) event.get("situation"), null, null, lorebook, (List<String>) null));
    }

    public record RunRequest(String uid,
                             @JsonProperty("world_id") String worldId,
                             @JsonProperty("event_id") String eventId,
                             @JsonProperty("character_id") String characterId,
                             @JsonProperty("agree_ip") Boolean agreeIp,
                             String style, String quality, String language) {}
}
