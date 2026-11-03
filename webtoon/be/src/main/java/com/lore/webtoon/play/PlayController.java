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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Webtoon", description = "웹툰 스튜디오")
@RestController
@RequestMapping(WebtoonApi.V1 + "/play")
@ConditionalOnProperty(name = "lore.webtoon.python.direct", havingValue = "true")
public class PlayController {

    private final PlayCatalog catalog;
    private final PlayWorldService made;
    private final JobController jobs;
    private final JobService service;

    public PlayController(PlayCatalog catalog, PlayWorldService made, JobController jobs, JobService service) {
        this.catalog = catalog;
        this.made = made;
        this.jobs = jobs;
        this.service = service;
    }

    @Operation(summary = "세계관 체험의 세계와 사건 목록",
            description = "운영자가 정한 세계 뒤에 사용자가 올린 세계가 붙는다. 로그인했으면 내가 만든 올리기 전 세계도 붙는다.")
    @GetMapping("/worlds")
    public Map<String, Object> worlds() {
        List<Map<String, Object>> all = new ArrayList<>(catalog.worlds().stream().map(PlayController::shown).toList());
        all.addAll(made.listed(CreditGate.currentUser()));
        return Map.of("worlds", all);
    }

    /** 운영자 세계를 화면에 줄 때 — 설정집은 시작할 때 서버가 붙이므로 빼고, 예시 주인공 칸은 비워 둔다. */
    private static Map<String, Object> shown(Map<String, Object> world) {
        Map<String, Object> out = new LinkedHashMap<>(world);
        out.remove("lorebook");
        out.putIfAbsent("examples", List.of());
        return out;
    }

    @Operation(summary = "내가 만든 세계관")
    @GetMapping("/worlds/mine")
    public Map<String, Object> mine() {
        return Map.of("worlds", made.mine(CreditGate.currentUser()));
    }

    @Operation(summary = "세계관 하나", description = "올라간 세계, 또는 내가 만든 세계")
    @GetMapping("/worlds/{id}")
    public Map<String, Object> world(@PathVariable String id) {
        return made.read(id, CreditGate.currentUser());
    }

    @Operation(summary = "세계관 만들기 · 다시 만들기",
            description = "적은 칸과 참고할 내 설정집으로 세계·사건·예시 주인공 둘을 짓는다. id 가 있으면 그 세계를 다시 만든다. "
                    + "바로 돌려주고 뒤에서 짓는다 — GET /worlds/{id} 의 making 이 idle 이 될 때까지 본다.")
    @PostMapping("/worlds/make")
    public Map<String, Object> make(@RequestBody PlayWorldService.MakeForm form) {
        return made.make(CreditGate.currentUser(), form);
    }

    @Operation(summary = "세계관 저장", description = "세계 · 등장인물 · 사건 · 예시 캐릭터(내 캐릭터 id, 둘까지)를 저장한다. "
            + "visibility 가 public 이면 세계관 목록에 올라가고 예시 캐릭터를 누구나 넣어 볼 수 있다.")
    @PostMapping("/worlds")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return made.save(null, CreditGate.currentUser(), body);
    }

    @Operation(summary = "세계관 고치기", description = "본문은 저장과 같다.")
    @PutMapping("/worlds/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return made.save(id, CreditGate.currentUser(), body);
    }

    @Operation(summary = "세계관 지우기")
    @DeleteMapping("/worlds/{id}")
    public void delete(@PathVariable String id) {
        made.delete(id, CreditGate.currentUser());
    }

    /** 세계·사건·캐릭터로 바로 만든다. 값·크레딧·안전 검사는 /nh/create 그대로다. */
    @Operation(summary = "세계관 체험 시작", description = "응답의 id 는 일반 작업 id 다. 후보 고르기 없이 끝까지 간다.")
    @PostMapping("/runs")
    public ResponseEntity<Map<String, Object>> start(HttpServletRequest request, @RequestBody RunRequest form) {
        Long user = CreditGate.currentUser();
        Map<String, Object> world = catalog.world(form.worldId());
        if (world == null && form.worldId() != null) {
            world = made.playable(form.worldId(), user);
        }
        Map<String, Object> event = catalog.event(world, form.eventId());
        if (world == null || event == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "없는 세계나 사건이에요.");
        }
        String name = service.characterName(form.characterId(), user, form.uid());
        if (name == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "캐릭터를 골라 주세요.");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> lorebook = (Map<String, Object>) world.get("lorebook");
        return jobs.create(request, new JobService.CreateRequest(
                name, null, (String) world.get("genre"), null, form.style(), null, null, null,
                form.agreeIp(), false, form.uid(), form.characterId(), null, form.quality(), form.language(),
                null, PlayWorldService.situation(event, Map.of()), null, null, lorebook, (List<String>) null));
    }

    public record RunRequest(String uid,
                             @JsonProperty("world_id") String worldId,
                             @JsonProperty("event_id") String eventId,
                             @JsonProperty("character_id") String characterId,
                             @JsonProperty("agree_ip") Boolean agreeIp,
                             String style, String quality, String language) {}
}
