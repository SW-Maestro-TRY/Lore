package com.lore.webtoon.play;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.webtoon.Admins;
import com.lore.webtoon.WebtoonApi;
import com.lore.webtoon.credit.CreditGate;
import com.lore.webtoon.job.HarnessProcess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 — 체험 만화의 갈림 정리를 다시 한다(2026-11-04). 갈림 정리({@code branch.json})는 장면 단계 뒤에 저절로
 * 돌지만, 그 기능이 생기기 전에 만든 체험 만화에는 없다. 사건의 원래 전개 · 원래 결말 이름 · 지금 갈래를 작품 폴더에
 * {@code branch_input.json} 으로 적고 하네스 {@code run.py --branch} 를 한 편씩 부른다. 글만 다시 읽는다 — 그림은
 * 다시 안 그린다(편당 약 10원). 한 번에 하나만 돈다.
 */
@Tag(name = "Webtoon Admin", description = "관리자")
@RestController
@RequestMapping(WebtoonApi.V1 + "/admin/play/branch")
@ConditionalOnProperty(name = "lore.webtoon.python.direct", havingValue = "true")
public class PlayBranchAdmin {

    private static final Logger log = LoggerFactory.getLogger(PlayBranchAdmin.class);

    private final Admins admins;
    private final NamedParameterJdbcTemplate sql;
    private final PlayCatalog catalog;
    private final PlayWorldService made;
    private final PlayRuns played;
    private final PlayBranch branches;
    private final HarnessProcess harness;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "play-branch-backfill");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean busy = new AtomicBoolean(false);

    public PlayBranchAdmin(Admins admins, NamedParameterJdbcTemplate sql, PlayCatalog catalog, PlayWorldService made,
                           PlayRuns played, PlayBranch branches, HarnessProcess harness) {
        this.admins = admins;
        this.sql = sql;
        this.catalog = catalog;
        this.made = made;
        this.played = played;
        this.branches = branches;
        this.harness = harness;
    }

    private void requireAdmin() {
        Long me = CreditGate.currentUser();
        if (me == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "로그인이 필요합니다");
        }
        if (!admins.isAdmin(me)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "관리자만 쓸 수 있습니다");
        }
    }

    @Operation(summary = "체험 만화 갈림 정리 상태", description = "다 만든 체험 만화마다 갈림 정리가 있는지. 지운 것은 뺀다.")
    @GetMapping
    public Map<String, Object> list() {
        requireAdmin();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows()) {
            Map<String, Object> m = new LinkedHashMap<>(row);
            Map<String, Object> b = branches.of(String.valueOf(row.get("run_id")));
            m.put("done", b != null);
            m.put("label", b == null ? "" : b.get("label"));
            m.put("group", b == null ? "" : b.get("group"));
            out.add(m);
        }
        return Map.of("runs", out, "busy", busy.get());
    }

    @Operation(summary = "갈림 정리 다시 하기", description = "all=false 면 갈림 정리가 없는 것만, true 면 전부 다시. 뒤에서 한 편씩 돈다.")
    @PostMapping
    public Map<String, Object> start(@RequestParam(name = "all", defaultValue = "false") boolean all) {
        requireAdmin();
        if (!busy.compareAndSet(false, true)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "이미 도는 중이에요.");
        }
        List<Map<String, Object>> todo = rows().stream()
                .filter(r -> all || branches.of(String.valueOf(r.get("run_id"))) == null).toList();
        worker.submit(() -> {
            try {
                for (Map<String, Object> r : todo) {
                    one(String.valueOf(r.get("run_id")), String.valueOf(r.get("play_world")), String.valueOf(r.get("play_event")));
                }
            } finally {
                busy.set(false);
            }
        });
        return Map.of("queued", todo.size());
    }

    private List<Map<String, Object>> rows() {
        return sql.queryForList("""
                select w.run_id, j.play_world, j.play_event from webtoon_job j join webtoon_work w on w.job_id = j.public_id
                 where j.play_event is not null and w.run_id is not null and w.deleted_at is null
                 order by j.created_at""", new MapSqlParameterSource());
    }

    private void one(String runId, String worldId, String eventId) {
        try {
            Map<String, Object> world = catalog.world(worldId);
            if (world == null) {
                world = made.playable(worldId, null);
            }
            Map<String, Object> event = catalog.event(world, eventId);
            List<String> canon = event == null ? List.of() : PlayWorldService.canon(event.get("canon"));
            Path dir = harness.runsDir().resolve(runId).normalize();
            if (canon.isEmpty() || !dir.startsWith(harness.runsDir().normalize()) || !Files.isDirectory(dir)) {
                log.info("갈림 정리 건너뜀 — 원래 전개나 작품 폴더가 없습니다: {} ({}/{})", runId, worldId, eventId);
                return;
            }
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("canon", canon);
            input.put("canon_end", PlayWorldService.text(event.get("canon_end"), 60));
            Map<String, String> groupEnds = played.groupEndsOf(eventId);
            input.put("groups", new ArrayList<>(groupEnds.keySet()));
            input.put("group_ends", groupEnds);
            mapper.writeValue(dir.resolve("branch_input.json").toFile(), input);
            int code = harness.run(null, List.of("--run-id", runId, "--branch"), Map.of(),
                    line -> log.info("[갈림 정리 {}] {}", runId, line));
            log.info("갈림 정리 {} — {}", runId, code == 0 && branches.of(runId) != null ? "끝" : "실패(exit=" + code + ")");
        } catch (Exception e) {
            log.warn("갈림 정리 실패: {}", runId, e);
        }
    }
}
