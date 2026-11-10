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
    private final PlayRuns played;
    private final com.lore.webtoon.character.CharacterOwner owner;

    public PlayController(PlayCatalog catalog, PlayWorldService made, JobController jobs, JobService service,
                          PlayRuns played, com.lore.webtoon.character.CharacterOwner owner) {
        this.catalog = catalog;
        this.made = made;
        this.jobs = jobs;
        this.service = service;
        this.played = played;
        this.owner = owner;
    }

    @Operation(summary = "세계관 사건 생성 크레딧", description = "오픈 세일 적용가와 할인 전 값을 함께 돌려준다.")
    @GetMapping("/allowance")
    public Map<String, Object> allowance(HttpServletRequest request) {
        return jobs.playAllowance(request);
    }

    @Operation(summary = "내가 체험한 만화", description = "/play/runs 로 시작해 그림까지 나온 작품. 로그인이면 계정과 이어진 브라우저, "
            + "아니면 X-Lore-Uid 브라우저. 카드는 둘러보기와 같은 모양에 world_id · world_name · event_id · event_title 이 붙는다.")
    @GetMapping("/runs/mine")
    public Map<String, Object> myRuns(@org.springframework.web.bind.annotation.RequestHeader(value = "X-Lore-Uid", required = false) String uid) {
        Long user = CreditGate.currentUser();
        return Map.of("runs", named(played.mine(user, owner.uidsOf(user, uid))));
    }

    @Operation(summary = "이 사건으로 만든 만화", description = "공개 작품만(비공개 · 링크 공개 · 관리자 처리된 것은 뺀다).")
    @GetMapping("/events/{eventId}/runs")
    public Map<String, Object> eventRuns(@PathVariable String eventId) {
        return Map.of("runs", named(played.ofEvent(eventId)));
    }

    @Operation(summary = "최근 체험 만화", description = "공개 작품만, 최근 순. world 를 주면 그 세계 것만.")
    @GetMapping("/runs")
    public Map<String, Object> recentRuns(@org.springframework.web.bind.annotation.RequestParam(required = false) String world) {
        return Map.of("runs", named(played.recent(world)));
    }

    /** 카드에 세계 · 사건 이름을 붙인다. 운영 세계면 파일에서, 사용자 세계면 DB 에서. */
    private List<Map<String, Object>> named(List<Map<String, Object>> cards) {
        Long user = CreditGate.currentUser();
        Map<String, Map<String, Object>> worlds = new java.util.HashMap<>();
        for (Map<String, Object> card : cards) {
            String wid = String.valueOf(card.get("world_id"));
            Map<String, Object> w = worlds.computeIfAbsent(wid, k -> {
                Map<String, Object> found = catalog.world(k);
                if (found == null) {
                    found = made.playable(k, user);
                }
                return found == null ? Map.of() : found;
            });
            card.put("world_name", w.getOrDefault("name", ""));
            Map<String, Object> ev = w.isEmpty() ? null : catalog.event(w, String.valueOf(card.get("event_id")));
            card.put("event_title", ev == null ? "" : ev.getOrDefault("title", ""));
        }
        return cards;
    }

    @Operation(summary = "세계관 체험의 세계와 사건 목록",
            description = "운영자가 정한 세계 뒤에 사용자가 올린 세계가 붙는다. 로그인했으면 내가 만든 올리기 전 세계도 붙는다.")
    @GetMapping("/worlds")
    public Map<String, Object> worlds() {
        Long user = CreditGate.currentUser();
        List<Map<String, Object>> all = new ArrayList<>();
        for (Map<String, Object> w : catalog.worlds()) {                 // 관리자가 고친 운영 세계는 그 편집본으로
            Map<String, Object> edited = made.overrideView(String.valueOf(w.get("id")), user);
            all.add(edited != null ? edited : shown(w));
        }
        all.addAll(made.listed(user));
        return Map.of("worlds", all);
    }

    /**
     * 운영자 세계를 화면에 줄 때 — 설정집은 시작할 때 서버가 붙이므로 빼고, 사건마다 예시 캐릭터 id 를 붙인다
     * (example_people 중 사진이 있는 사람, {@link PlayPeople}).
     */
    Map<String, Object> shown(Map<String, Object> world) {
        Map<String, Object> out = new LinkedHashMap<>(world);
        out.remove("lorebook");
        out.remove("secret");
        List<Map<String, Object>> cast = new ArrayList<>();
        for (Object c : PlayWorldService.list(world.get("cast"))) {
            if (c instanceof Map<?, ?> person) {
                Map<String, Object> visible = new LinkedHashMap<>();
                person.forEach((k, val) -> visible.put(String.valueOf(k), val));
                visible.remove("secret");
                cast.add(visible);
            }
        }
        out.put("cast", cast);
        out.putIfAbsent("examples", List.of());
        List<Map<String, Object>> events = new ArrayList<>();
        for (Object e : PlayWorldService.list(world.get("events"))) {
            if (e instanceof Map<?, ?> v) {
                Map<String, Object> m = new LinkedHashMap<>();
                v.forEach((k, val) -> m.put(String.valueOf(k), val));
                m.remove("secret");
                List<String> ids = new ArrayList<>(PlayWorldService.ids(v.get("examples")));
                catalog.examples(String.valueOf(v.get("id"))).forEach(id -> {
                    if (!ids.contains(id)) {
                        ids.add(id);
                    }
                });
                m.put("examples", ids);
                events.add(m);
            }
        }
        out.put("events", events);
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

    /** 세계·사건·캐릭터로 바로 만든다. 안전 검사는 /nh/create 와 같고 사건 할인 값을 받는다. */
    @Operation(summary = "세계관 체험 시작", description = "응답의 id 는 일반 작업 id 다. 후보 고르기 없이 끝까지 간다.")
    @PostMapping("/runs")
    public ResponseEntity<Map<String, Object>> start(HttpServletRequest request, @RequestBody RunRequest form) {
        Long user = CreditGate.currentUser();
        boolean edited = made.overridden(form.worldId());
        Map<String, Object> world = edited ? made.playable(form.worldId(), user) : catalog.world(form.worldId());
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
        String situation = !edited && catalog.world(form.worldId()) != null
                ? catalogSituation(world, event, name)
                : PlayWorldService.situation(event, Map.of());
        situation = PlayWorldService.withCanon(situation, event, played.groupEndsOf(form.eventId()));
        situation = PlayWorldService.withSecrets(situation, world, event);   // 비공개 설정 — 이야기만 안다(2026-11-08)
        ResponseEntity<Map<String, Object>> started = jobs.createPlay(request, new JobService.CreateRequest(
                name, null, (String) world.get("genre"), null, form.style(), null, null, null,
                form.agreeIp(), false, form.uid(), form.characterId(), null, form.quality(), form.language(),
                null, situation, null, null, lorebook, (List<String>) null, true));
        if (started.getBody() != null && started.getBody().get("id") instanceof String jobId) {
            played.mark(jobId, form.worldId(), form.eventId());
        }
        return started;
    }

    /**
     * 운영 세계 사건의 상황 — 장소, 그리고 하은이 사건마다 고른 인물(events[].cast: 이름)의 역할 · 설정을 붙인다.
     * 운영 세계의 설정집(worlds.json lorebook)에는 인물이 없어서, 고른 인물을 상황에 직접 적어야 이야기가 그 인물을
     * 그 모습대로 쓴다. 사건에 안 고른 인물과 주인공으로 넣은 사람은 적지 않는다.
     */
    static String catalogSituation(Map<String, Object> world, Map<String, Object> event, String protagonist) {
        String base = PlayWorldService.situation(event, Map.of());
        List<String> picked = PlayWorldService.ids(event.get("cast")).stream()
                .filter(n -> !n.equals(protagonist)).toList();                 // 주인공으로 고른 사람은 또 적지 않는다
        if (picked.isEmpty()) {
            return base;
        }
        StringBuilder s = new StringBuilder(base).append("\n이 사건에 나오는 인물:");
        for (String name : picked) {
            Map<?, ?> who = PlayWorldService.list(world.get("cast")).stream()
                    .filter(c -> c instanceof Map<?, ?> m && name.equals(PlayWorldService.text(m.get("name"), 60)))
                    .map(c -> (Map<?, ?>) c).findFirst().orElse(Map.of());
            String role = PlayWorldService.text(who.get("role"), 60);
            String desc = PlayWorldService.text(who.get("desc"), 600);
            s.append("\n- ").append(name);
            if (!role.isEmpty()) {
                s.append(" (").append(role).append(")");
            }
            if (!desc.isEmpty()) {
                s.append(": ").append(desc);
            }
        }
        return s.toString();
    }

    public record RunRequest(String uid,
                             @JsonProperty("world_id") String worldId,
                             @JsonProperty("event_id") String eventId,
                             @JsonProperty("character_id") String characterId,
                             @JsonProperty("agree_ip") Boolean agreeIp,
                             String style, String quality, String language) {}
}
