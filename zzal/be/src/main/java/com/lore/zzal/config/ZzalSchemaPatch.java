package com.lore.zzal.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code ddl-auto: update} 가 못 하는 스키마 손질 — 부팅 때 멱등 SQL 몇 줄.
 *
 * <h3>★★ 왜 필요한가 — enum 에 값을 더하면 서버는 정상 기동하고 INSERT 때만 터진다</h3>
 * Hibernate 6+ 는 {@code @Enumerated(STRING)} 칸에 <b>허용 값 목록 CHECK 제약</b>을 만든다
 * ({@code zzal_motion_status_check IN ('PENDING','OPEN','FAILED')}). 나중에 enum 에 NONE·QUEUED 를 더해도
 * {@code update} 는 제약을 안 고친다. 그래서 컴파일·테스트·기동이 전부 통과한 채 <b>부화 완료 때 18행 INSERT 만</b>
 * 실패했다(2026-09-05 PR-5 실기동 스모크에서 실제로 걸림 — 로그에만 남고 펫은 ALIVE 로 보였다).
 *
 * <h3>처방</h3>
 * enum 값이 자라는 칸의 CHECK 를 부팅 때 지운다(멱등 {@code DROP CONSTRAINT IF EXISTS}). 값 검증은 자바 enum 이 한다.
 * Flyway(결정기록 B6, 9/14 뒤)가 오면 이 클래스는 마이그레이션 파일 한 장이 되고 사라진다.
 * 실패해도 부팅을 막지 않되 ERROR 로 크게 남긴다 — 어떤 SQL 이었는지 이름을 말한다.
 */
@Component
public class ZzalSchemaPatch {

    private static final Logger log = LoggerFactory.getLogger(ZzalSchemaPatch.class);

    /** 값이 자라는 enum 칸의 CHECK 제약. 새 enum 값을 더할 때 여기에 한 줄 더한다. */
    static final List<String> STATEMENTS = List.of(
            "ALTER TABLE zzal_motion DROP CONSTRAINT IF EXISTS zzal_motion_status_check"
    );

    private final JdbcTemplate jdbc;

    public ZzalSchemaPatch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void apply() {
        for (String sql : STATEMENTS) {
            try {
                jdbc.execute(sql);
                log.info("스키마 손질 — {}", sql);
            } catch (Exception e) {
                log.error("★ 스키마 손질 실패 — 이 SQL 을 손으로 실행하세요: {}", sql, e);
            }
        }
    }
}
