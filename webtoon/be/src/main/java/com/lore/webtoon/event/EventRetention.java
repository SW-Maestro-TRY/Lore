package com.lore.webtoon.event;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 1년이 지난 행동 기록을 지운다 — 처리방침 제4조 「이용 흐름 분석 기록 1년」.
 *
 * 다른 기간 파기({@code RetentionSweep})와 같은 스위치({@code lore.retention.sweep-enabled})
 * 로 켠다. 서버를 여러 대 띄우면 한 대에서만 켜는 관례를 그대로 따른다.
 */
@Component
public class EventRetention {

    private static final Logger log = LoggerFactory.getLogger(EventRetention.class);

    static final Duration KEEP = Duration.ofDays(365);

    /** 05:40 기간 파기 뒤. */
    static final String CRON = "0 45 5 * * *";
    static final String ZONE = "Asia/Seoul";

    private final WebtoonEventRepository events;
    private final Clock clock;
    private final boolean enabled;

    @Autowired
    public EventRetention(WebtoonEventRepository events,
                          @Value("${lore.retention.sweep-enabled:false}") boolean enabled) {
        this(events, Clock.systemUTC(), enabled);
    }

    EventRetention(WebtoonEventRepository events, Clock clock, boolean enabled) {
        this.events = events;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(cron = CRON, zone = ZONE)
    @Transactional
    public int runOnce() {
        if (!enabled) {
            return 0;
        }
        try {
            int gone = events.deleteReceivedBefore(Instant.now(clock).minus(KEEP));
            if (gone > 0) {
                log.info("1년이 지난 웹툰 행동 기록 {}줄을 지웠습니다", gone);
            }
            return gone;
        } catch (RuntimeException e) {
            // 시각 트리거에서 예외가 새면 그 뒤로 이 작업이 다시 안 돈다.
            log.warn("웹툰 행동 기록 파기가 실패했습니다", e);
            return 0;
        }
    }
}
