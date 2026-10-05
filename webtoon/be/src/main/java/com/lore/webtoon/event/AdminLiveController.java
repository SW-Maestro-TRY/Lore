package com.lore.webtoon.event;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.webtoon.Admins;
import com.lore.webtoon.WebtoonApi;
import com.lore.webtoon.credit.CreditGate;
import com.lore.webtoon.job.JobStatus;
import com.lore.webtoon.job.WebtoonJobRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 관리자 홈의 실시간 패널(#...) — <b>관리자만</b>.
 *
 * 유입(landing) · 생성 시작(create_started) · 완성(bake) 수와 어디서 왔는지(utm_source,
 * utm_medium, utm_campaign, ref_host)를 날짜 범위로 센다. 수는 전부 unique uid 로 센다 —
 * 한 사람이 여러 번 들어와도 한 번으로.
 */
@Tag(name = "Webtoon", description = "웹툰 스튜디오")
@RestController
@RequestMapping(WebtoonApi.V1 + "/admin/live")
public class AdminLiveController {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int ACTIVE_MINUTES = 5;
    private static final int TOP_LIMIT = 10;

    private final Admins admins;
    private final WebtoonJobRepository jobs;

    @PersistenceContext
    private EntityManager em;

    public AdminLiveController(Admins admins, WebtoonJobRepository jobs) {
        this.admins = admins;
        this.jobs = jobs;
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

    @Operation(summary = "실시간 집계",
            description = "날짜 범위(KST, 포함) 안의 유입·생성·완성 수와 UTM 출처별 분해. 범위를 안 넘기면 오늘.")
    @GetMapping
    public Map<String, Object> live(@RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to) {
        requireAdmin();

        LocalDate today = LocalDate.now(KST);
        LocalDate fromDate = parse(from, today);
        LocalDate toDate = parse(to, today);
        if (toDate.isBefore(fromDate)) {
            LocalDate t = fromDate;
            fromDate = toDate;
            toDate = t;
        }
        Instant fromAt = fromDate.atStartOfDay(KST).toInstant();
        Instant toAt = toDate.plusDays(1).atStartOfDay(KST).toInstant();

        Instant activeCut = Instant.now().minus(Duration.ofMinutes(ACTIVE_MINUTES));

        Map<String, Long> funnelAll = countsByName(fromAt, toAt);
        List<Map<String, Object>> sources = groupByUtm("source", fromAt, toAt);
        List<Map<String, Object>> mediums = groupByUtm("medium", fromAt, toAt);
        List<Map<String, Object>> campaigns = groupByUtm("campaign", fromAt, toAt);
        List<Map<String, Object>> refHosts = groupByRefHost(fromAt, toAt);
        List<Map<String, Object>> allEvents = allEventCounts(fromAt, toAt);
        long newSessions = countDistinctUidForSessionKind(fromAt, toAt, "new");
        long returningSessions = countDistinctUidForSessionKind(fromAt, toAt, "returning");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", fromDate.toString());
        out.put("to", toDate.toString());
        out.put("activeMinutes", ACTIVE_MINUTES);
        out.put("activeUids", countDistinctUidSince(activeCut));
        out.put("runningJobs", jobs.countByStatusIn(List.of(JobStatus.RUNNING)));
        /* 이름 매핑은 webtoon/fe 가 보내는 그대로다:
           - session_start: 들어온 사람(한 세션당 한 번, kind=new|returning)
           - create_started: 「만들기」가 실제로 서버에 작업을 만든 순간(job id 가 생긴 시점)
           - bake: 편집실에서 한 편을 구운 때(완성 신호). */
        Map<String, Long> funnel = new LinkedHashMap<>();
        funnel.put("landing", funnelAll.getOrDefault("session_start", 0L));
        funnel.put("newSessions", newSessions);
        funnel.put("returningSessions", returningSessions);
        funnel.put("signedIn", funnelAll.getOrDefault("auth_done", 0L));
        funnel.put("createStarted", funnelAll.getOrDefault("create_started", 0L));
        funnel.put("baked", funnelAll.getOrDefault("bake", 0L));
        funnel.put("readEnd", funnelAll.getOrDefault("read_end", 0L));
        funnel.put("nextEpisodeClicked", funnelAll.getOrDefault("next_episode_click", 0L));
        funnel.put("kakaoShared", funnelAll.getOrDefault("kakao", 0L));
        funnel.put("downloadClicked", funnelAll.getOrDefault("download_click", 0L));
        funnel.put("createFailed", funnelAll.getOrDefault("create_failed", 0L));
        funnel.put("createBlocked", funnelAll.getOrDefault("create_blocked", 0L));
        out.put("funnel", funnel);
        /* 발표 7번 장 「결과를 어느 단계까지 손대고 싶어하나」에 쓰는 분해.
           이야기 단계부터 완성 뒤 장(=페이지) 단계까지, 다시 그린 사용자 수 네 칸. */
        Map<String, Long> revisions = new LinkedHashMap<>();
        revisions.put("story", funnelAll.getOrDefault("story_retry", 0L));
        revisions.put("sheet", funnelAll.getOrDefault("sheet_fix", 0L)
                + funnelAll.getOrDefault("sheet_restore", 0L));
        revisions.put("scene", funnelAll.getOrDefault("scene_retry", 0L)
                + funnelAll.getOrDefault("scene_restore", 0L));
        revisions.put("panel", funnelAll.getOrDefault("regen_start", 0L));
        out.put("revisions", revisions);
        out.put("survey", surveyAnswers(fromAt, toAt));
        out.put("sources", sources);
        out.put("mediums", mediums);
        out.put("campaigns", campaigns);
        out.put("refHosts", refHosts);
        out.put("events", allEvents);
        return out;
    }

    /** 발표 7번 장이 묻는 S1·S2·S6·S7 등 설문 답의 긍정 비율.
     *  answers 는 TEXT JSON — SCALE 질문은 4~5 를 긍정, YES_PARTLY_NO 는 "yes" 를 긍정으로 센다. */
    private Map<String, Map<String, Long>> surveyAnswers(Instant from, Instant to) {
        Map<String, Map<String, Long>> out = new LinkedHashMap<>();
        /* SCALE 질문: S0 전체만족 · S1 내 캐릭터 얘기 맞나 · S2 캐릭터 일관성 · S4 성격 · S6 1화 재미. */
        for (String key : new String[]{"S0", "S1", "S2", "S4", "S6"}) {
            out.put(key, scaleAnswers(from, to, key));
        }
        /* YES_PARTLY_NO: S3 설정 반영 · S5 줄거리 반영 · S7 다음 화 궁금. */
        for (String key : new String[]{"S3", "S5", "S7"}) {
            out.put(key, categoricalAnswers(from, to, key, List.of("yes", "partly", "no")));
        }
        return out;
    }

    private Map<String, Long> scaleAnswers(Instant from, Instant to, String key) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "select substring(answers from '\"" + key + "\"\\s*:\\s*(\\d)'), count(*)"
                                + " from webtoon_feedback"
                                + " where created_at >= :from and created_at < :to"
                                + "   and answers like :k"
                                + " group by 1")
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("k", "%\"" + key + "\"%")
                .getResultList();
        Map<String, Long> out = new LinkedHashMap<>();
        long positive = 0, total = 0;
        for (Object[] r : rows) {
            if (r[0] == null) continue;
            long n = ((Number) r[1]).longValue();
            total += n;
            int v;
            try { v = Integer.parseInt((String) r[0]); } catch (NumberFormatException e) { continue; }
            if (v >= 4) positive += n;
        }
        out.put("positive", positive);
        out.put("total", total);
        return out;
    }

    private Map<String, Long> categoricalAnswers(Instant from, Instant to, String key, List<String> values) {
        Map<String, Long> out = new LinkedHashMap<>();
        long positive = 0, total = 0;
        for (String v : values) {
            Object r = em.createNativeQuery(
                            "select count(*) from webtoon_feedback"
                                    + " where created_at >= :from and created_at < :to"
                                    + "   and answers like :p")
                    .setParameter("from", from)
                    .setParameter("to", to)
                    .setParameter("p", "%\"" + key + "\":\"" + v + "\"%")
                    .getSingleResult();
            long n = r == null ? 0L : ((Number) r).longValue();
            if ("yes".equals(v)) positive = n;
            total += n;
        }
        out.put("positive", positive);
        out.put("total", total);
        return out;
    }

    private LocalDate parse(String s, LocalDate fallback) {
        if (s == null || s.isBlank()) return fallback;
        try {
            return LocalDate.parse(s);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private long countDistinctUidSince(Instant since) {
        Object r = em.createNativeQuery(
                        "select count(distinct uid) from webtoon_event where occurred_at >= :cut and uid is not null")
                .setParameter("cut", since)
                .getSingleResult();
        return r == null ? 0L : ((Number) r).longValue();
    }

    /** 통계 카드가 바로 꺼내 쓸 이름별 unique uid. 전체 집계는 allEventCounts 가 따로 돌린다. */
    private Map<String, Long> countsByName(Instant from, Instant to) {
        List<String> names = Arrays.asList(
                "session_start", "auth_done", "create_started", "bake", "kakao", "download_click",
                "read_end", "next_episode_click", "create_failed", "create_blocked",
                "story_retry", "sheet_fix", "sheet_restore", "scene_retry", "scene_restore", "regen_start");
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "select name, count(distinct uid) from webtoon_event"
                                + " where name in (:names) and occurred_at >= :from and occurred_at < :to"
                                + "   and uid is not null"
                                + " group by name")
                .setParameter("names", names)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
        Map<String, Long> out = new LinkedHashMap<>();
        for (Object[] r : rows) {
            out.put((String) r[0], ((Number) r[1]).longValue());
        }
        return out;
    }

    /** UTM 조각(source|medium|campaign)별 unique uid 와 깔때기. */
    private List<Map<String, Object>> groupByUtm(String part, Instant from, Instant to) {
        /* source 칸의 포맷이 환경마다 다를 수 있어 둘을 모두 수용한다:
           - "pinterest" 같은 날것(브라우저가 utm_source 하나만 뽑아 넣은 경우)
           - "utm_source=pinterest&utm_medium=post&..." 같은 쿼리 조각(원본 보존)
           source 는 둘 중 utm_source 가 있으면 그걸, 아니면 날것을 쓴다. */
        String column = "source".equals(part)
                ? "coalesce(substring(source from 'utm_source=([^&]+)'), source)"
                : "substring(source from 'utm_" + part + "=([^&]+)')";
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "select key, "
                                + " count(distinct uid) filter (where name = 'session_start') as visitors,"
                                + " count(distinct uid) filter (where name = 'create_started') as started,"
                                + " count(distinct uid) filter (where name = 'bake') as baked"
                                + " from (select " + column + " as key, uid, name"
                                + "       from webtoon_event"
                                + "       where occurred_at >= :from and occurred_at < :to"
                                + "         and uid is not null"
                                + "         and source is not null and source <> '') t"
                                + " where key is not null and key <> ''"
                                + " group by key"
                                + " order by visitors desc nulls last, started desc nulls last"
                                + " limit :lim")
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("lim", TOP_LIMIT)
                .getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", r[0]);
            m.put("visitors", r[1] == null ? 0L : ((Number) r[1]).longValue());
            m.put("started", r[2] == null ? 0L : ((Number) r[2]).longValue());
            m.put("baked", r[3] == null ? 0L : ((Number) r[3]).longValue());
            out.add(m);
        }
        return out;
    }

    /** session_start 의 props 안 kind("new" / "returning") 별 unique uid.
     *  props 는 TEXT 로 JSON 이 들어 있어 like 로 긁는다 — EventService 가 값을 짧은 기호로만
     *  허용하기 때문에 "kind":"new" / "kind":"returning" 외의 형태로는 안 들어온다. */
    private long countDistinctUidForSessionKind(Instant from, Instant to, String kind) {
        Object r = em.createNativeQuery(
                        "select count(distinct uid) from webtoon_event"
                                + " where name = 'session_start'"
                                + "   and occurred_at >= :from and occurred_at < :to"
                                + "   and uid is not null"
                                + "   and props like :p")
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("p", "%\"kind\":\"" + kind + "\"%")
                .getSingleResult();
        return r == null ? 0L : ((Number) r).longValue();
    }

    /** 범위 안 모든 이벤트 이름을 unique uid 와 raw 수로 센다. 상위 50개까지. */
    private List<Map<String, Object>> allEventCounts(Instant from, Instant to) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "select name, count(distinct uid), count(*)"
                                + " from webtoon_event"
                                + " where occurred_at >= :from and occurred_at < :to"
                                + " group by name"
                                + " order by count(distinct uid) desc, count(*) desc"
                                + " limit 50")
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", r[0]);
            m.put("uids", ((Number) r[1]).longValue());
            m.put("total", ((Number) r[2]).longValue());
            out.add(m);
        }
        return out;
    }

    /** UTM 없는 외부 유입(ref_host) 순위. */
    private List<Map<String, Object>> groupByRefHost(Instant from, Instant to) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "select ref_host, count(distinct uid)"
                                + " from webtoon_event"
                                + " where occurred_at >= :from and occurred_at < :to"
                                + "   and uid is not null"
                                + "   and (source is null or source = '')"
                                + "   and ref_host is not null and ref_host <> ''"
                                + " group by ref_host"
                                + " order by count(distinct uid) desc"
                                + " limit :lim")
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("lim", TOP_LIMIT)
                .getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("host", r[0]);
            m.put("visitors", ((Number) r[1]).longValue());
            out.add(m);
        }
        return out;
    }
}
