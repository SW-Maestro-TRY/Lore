package com.lore.webtoon.admin;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 관리자 대시보드의 「개요」 숫자(#779). 정의는 {@code webtoon/docs/analytics.md} 의 「최소 KPI 세 개」와
 * 「내부 접속 따로 표시」를 그대로 따른다 — 화면이 문서와 다른 숫자를 내면 안 된다.
 *
 * <h2>누구를 세나</h2>
 *
 * 우리 쪽 사람은 관리자({@code users.role = 'ADMIN'})와 멘토 · 지인({@code webtoon_internal_user}, #778)이다.
 * 로그인 전 줄에는 계정이 없으므로, 그 계정으로 한 번이라도 로그인한 브라우저({@code uid})를 통째로 우리 쪽으로
 * 본다. {@link Who#EXTERNAL}(기본)은 그 둘을 빼고, {@link Who#INTERNAL} 은 그 둘만, {@link Who#ALL} 은 다 센다.
 *
 * <h2>무엇을 세나</h2>
 *
 * <ul>
 *   <li>사람 = {@code coalesce(user_id, uid)} — 문서의 단위. 로그인하면 계정으로, 아니면 브라우저로 센다.</li>
 *   <li>완성 = 작업이 실제로 끝난 것({@code webtoon_job.status = 'DONE'}). 편집실 「이미지로 뽑기」(bake)가 아니다.</li>
 *   <li>가입 = 그 방문에서 새로 만든 계정 — {@code auth_done} 직전 10분 안에 생긴 계정. {@code users} 는
 *       다른 서비스와 같이 쓰는 표라 생성 시각만으로 세면 짤 · 조각 가입이 섞인다.</li>
 *   <li>기간은 KST 날짜로 받아 {@code [from 00:00, to 다음날 00:00)} 로 자른다.</li>
 * </ul>
 */
@Service
public class AdminOverview {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public enum Who { EXTERNAL, INTERNAL, ALL }

    /** 우리 쪽 계정과, 그 계정으로 로그인한 적 있는 브라우저. 모든 집계 앞에 붙는다. */
    private static final String INNER = """
            with inner_user as (
              select id as user_id from users where role = 'ADMIN'
              union select user_id from webtoon_internal_user
            ),
            inner_uid as (
              select distinct x.uid from webtoon_event x
              join inner_user iu on iu.user_id = x.user_id
              where x.uid is not null
            )
            """;

    @PersistenceContext
    private EntityManager em;

    /** 이벤트 줄 {@code e} 를 고르는 조건. */
    static String eventCut(Who who) {
        // in (...) 이 아니라 exists 로 쓴다 — 로그인 전 줄은 user_id 가 비어 있어서 in 이 참도 거짓도 아닌 값이 되고,
        // 그러면 not 을 붙여도 빠져서 외부 · 우리 쪽 어느 쪽에도 안 세진다.
        String inner = "(exists (select 1 from inner_uid w where w.uid = e.uid)"
                + " or exists (select 1 from inner_user iu where iu.user_id = e.user_id))";
        return switch (who) {
            case EXTERNAL -> " and not " + inner;
            case INTERNAL -> " and " + inner;
            case ALL -> "";
        };
    }

    /** 작업 줄 {@code j} 를 고르는 조건 — 계정이 없으면 만든 브라우저로 가른다. */
    static String jobCut(Who who) {
        String inner = "(exists (select 1 from inner_user iu where iu.user_id = j.user_id)"
                + " or exists (select 1 from inner_uid w where w.uid = j.browser_uid))";
        return switch (who) {
            case EXTERNAL -> " and not " + inner;
            case INTERNAL -> " and " + inner;
            case ALL -> "";
        };
    }

    @Transactional(readOnly = true)
    public Map<String, Object> overview(LocalDate fromDate, LocalDate toDate, Who who) {
        Instant from = fromDate.atStartOfDay(KST).toInstant();
        Instant to = toDate.plusDays(1).atStartOfDay(KST).toInstant();

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("visitors", people("session_start", from, to, who));
        totals.put("signups", signups(from, to, who));
        totals.put("createStarted", people("create_started", from, to, who));
        // 결제 — 충전 창을 연 사람, 「N원 결제하기」를 누른 사람과 누른 횟수. 누름은 결제가 아직 안 열렸어도
        // 남는다(ok=false) — 실제로 돈을 내려던 사람의 수가 궁금한 것이라서다.
        totals.put("chargeOpen", people("charge_open", from, to, who));
        totals.put("chargeSubmit", people("charge_submit", from, to, who));
        totals.put("chargeSubmitClicks", count("charge_submit", from, to, who));
        Object[] jobs = row(INNER + """
                select count(*),
                       count(*) filter (where j.status = 'DONE'),
                       count(*) filter (where j.status = 'ERROR'),
                       count(distinct coalesce(j.user_id::text, j.browser_uid)) filter (where j.status = 'DONE')
                from webtoon_job j
                where j.created_at >= :from and j.created_at < :to
                """ + jobCut(who), from, to);
        totals.put("jobs", num(jobs[0]));
        totals.put("jobsDone", num(jobs[1]));
        totals.put("jobsFailed", num(jobs[2]));
        totals.put("makersDone", num(jobs[3]));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", fromDate.toString());
        out.put("to", toDate.toString());
        out.put("who", who.name().toLowerCase());
        out.put("totals", totals);
        out.put("kpi", List.of(signupRate(from, to, who), firstDoneRate(from, to, who), revisit7d(fromDate, toDate, who)));
        out.put("daily", daily(from, to, who));
        out.put("internalVisitors", who == Who.INTERNAL ? totals.get("visitors") : people("session_start", from, to, Who.INTERNAL));
        out.put("runningJobs", num(single("""
                select count(*) from webtoon_job
                where status in ('QUEUED', 'RUNNING', 'AWAITING_SHEET', 'AWAITING_PICK', 'AWAITING_CAST', 'AWAITING_SCENES')
                """)));
        return out;
    }

    private long people(String name, Instant from, Instant to, Who who) {
        return num(single(INNER + """
                select count(distinct coalesce(e.user_id::text, e.uid)) from webtoon_event e
                where e.name = :name and e.occurred_at >= :from and e.occurred_at < :to
                """ + eventCut(who), from, to, "name", name));
    }

    private long count(String name, Instant from, Instant to, Who who) {
        return num(single(INNER + """
                select count(*) from webtoon_event e
                where e.name = :name and e.occurred_at >= :from and e.occurred_at < :to
                """ + eventCut(who), from, to, "name", name));
    }

    private long signups(Instant from, Instant to, Who who) {
        return num(single(INNER + """
                select count(distinct u.id) from webtoon_event e
                join users u on u.id = e.user_id
                where e.name = 'auth_done' and e.occurred_at >= :from and e.occurred_at < :to
                  and u.created_at between e.occurred_at - interval '10 minutes' and e.occurred_at
                """ + eventCut(who), from, to));
    }

    /** 가입전환율 — 로그인 안 한 채 들어온 사람 중 그 방문에 새로 가입한 비율(문서의 C1 → C4). */
    private Map<String, Object> signupRate(Instant from, Instant to, Who who) {
        Object[] r = row(INNER + """
                , s as (
                  select e.uid, min(e.occurred_at) as entered from webtoon_event e
                  where e.name = 'session_start' and (e.props::jsonb->>'logged_in') = 'false'
                    and e.occurred_at >= :from and e.occurred_at < :to and e.uid is not null
                """ + eventCut(who) + """
                  group by e.uid
                ),
                signed as (
                  select distinct e.uid from webtoon_event e
                  join s on s.uid = e.uid
                  join users u on u.id = e.user_id
                  where e.name = 'auth_done' and e.occurred_at >= s.entered
                    and u.created_at between e.occurred_at - interval '10 minutes' and e.occurred_at
                )
                select (select count(*) from s), (select count(*) from signed)
                """, from, to);
        return kpi("signup", "가입전환율", "로그인 안 하고 들어온 사람 → 그 방문에 가입", num(r[0]), num(r[1]));
    }

    /** 첫생성완료율 — 서버가 받은 사람 중 그 작업이 실제로 끝난 사람의 비율. */
    private Map<String, Object> firstDoneRate(Instant from, Instant to, Who who) {
        Object[] r = row(INNER + """
                , started as (
                  select distinct coalesce(e.user_id::text, e.uid) as who, e.props::jsonb->>'job' as job
                  from webtoon_event e
                  where e.name = 'create_started' and e.occurred_at >= :from and e.occurred_at < :to
                """ + eventCut(who) + """
                ),
                per_person as (
                  select s.who, bool_or(j.status = 'DONE') as finished
                  from started s left join webtoon_job j on j.public_id = s.job
                  group by s.who
                )
                select count(*), count(*) filter (where finished) from per_person
                """, from, to);
        return kpi("firstDone", "첫생성완료율", "만들기를 서버가 받은 사람 → 작업이 끝난 사람", num(r[0]), num(r[1]));
    }

    /**
     * 재방문율(7일) — 기간 안에 처음 온 사람 중 다음 날부터 7일 안에 다른 날 다시 온 비율.
     * 7일을 다 지켜본 사람만 넣으려고 첫 방문이 8일 전보다 앞선 사람만 센다. 그래서 최근 일주일만 고르면 비어 있다.
     */
    private Map<String, Object> revisit7d(LocalDate fromDate, LocalDate toDate, Who who) {
        LocalDate lastFull = LocalDate.now(KST).minusDays(8);
        LocalDate until = toDate.isBefore(lastFull) ? toDate : lastFull;
        if (until.isBefore(fromDate)) {
            Map<String, Object> m = kpi("revisit7d", "재방문율(7일)", "처음 온 사람 → 1~7일 안에 다시 온 사람", 0, 0);
            m.put("note", "첫 방문 뒤 7일이 다 지난 사람이 아직 없어요. 기간을 8일 전보다 앞으로 넓혀 보세요.");
            return m;
        }
        Query q = em.createNativeQuery(INNER + """
                , visits as (
                  select e.uid, (e.occurred_at at time zone 'Asia/Seoul')::date as day from webtoon_event e
                  where e.name = 'session_start' and e.uid is not null
                """ + eventCut(who) + """
                ),
                first_visit as (select uid, min(day) as first_day from visits group by uid),
                cohort as (select * from first_visit where first_day between cast(:d0 as date) and cast(:d1 as date))
                select count(*),
                       count(*) filter (where exists (
                         select 1 from visits v where v.uid = c.uid and v.day between c.first_day + 1 and c.first_day + 7))
                from cohort c
                """)
                .setParameter("d0", fromDate.toString())
                .setParameter("d1", until.toString());
        Object[] r = (Object[]) q.getSingleResult();
        Map<String, Object> m = kpi("revisit7d", "재방문율(7일)", "처음 온 사람 → 1~7일 안에 다시 온 사람", num(r[0]), num(r[1]));
        if (until.isBefore(toDate)) {
            m.put("note", until + " 까지 처음 온 사람만 셌어요(그 뒤는 7일이 아직 안 지났어요).");
        }
        return m;
    }

    /** 날마다 방문 · 만들기 시작 · 완성. 그래프용. */
    private List<Map<String, Object>> daily(Instant from, Instant to, Who who) {
        @SuppressWarnings("unchecked")
        List<Object[]> ev = em.createNativeQuery(INNER + """
                select (e.occurred_at at time zone 'Asia/Seoul')::date::text as day,
                       count(distinct coalesce(e.user_id::text, e.uid)) filter (where e.name = 'session_start'),
                       count(distinct coalesce(e.user_id::text, e.uid)) filter (where e.name = 'create_started')
                from webtoon_event e
                where e.name in ('session_start', 'create_started') and e.occurred_at >= :from and e.occurred_at < :to
                """ + eventCut(who) + " group by 1")
                .setParameter("from", from).setParameter("to", to).getResultList();
        @SuppressWarnings("unchecked")
        List<Object[]> done = em.createNativeQuery(INNER + """
                select (j.created_at at time zone 'Asia/Seoul')::date::text as day, count(*)
                from webtoon_job j
                where j.status = 'DONE' and j.created_at >= :from and j.created_at < :to
                """ + jobCut(who) + " group by 1")
                .setParameter("from", from).setParameter("to", to).getResultList();

        Map<String, long[]> by = new LinkedHashMap<>();
        for (LocalDate d = LocalDate.ofInstant(from, KST); d.isBefore(LocalDate.ofInstant(to, KST)); d = d.plusDays(1)) {
            by.put(d.toString(), new long[3]);
        }
        for (Object[] r : ev) {
            long[] v = by.get((String) r[0]);
            if (v != null) {
                v[0] = num(r[1]);
                v[1] = num(r[2]);
            }
        }
        for (Object[] r : done) {
            long[] v = by.get((String) r[0]);
            if (v != null) {
                v[2] = num(r[1]);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        by.forEach((day, v) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("day", day);
            m.put("visitors", v[0]);
            m.put("createStarted", v[1]);
            m.put("jobsDone", v[2]);
            out.add(m);
        });
        return out;
    }

    private static Map<String, Object> kpi(String key, String label, String def, long base, long hit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("definition", def);
        m.put("base", base);
        m.put("hit", hit);
        m.put("pct", base == 0 ? null : Math.round(1000.0 * hit / base) / 10.0);
        return m;
    }

    private Object single(String sql) {
        return em.createNativeQuery(sql).getSingleResult();
    }

    private Object single(String sql, Instant from, Instant to) {
        return em.createNativeQuery(sql).setParameter("from", from).setParameter("to", to).getSingleResult();
    }

    private Object single(String sql, Instant from, Instant to, String key, Object value) {
        return em.createNativeQuery(sql).setParameter("from", from).setParameter("to", to)
                .setParameter(key, value).getSingleResult();
    }

    private Object[] row(String sql, Instant from, Instant to) {
        return (Object[]) single(sql, from, to);
    }

    static long num(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }
}
