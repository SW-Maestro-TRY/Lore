package com.lore.webtoon.play;

import com.lore.webtoon.runs.RunService;
import com.lore.webtoon.work.WebtoonWork;
import com.lore.webtoon.work.WebtoonWorkRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 세계관 체험으로 만든 만화(2026-11-03). 시작할 때 작업에 세계 · 사건을 적어 두고({@link #mark}), 그것으로
 * 「내가 체험한 만화」와 「이 사건으로 만든 만화」를 모은다. 카드는 둘러보기 · 마이페이지와 같은 모양
 * ({@link RunService#cardOf})에 세계 · 사건 이름이 붙는다. 그림이 다 나온 작품만 싣는다 — 만드는 중인 것은
 * 첫 화면의 「만들던 웹툰」이 따로 보여 준다.
 */
@Component
public class PlayRuns {

    private static final int LIMIT = 60;

    private final NamedParameterJdbcTemplate sql;
    private final WebtoonWorkRepository works;
    private final RunService runs;
    private final PlayBranch branches;

    public PlayRuns(NamedParameterJdbcTemplate sql, WebtoonWorkRepository works, RunService runs, PlayBranch branches) {
        this.sql = sql;
        this.works = works;
        this.runs = runs;
        this.branches = branches;
    }

    /** 체험으로 시작한 작업에 세계 · 사건을 적는다. */
    @Transactional
    public void mark(String jobPublicId, String worldId, String eventId) {
        sql.update("update webtoon_job set play_world = :w, play_event = :e where public_id = :id",
                new MapSqlParameterSource().addValue("w", worldId).addValue("e", eventId).addValue("id", jobPublicId));
    }

    /** 내가 체험으로 만든 것 — 계정이면 계정 것과 이어진 브라우저 것, 아니면 이 브라우저 것. 비공개도 준다. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> mine(Long userId, Collection<String> uids) {
        if (userId == null && (uids == null || uids.isEmpty())) {
            return List.of();
        }
        List<Map<String, Object>> rows = sql.queryForList("""
                select public_id, play_world, play_event from webtoon_job
                 where play_event is not null
                   and (user_id = :u or browser_uid in (:uids))
                 order by created_at desc limit %d""".formatted(LIMIT),
                new MapSqlParameterSource().addValue("u", userId == null ? -1L : userId)
                        .addValue("uids", uids == null || uids.isEmpty() ? List.of("") : uids));
        return cards(rows, false);
    }

    /** 이 사건으로 만든 공개 작품 — 관리자가 숨기거나 지운 것, 비공개 · 링크 공개는 뺀다. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> ofEvent(String eventId) {
        List<Map<String, Object>> rows = sql.queryForList("""
                select public_id, play_world, play_event from webtoon_job
                 where play_event = :e
                 order by created_at desc limit %d""".formatted(LIMIT),
                new MapSqlParameterSource().addValue("e", eventId));
        return cards(rows, true);
    }

    /**
     * 이 사건의 만화들이 이미 묶인 갈래 이름 — 공개 · 비공개 가리지 않고, 많이 묶인 순. 새 체험이 시작될 때 하네스에
     * 넘겨, 같은 선택을 한 만화가 같은 이름으로 묶이게 한다(갈래 이름일 뿐 작품 내용은 넘기지 않는다).
     */
    @Transactional(readOnly = true)
    public List<String> groupsOf(String eventId) {
        return new ArrayList<>(groupEndsOf(eventId).keySet());
    }

    /**
     * 위와 같은 갈래 이름(많이 묶인 순)마다 그 갈래의 결말 한 줄. 그 이름을 처음 지은 만화(label 이 갈래 이름과 같은 것)의
     * 결말을 먼저 쓰고, 없으면 가장 최근 만화의 결말을 쓴다. 갈림 정리가 이름의 낱말이 아니라 실제로 고른 길을 보고
     * 묶게 한다(#989).
     */
    @Transactional(readOnly = true)
    public Map<String, String> groupEndsOf(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return Map.of();
        }
        List<Map<String, Object>> rows = sql.queryForList("""
                select w.run_id from webtoon_job j join webtoon_work w on w.job_id = j.public_id
                 where j.play_event = :e and w.run_id is not null and w.deleted_at is null
                 order by j.created_at desc limit %d""".formatted(LIMIT),
                new MapSqlParameterSource().addValue("e", eventId));
        Map<String, Integer> count = new LinkedHashMap<>();
        Map<String, String> ends = new LinkedHashMap<>();
        Map<String, Boolean> namer = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> b = branches.of(String.valueOf(row.get("run_id")));
            if (b != null && b.get("group") instanceof String g && !g.isBlank()) {
                count.merge(g, 1, Integer::sum);
                String end = b.get("end") instanceof String e ? e.trim() : "";
                boolean named = g.equals(b.get("label"));
                if (!end.isEmpty() && (!ends.containsKey(g) || (named && !namer.getOrDefault(g, false)))) {
                    ends.put(g, end);
                    namer.put(g, named);
                }
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        count.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> out.put(e.getKey(), ends.getOrDefault(e.getKey(), "")));
        return out;
    }

    /**
     * 최근 체험 만화 — 공개된 것만, 최근 순. {@code worldId} 를 주면 그 세계 것만. 홈의 「세계관 체험 만화」 칸과
     * 세계 안 사건별 선반이 쓴다(2026-11-04). 일반 둘러보기에는 체험 만화가 안 섞이므로 여기가 그 자리다.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> recent(String worldId) {
        boolean one = worldId != null && !worldId.isBlank();
        List<Map<String, Object>> rows = sql.queryForList("""
                select public_id, play_world, play_event from webtoon_job
                 where play_event is not null %s
                 order by created_at desc limit %d""".formatted(one ? "and play_world = :w" : "", LIMIT),
                new MapSqlParameterSource().addValue("w", one ? worldId : ""));
        return cards(rows, true);
    }

    private List<Map<String, Object>> cards(List<Map<String, Object>> rows, boolean publicOnly) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            WebtoonWork work = works.findByJobId(String.valueOf(row.get("public_id"))).orElse(null);
            if (work == null || work.getRunId() == null || work.isTrashed()) {
                continue;
            }
            if (publicOnly && (!work.isPublic() || work.getModeration() != null)) {
                continue;
            }
            Map<String, Object> card = runs.cardOf(work.getRunId());
            if (card == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>(card);
            m.put("run_id", work.getRunId());                       // 연재로 묶여도 이 화를 연다
            m.put("world_id", row.get("play_world"));
            m.put("event_id", row.get("play_event"));
            Map<String, Object> branch = branches.of(work.getRunId());     // 원래 전개와 갈라진 곳 — 없으면 안 싣는다
            if (branch != null) {
                m.put("branch", branch);
            }
            out.add(m);
        }
        return out;
    }
}
