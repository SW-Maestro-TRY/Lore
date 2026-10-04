package com.lore.webtoon.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 오래 안 쓰인 푸시 구독을 지운다(#599).
 *
 * 개인정보처리방침에 「구독을 끄거나 {@link #IDLE} 동안 알림이 한 번도 안 가면
 * 지운다」고 적었다. 그 약속을 지키는 자리다. 알림이 가면 {@code used_at} 이
 * 새로 찍히므로, 계속 만드는 사람의 구독은 안 지워진다.
 *
 * 다른 기간 파기({@code RetentionSweep})와 같은 스위치로 켠다 — 서버가 여러 대일
 * 때 한 대에서만 켜는 관례도 같다.
 */
@Component
public class PushSweep {

    private static final Logger log = LoggerFactory.getLogger(PushSweep.class);

    static final Duration IDLE = Duration.ofDays(90);

    private final PushSubscriptionRepository subs;
    private final boolean enabled;

    public PushSweep(PushSubscriptionRepository subs,
                     @Value("${lore.retention.sweep-enabled:false}") boolean enabled) {
        this.subs = subs;
        this.enabled = enabled;
    }

    /** 새벽 5시 50분. 다른 파기(04:30 · 04:50 · 05:10 · 05:40)와 안 겹치게 둔다. */
    @Scheduled(cron = "0 50 5 * * *", zone = "Asia/Seoul")
    public void scheduled() {
        if (!enabled) {
            return;
        }
        try {
            int n = subs.deleteIdle(Instant.now().minus(IDLE));
            if (n > 0) {
                log.info("오래 안 쓰인 푸시 구독 {}개를 지웠습니다", n);
            }
        } catch (RuntimeException e) {          // 시각 트리거에서 예외가 새면 다음부터 안 돈다
            log.warn("푸시 구독 파기가 실패했습니다", e);
        }
    }
}
