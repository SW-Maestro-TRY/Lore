package com.lore.webtoon.ops;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 90일이 지난 API 기록을 지운다. 다른 기간 파기와 같은 스위치({@code lore.retention.sweep-enabled})로 켠다.
 * 요청 본문에 사용자가 적은 글이 들어 있어서, 행동 기록(1년)보다 짧게 둔다.
 */
@Component
public class ApiLogRetention {

    private static final Logger log = LoggerFactory.getLogger(ApiLogRetention.class);
    static final Duration KEEP = Duration.ofDays(90);

    private final ApiLogRepository rows;
    private final boolean enabled;

    public ApiLogRetention(ApiLogRepository rows, @Value("${lore.retention.sweep-enabled:false}") boolean enabled) {
        this.rows = rows;
        this.enabled = enabled;
    }

    /** 05:50 — 행동 기록 파기(05:45) 뒤. */
    @Scheduled(cron = "0 50 5 * * *", zone = "Asia/Seoul")
    @Transactional
    public int runOnce() {
        if (!enabled) {
            return 0;
        }
        try {
            int gone = rows.deleteBefore(Instant.now().minus(KEEP));
            if (gone > 0) {
                log.info("90일이 지난 웹툰 API 기록 {}줄을 지웠습니다", gone);
            }
            return gone;
        } catch (RuntimeException e) {
            log.warn("웹툰 API 기록 파기 실패: {}", e.toString());
            return 0;
        }
    }
}
