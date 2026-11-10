package com.lore.zzal.attendance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Opt-in product entry: one durable outbox handoff per poll, never a provider retry loop. */
@Component
@ConditionalOnProperty(name="app.zzal.custom.dispatch.enabled", havingValue="true")
public class CustomBundleDispatcher {
    private static final Logger log = LoggerFactory.getLogger(CustomBundleDispatcher.class);
    static final String CLAIM = """
            WITH candidate AS (
              SELECT j.id FROM zzal_custom_bundle_job j JOIN zzal_pet p ON p.id=j.pet_id
              WHERE j.state='PENDING' AND j.claim_token IS NULL AND j.error_code IS NULL
                AND j.program_version=? AND j.bundle_version ~ '^bundle-[1-9][0-9]*$'
                AND p.phase='ALIVE'
                AND j.snapshot_json::jsonb->>'schemaVersion'='4'
                AND j.snapshot_json::jsonb->>'materialPolicy'=?
              ORDER BY j.id FOR UPDATE OF j SKIP LOCKED LIMIT 1
            )
            UPDATE zzal_custom_bundle_job j SET claim_token=? FROM candidate c
            WHERE j.id=c.id AND j.state='PENDING' AND j.claim_token IS NULL AND j.error_code IS NULL
            RETURNING j.id
            """;
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final RealCustomBundleService generation;
    private final Path outputRoot;

    public CustomBundleDispatcher(JdbcTemplate db, TransactionTemplate transaction,
                                  RealCustomBundleService generation, Environment env) {
        for (String flag : List.of("app.zzal.custom.dispatch.enabled", "app.zzal.custom.enabled",
                "app.zzal.generation.real", "app.zzal.generation.real-postprocess", "app.zzal.attendance.cli-enabled")) {
            if (!env.getProperty(flag, Boolean.class, false))
                throw new IllegalStateException("Custom dispatch requires explicit " + flag);
        }
        String root = env.getProperty("app.zzal.custom.dispatch.output-root", "");
        if (root.isBlank() || !Path.of(root).isAbsolute())
            throw new IllegalStateException("Custom dispatch requires an explicit absolute output-root");
        this.db = db; this.transaction = transaction; this.generation = generation;
        this.outputRoot = Path.of(root).normalize();
    }

    /** A long image request must not occupy the scheduler used for sleep/archive work. */
    @Bean(name="customBundleDispatchScheduler", destroyMethod="shutdown")
    public static ThreadPoolTaskScheduler scheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("custom-dispatch-");
        return scheduler;
    }

    @Scheduled(scheduler="customBundleDispatchScheduler", initialDelay=30000, fixedDelay=30000)
    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Dispatch must not retain a caller transaction");
        String token = UUID.randomUUID().toString();
        var jobs = Objects.requireNonNull(transaction.execute(status -> db.query(CLAIM,
                (rs, index) -> rs.getLong(1), AttendanceService.PROGRAM, BundleCommandAdapter.SHEET_REFERENCE_POLICY, token)));
        if (jobs.isEmpty()) return;
        long job = jobs.getFirst();
        // The committed PENDING token records dispatch ownership, not image execution.
        // The existing planner and worker own their separate durable claims and receipts.
        try {
            generation.run(job, token, outputRoot.resolve("job-" + job).resolve(token), Instant.now());
        } catch (Exception stopped) {
            // Keep token/state/error/source evidence untouched. Automatic polling cannot replay it.
            // Explicit recovery can reuse the stored token through the existing run entry.
            log.warn("Custom dispatch stopped: jobId={} failureType={}", job, stopped.getClass().getSimpleName());
        }
    }
}
