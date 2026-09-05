package com.lore.zzal.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code ddl-auto: update} 가 못 하는 스키마 손질 — 부팅 때 멱등 SQL. <b>dev 전용 임시</b>(Flyway = PR-12, 9/13).
 *
 * <h3>★★ 왜 필요한가 — enum 에 값을 더하면 서버는 정상 기동하고 INSERT 때만 터진다</h3>
 * Hibernate 6+ 는 {@code @Enumerated(STRING)} 칸마다 <b>허용 값 목록 CHECK 제약</b>을 만든다
 * ({@code zzal_motion_status_check IN ('PENDING','OPEN','FAILED')}). enum 에 NONE·QUEUED 를 더해도 {@code update} 는
 * 제약을 안 고친다. 컴파일·테스트·기동이 전부 통과한 채 <b>부화 완료 때 18행 INSERT 만</b> 실패했다
 * (2026-09-05 PR-5 실기동 스모크 — 로그에만 남고 펫은 ALIVE 로 보였다).
 *
 * <h3>처방 — 이름을 박지 않고 표의 CHECK 를 전부 지운 뒤, 다시 읽어 남아 있으면 부팅을 막는다</h3>
 * 리뷰 실측: status 하나만 지웠더니 {@code gate_verdict}·{@code human_verdict}·{@code source}·{@code layer} 의 CHECK 4개가
 * 남아 있었다 — 다음 enum 확장 때 같은 사고. 그래서 {@code pg_constraint} 에서 그 표의 {@code contype='c'} 를 전부 찾아
 * 지우고, <b>지운 뒤 다시 읽어</b> 하나라도 남으면 부팅을 실패시킨다("올리고 다시 읽는 왕복"). 값 검증은 자바 enum 이 한다.
 * 실패를 삼키지 않는다 — 삼키면 8/25 처럼 "기동 성공 = 동작 확인" 으로 읽힌다.
 *
 * <h3>운영</h3>
 * ALTER 권한이 없는 DB 라면 부팅이 막히고 로그가 SQL 을 말한다. 그때는 DBA 가 손으로 실행한다. Flyway 가 오면
 * 이 클래스는 마이그레이션 파일 한 장이 되고 사라진다.
 */
@Component
public class ZzalSchemaPatch {

    private static final Logger log = LoggerFactory.getLogger(ZzalSchemaPatch.class);

    /** enum 값이 자라는 표. 새 enum 표가 생기면 여기 한 줄. */
    static final List<String> ENUM_TABLES = List.of("zzal_motion");

    private final JdbcTemplate jdbc;

    public ZzalSchemaPatch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void apply() {
        for (String table : ENUM_TABLES) {
            List<String> names = checkConstraints(table);
            for (String name : names) {
                String sql = "ALTER TABLE %s DROP CONSTRAINT IF EXISTS %s".formatted(table, name);
                jdbc.execute(sql);
                log.info("스키마 손질 — {}", sql);
            }
            List<String> remaining = checkConstraints(table);          // 올리고 다시 읽는 왕복
            if (!remaining.isEmpty()) {
                throw new IllegalStateException(
                        "★ %s 의 CHECK 제약이 지워지지 않았습니다(ALTER 권한?). 손으로 실행하세요: %s"
                                .formatted(table, remaining.stream()
                                        .map(n -> "ALTER TABLE %s DROP CONSTRAINT %s;".formatted(table, n)).toList()));
            }
            if (names.isEmpty()) {
                log.debug("스키마 손질 — {} 에 CHECK 제약 없음", table);
            }
        }
    }

    /** 그 표의 CHECK 제약 이름들(Postgres). */
    List<String> checkConstraints(String table) {
        return jdbc.queryForList(
                "select conname from pg_constraint where conrelid = ?::regclass and contype = 'c'",
                String.class, table);
    }
}
