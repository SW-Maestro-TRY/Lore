package com.lore.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code ddl-auto: update} 가 못 하는 스키마 손질 — 부팅 때 멱등 SQL.
 * <b>dev 전용 임시</b>(Flyway 가 오면 마이그레이션 한 장이 되고 사라진다).
 *
 * <h3>왜 필요한가 — 유일키는 한번 생기면 update 가 안 고친다</h3>
 *
 * {@code credit_event} 의 「같은 일인가」 기준이
 * {@code (user_id, reason, ref_id)} 였는데 여기에 {@code domain} 이 빠져
 * 있었다. {@code refId} 는 서비스마다 자기 방식으로 짓는 값이라(작품 id ·
 * 결제 id …) 웹툰의 것과 짤의 것이 우연히 같을 수 있고, 그러면 뒤엣것이
 * <b>"이미 적힌 일" 로 밀려 조용히 사라진다</b> — 낸 사람은 냈는데 장부에
 * 없는 상태다.
 *
 * 표에 붙은 유일키는 {@code ddl-auto: update} 가 <b>안 고치고 안 지운다.</b>
 * 그래서 클래스만 고치면 컴파일·테스트·기동이 전부 통과한 채 DB 는 옛 규칙
 * 그대로다(같은 함정을 이미 겪었다 — {@code ZzalSchemaPatch} 참고).
 *
 * <h3>{@code domain} 을 안 비게 하는 것도 함께</h3>
 *
 * 포스트그레스는 유일키에서 {@code NULL} 을 서로 다른 값으로 친다. 그 칸이
 * 비어 있으면 넷을 다 맞춰 봐도 안 맞아서, 같은 일이 몇 번이고 다시 적힌다.
 * 옛 줄(도메인 칸이 생기기 전에 적힌 것)을 {@code COMMON} 으로 메우고 칸을
 * 잠근다.
 *
 * <h3>넓히는 쪽이라 안전하다</h3>
 *
 * 칸이 <b>느는</b> 유일키는 옛 규칙을 지키던 자료를 절대 어기지 않는다
 * (좁은 규칙을 통과한 것은 넓은 규칙도 통과한다). 그래서 이 손질은 있는
 * 자료를 못 넣게 만들 수 없다.
 *
 * <h3>운영</h3>
 *
 * ALTER 권한이 없으면 부팅이 막히고 로그가 SQL 을 말한다. 그때는 DBA 가
 * 손으로 실행한다. 삼키지 않는다 — 삼키면 "기동 성공 = 동작 확인" 으로 읽힌다.
 */
@Component
public class CreditSchemaPatch {

    private static final Logger log = LoggerFactory.getLogger(CreditSchemaPatch.class);

    private static final String TABLE = "credit_event";
    private static final String NAME = "uk_credit_event_once";

    /** 지금 규칙. 순서까지 이대로여야 한다. */
    static final List<String> WANT = List.of("user_id", "reason", "domain", "ref_id");

    private final JdbcTemplate jdbc;

    public CreditSchemaPatch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void apply() {
        if (!tableExists()) {
            return;                         // 아직 안 만들어진 환경(검사 등)
        }

        // 1. 도메인이 빈 옛 줄을 메우고 칸을 잠근다. 유일키를 걸기 전에 해야
        //    한다 — 비어 있으면 그 줄은 어떤 유일키에도 안 걸린다.
        // 이미 잠겨 있으면 건너뛴다 — SET NOT NULL 은 그때도 성공하지만 표를
        // 통째로 훑고 잠근다. 부팅마다 할 일이 아니다.
        if (nullable("domain")) {
            int filled = jdbc.update(
                    "update " + TABLE + " set domain = 'COMMON' where domain is null");
            if (filled > 0) {
                log.info("스키마 손질 — 도메인이 비어 있던 {}줄을 COMMON 으로 메웠습니다", filled);
            }
            jdbc.execute("ALTER TABLE " + TABLE + " ALTER COLUMN domain SET NOT NULL");
            log.info("스키마 손질 — domain 을 안 비게 잠갔습니다");
        }

        // 2. 유일키가 이미 지금 규칙이면 아무것도 안 한다.
        List<String> now = columnsOf(NAME);
        if (WANT.equals(now)) {
            log.debug("스키마 손질 — {} 는 이미 {} 입니다", NAME, WANT);
            return;
        }

        if (!now.isEmpty()) {
            jdbc.execute("ALTER TABLE " + TABLE + " DROP CONSTRAINT " + NAME);
            log.info("스키마 손질 — 옛 유일키를 지웠습니다 ({} {})", NAME, now);
        }
        jdbc.execute("ALTER TABLE " + TABLE + " ADD CONSTRAINT " + NAME
                + " UNIQUE (" + String.join(", ", WANT) + ")");
        log.info("스키마 손질 — {} 를 {} 로 다시 걸었습니다", NAME, WANT);

        List<String> after = columnsOf(NAME);            // 올리고 다시 읽는 왕복
        if (!WANT.equals(after)) {
            throw new IllegalStateException(
                    "★ %s 를 못 고쳤습니다(ALTER 권한?). 손으로 실행하세요: "
                            .formatted(NAME)
                    + "ALTER TABLE %s DROP CONSTRAINT %s; ".formatted(TABLE, NAME)
                    + "ALTER TABLE %s ADD CONSTRAINT %s UNIQUE (%s);"
                            .formatted(TABLE, NAME, String.join(", ", WANT)));
        }
    }

    /** 그 칸이 비어 있어도 되나. */
    private boolean nullable(String column) {
        List<String> got = jdbc.queryForList(
                "select is_nullable from information_schema.columns"
                        + " where table_name = ? and column_name = ?",
                String.class, TABLE, column);
        return got.contains("YES");
    }

    private boolean tableExists() {
        Boolean got = jdbc.queryForObject(
                "select exists (select 1 from information_schema.tables"
                        + " where table_name = ?)", Boolean.class, TABLE);
        return Boolean.TRUE.equals(got);
    }

    /** 그 제약이 걸린 칸들. 걸린 순서 그대로. 없으면 빈 것. */
    List<String> columnsOf(String name) {
        return jdbc.queryForList(
                "select a.attname from pg_constraint c"
                        + " join unnest(c.conkey) with ordinality as k(attnum, ord) on true"
                        + " join pg_attribute a"
                        + "   on a.attrelid = c.conrelid and a.attnum = k.attnum"
                        + " where c.conrelid = ?::regclass and c.conname = ?"
                        + " order by k.ord",
                String.class, TABLE, name);
    }
}
