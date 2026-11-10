package com.lore.webtoon.usage;

import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 하루 생성 상한을 <b>그날만</b> 바꾼 값(#617). 관리자 화면 「오늘만 바꾸기」가 쓴다.
 *
 * <h2>왜 따로 두나</h2>
 * 기본값은 설정({@code lore.webtoon.spend.*}, 운영은 Parameter Store)이고 서버가 뜰 때 한 번 읽는다.
 * 사람이 몰려 상한에 걸린 날 그 값을 올리려면 서버를 다시 띄워야 했다 — 가장 붐빌 때 웹툰 · 짤 API 가
 * 함께 1~2분 멈춘다. 그래서 그날 하루만 덮는 값을 DB 에 둔다.
 *
 * <h2>규칙</h2>
 * <ul>
 *   <li><b>날짜(한국 시각)가 붙는다.</b> 자정이 지나면 저절로 안 쓰인다 — 되돌리는 것을 잊어도 다음 날은 기본값이다.</li>
 *   <li><b>지우지 않고 쌓는다.</b> 누가 · 언제 · 얼마로 바꿨는지 남는다. 그날의 마지막 줄이 이긴다. 금액 · 편수가 둘 다
 *       빈 줄은 「기본값으로」다.</li>
 *   <li><b>서버가 다시 떠도 남는다.</b> DB 에서 다시 읽는다.</li>
 *   <li><b>30초는 기억한다.</b> 만들기 화면이 열릴 때마다 묻는 자리라 매번 DB 를 읽지 않는다. 이 서버에서 바꾼 값은
 *       바로 반영한다(서버가 여러 대면 다른 서버는 30초 안에 따라온다).</li>
 *   <li><b>못 읽으면 마지막으로 읽은 값을 쓴다.</b> 이 표 하나 때문에 만들기 전체가 500 으로 막히면 안 된다.</li>
 * </ul>
 */
@Component
public class SpendOverrides {

    private static final Logger log = LoggerFactory.getLogger(SpendOverrides.class);

    /** 하루가 언제 바뀌는가 — {@link SpendGuard} 와 같은 자. */
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /** 이만큼은 DB 를 다시 안 읽는다. */
    static final long KEEP_MS = 30_000;

    /**
     * 그날 덮은 값. 비어 있는 칸은 기본값을 쓴다.
     *
     * @param day   한국 날짜 — 이 날에만 쓴다
     * @param krw   금액 상한(원). null 이면 기본값
     * @param runs  편수 상한. null 이면 기본값
     * @param setBy 바꾼 관리자 계정 번호
     * @param setAt 바꾼 때
     */
    public record DayCap(LocalDate day, Long krw, Long runs, Long setBy, Instant setAt) {
        boolean cleared() {
            return krw == null && runs == null;
        }
    }

    /** 실제로 읽고 쓰는 손. 검사에서 갈아 끼운다. */
    interface Store {
        /** 그날의 마지막 줄. 없으면 null. */
        DayCap latest(LocalDate day);

        void add(LocalDate day, Long krw, Long runs, Long by);
    }

    private final Store store;
    private final Clock clock;
    private volatile DayCap cached;
    private volatile LocalDate cachedDay;
    private volatile long cachedAt;

    @Autowired
    public SpendOverrides(DataSource dataSource) {
        this(jdbc(new JdbcTemplate(dataSource)), Clock.system(ZONE));
    }

    SpendOverrides(Store store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** 오늘 덮은 값. 없거나 「기본값으로」 했으면 null. */
    public DayCap today() {
        LocalDate day = day();
        long now = clock.millis();
        if (day.equals(cachedDay) && now - cachedAt < KEEP_MS) {
            return cached;
        }
        try {
            DayCap last = store.latest(day);
            cached = last == null || last.cleared() ? null : last;
        } catch (RuntimeException e) {      // noqa: 못 읽으면 마지막 값 — 날이 바뀌었으면 기본값
            log.warn("오늘 바꾼 상한을 못 읽었습니다 — {}", e.toString());
            if (!day.equals(cachedDay)) {
                cached = null;
            }
        }
        cachedDay = day;
        cachedAt = now;
        return cached;
    }

    /** 오늘 하루(한국 자정까지) 상한을 바꾼다. 둘 중 비운 쪽은 기본값을 쓴다. 바로 반영한다. */
    public DayCap set(Long krw, Long runs, Long by) {
        store.add(day(), krw, runs, by);
        cachedDay = null;
        return today();
    }

    /** 오늘 바꾼 것을 무른다 — 기본값으로. */
    public void clear(Long by) {
        set(null, null, by);
    }

    private LocalDate day() {
        return LocalDate.ofInstant(clock.instant(), ZONE);
    }

    private static Store jdbc(JdbcTemplate db) {
        return new Store() {
            @Override
            public DayCap latest(LocalDate day) {
                List<DayCap> rows = db.query(
                        "select day, daily_krw, daily_runs, set_by, set_at from webtoon_spend_override "
                                + "where day = ? order by id desc limit 1",
                        (rs, i) -> new DayCap(rs.getDate("day").toLocalDate(),
                                rs.getObject("daily_krw", Long.class),
                                rs.getObject("daily_runs", Long.class),
                                rs.getObject("set_by", Long.class),
                                rs.getTimestamp("set_at").toInstant()),
                        java.sql.Date.valueOf(day));
                return rows.isEmpty() ? null : rows.get(0);
            }

            @Override
            public void add(LocalDate day, Long krw, Long runs, Long by) {
                /* 빈 값이 섞이므로 타입을 같이 준다 — 안 주면 PostgreSQL 이 null 의 타입을 못 정해 실패할 수 있다. */
                db.update("insert into webtoon_spend_override (day, daily_krw, daily_runs, set_by) values (?, ?, ?, ?)",
                        new Object[] {java.sql.Date.valueOf(day), krw, runs, by},
                        new int[] {Types.DATE, Types.BIGINT, Types.BIGINT, Types.BIGINT});
            }
        };
    }
}
