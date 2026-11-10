package com.lore.piecemaker.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** 운영 확인 후 별도로 활성화한다. 실패는 오류 로그로 남고 다음 실행에서 재시도한다. */
@Component
@ConditionalOnProperty(name = "lore.piece-maker.measurement.retention-enabled", havingValue = "true")
public class MeasurementRetentionJob {
    private static final Logger log = LoggerFactory.getLogger(MeasurementRetentionJob.class);
    private final MeasurementRetention retention;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "piece-maker-retention");
        thread.setDaemon(true);
        return thread;
    });

    public MeasurementRetentionJob(MeasurementRetention retention) { this.retention = retention; }

    // 공용 스케줄러는 실행 신호만 보낸다. 다른 서비스의 스케줄러 선택·작업 시간을 바꾸지 않는다.
    @Scheduled(cron = "0 15 * * * *", zone = "UTC")
    public void run() {
        if (!running.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                try { log.info("Piece Maker measurement retention: {}", retention.purgeExpired()); }
                catch (RuntimeException e) { log.error("Piece Maker measurement retention failed", e); }
                finally { running.set(false); }
            });
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
    }

    @PreDestroy
    public void close() { worker.shutdown(); }
}
