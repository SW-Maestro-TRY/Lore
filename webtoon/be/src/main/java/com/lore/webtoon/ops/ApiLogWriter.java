package com.lore.webtoon.ops;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 기록을 요청 바깥에서 쓴다.
 *
 * <p>요청 하나마다 DB 에 한 줄을 그 자리에서 쓰면, 기록 때문에 응답이 늦어지고 DB 가 바쁠 때 화면까지
 * 같이 느려진다. 그래서 줄을 큐에 넣고 스레드 하나가 뒤에서 쓴다. 큐가 차면 버린다 — 기록을 잃는 편이
 * 사용자를 기다리게 하는 것보다 낫다. 버린 수는 로그에 남긴다.
 */
@Component
public class ApiLogWriter {

    private static final Logger log = LoggerFactory.getLogger(ApiLogWriter.class);

    private final ApiLogRepository rows;
    private final AtomicLong dropped = new AtomicLong();
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(5000),
            r -> {
                Thread t = new Thread(r, "webtoon-api-log");
                t.setDaemon(true);
                return t;
            },
            (r, p) -> {
                long n = dropped.incrementAndGet();
                if (n == 1 || n % 1000 == 0) {
                    log.warn("API 기록 큐가 차서 {}줄을 버렸습니다", n);
                }
            });

    public ApiLogWriter(ApiLogRepository rows) {
        this.rows = rows;
    }

    /** 한 줄 남긴다. 실패해도 조용하다 — 기록 때문에 요청이 실패하면 안 된다. */
    public void write(ApiLog row) {
        if (row == null) {
            return;
        }
        pool.execute(() -> {
            try {
                rows.save(row);
            } catch (RuntimeException e) {
                log.debug("API 기록을 못 남겼습니다: {}", e.toString());
            }
        });
    }

    @PreDestroy
    void stop() {
        pool.shutdown();
        try {
            pool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
