package com.lore.zzal.admin.retry;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 맥미니 러너가 마지막으로 확인한 시각(결정 4). 새 표를 만들지 않고 {@code zzal_alert_state} 한 줄을 쓴다 —
 * 서버가 다시 떠도 남는다. 화면은 이 값으로 "러너가 살아 있나" 를 본다.
 */
@Component
public class AdminRunnerClock {

    static final String KEY = "admin_runner_seen";

    private final JdbcTemplate jdbc;

    public AdminRunnerClock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void touch(Instant now) {
        jdbc.update("""
                insert into zzal_alert_state (alert_key, last_value, sent_at) values (?, ?, ?)
                on conflict (alert_key) do update set last_value = excluded.last_value, sent_at = excluded.sent_at
                """, KEY, now.toString(), Timestamp.from(now));
    }

    public Optional<Instant> lastSeen() {
        return jdbc.query("select sent_at from zzal_alert_state where alert_key = ?",
                        (rs, n) -> rs.getTimestamp(1), KEY).stream()
                .findFirst().map(Timestamp::toInstant);
    }
}
