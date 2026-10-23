package com.lore.zzal.attendance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit one-shot publication recovery. No generation dependency and no HTTP surface. */
@Component
public final class CustomPublicationRecoveryRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(CustomPublicationRecoveryRunner.class);
    private static final String PREFIX = "ZZAL_PUBLICATION_RECOVERY_";
    private final Environment env;
    private final CustomBundlePublicationService publication;
    private final AtomicBoolean started = new AtomicBoolean();

    public CustomPublicationRecoveryRunner(Environment env, CustomBundlePublicationService publication) {
        this.env = env;
        this.publication = publication;
    }

    @Override public void run(ApplicationArguments args) {
        if (!enabled() || !started.compareAndSet(false, true)) return;
        try {
            // Storage reads and image verification must not delay application readiness.
            Thread.ofPlatform().daemon(true).name("custom-publication-recovery").start(() -> {
                Outcome result = attempt(Instant.now());
                log.info("Custom publication recovery: code={} ownerId={} jobId={} published={}",
                        result.code(), result.ownerId(), result.jobId(), result.published());
            });
        } catch (RuntimeException unavailable) {
            log.warn("Custom publication recovery: code=WORKER_UNAVAILABLE");
        }
    }

    record Outcome(String code, long ownerId, long jobId, int published) { }

    private boolean enabled() { return "true".equals(env.getProperty(PREFIX + "ENABLED", "false")); }

    Outcome attempt(Instant now) {
        if (!enabled()) return new Outcome("DISABLED", 0, 0, 0);
        long owner = 0, job = 0;
        String stage = "INVALID_CONFIG";
        try {
            if (!Set.of("dev", "staging").contains(env.getProperty("LORE_ENV", "")))
                return new Outcome("ENVIRONMENT_DENIED", 0, 0, 0);
            owner = Long.parseLong(env.getRequiredProperty(PREFIX + "OWNER_ID"));
            job = Long.parseLong(env.getRequiredProperty(PREFIX + "JOB_ID"));
            if (owner <= 0 || job <= 0) return new Outcome("INVALID_TARGET", 0, 0, 0);
            String operation = env.getRequiredProperty(PREFIX + "OPERATION_ID");
            if (!operation.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,79}"))
                return new Outcome("INVALID_OPERATION", owner, job, 0);
            Instant expires = Instant.parse(env.getRequiredProperty(PREFIX + "EXPIRES_AT"));
            if (!expires.isAfter(now) || expires.isAfter(now.plus(Duration.ofHours(1))))
                return new Outcome("INVALID_EXPIRY", owner, job, 0);
            Path root = Path.of(env.getRequiredProperty("ZZAL_CUSTOM_DISPATCH_OUTPUT_ROOT"));
            if (!root.isAbsolute() || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                    || !root.normalize().equals(root.toRealPath()))
                return new Outcome("INVALID_ROOT", owner, job, 0);
            stage = "FENCE_UNAVAILABLE";
            Path directory = root.resolve("publication-recovery");
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(directory) || !directory.toRealPath().getParent().equals(root.toRealPath()))
                return new Outcome("INVALID_ROOT", owner, job, 0);
            // Persist the directory entry as well as the fence contents before any publication write.
            forceDirectory(root);
            // Global operation ID also prevents accidentally reusing one operation for a different target.
            Path fence = directory.resolve(operation + ".attempt");
            String receipt = "ownerId=" + owner + "\njobId=" + job + "\noperationId=" + operation
                    + "\nstartedAt=" + now + "\nexpiresAt=" + expires + "\n";
            try (FileChannel file = FileChannel.open(fence, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(receipt);
                while (bytes.hasRemaining()) file.write(bytes);
                file.force(true);
            } catch (FileAlreadyExistsException duplicate) {
                return new Outcome("ALREADY_ATTEMPTED", owner, job, 0);
            }
            forceDirectory(directory);
            // Never delete the fence, including on an uncertain result or process shutdown.
            stage = "PUBLICATION_FAILED";
            int published = publication.publish(owner, job, now);
            return new Outcome("SUCCEEDED", owner, job, published);
        } catch (Exception stopped) {
            // Provider/storage/SQL exception text may contain secrets. Only the fixed stage is reported.
            return new Outcome(stage, owner, job, 0);
        }
    }
    private static void forceDirectory(Path path) throws java.io.IOException {
        try (FileChannel directory = FileChannel.open(path, StandardOpenOption.READ)) {
            directory.force(true);
        }
    }
}
