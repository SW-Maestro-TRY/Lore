package com.lore.zzal.generation;

import com.lore.zzal.generation.steps.IdentityStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 부화 한 마리를 끝까지 책임진다 — 돌리고, 실패하면 다시 하고, 그래도 안 되면 실패로 끝낸다.
 *
 * ★ @Async = 부른 쪽은 기다리지 않고 즉시 돌아간다. 실제 작업은 별도 스레드에서 이어지며,
 *   그 스레드 수(3)가 곧 동시 생성 상한이다.
 */
@Service
public class HatchService {

    private static final Logger log = LoggerFactory.getLogger(HatchService.class);

    private final GenerationRunner runner;
    private final GenerationRecorder recorder;
    private final GenJobRepository jobRepository;
    private final PipelineRegistry registry;
    private final int maxAttempts;

    public HatchService(GenerationRunner runner, GenerationRecorder recorder,
                        GenJobRepository jobRepository, PipelineRegistry registry,
                        @Value("${app.zzal.max-hatch-attempts:2}") int maxAttempts) {
        this.runner = runner;
        this.recorder = recorder;
        this.jobRepository = jobRepository;
        this.registry = registry;
        this.maxAttempts = maxAttempts;
    }

    @Async("hatchExecutor")
    public void hatch(Long jobId, Long petId, String version) {
        runner.run(jobId, petId, version);

        GenJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != GenStatus.FAILED) {
            return;
        }

        long attempts = jobRepository.countByPetIdAndKind(petId, GenKind.HATCH);
        if (attempts >= maxAttempts) {
            log.warn("부화 실패 확정 — petId={} 시도={}회", petId, attempts);
            recorder.markPetFailed(petId);
            return;
        }

        // ★ 거부(MODERATION_BLOCKED)면 문단부터 다시 만든다.
        //   거부는 입력이 막힌 것이라 같은 문단을 또 보내면 또 막힌다. 성공 기록을 지워야
        //   재시도가 그 단계를 건너뛰지 않는다.
        if (job.getErrorCode() == GenErrorCode.MODERATION_BLOCKED) {
            int discarded = recorder.discardSucceeded(petId, GenKind.HATCH, IdentityStep.NAME);
            log.info("거부로 실패 — 정체성 문단 {}건을 폐기하고 다시 만든다 (petId={})", discarded, petId);
        }

        // 다시 한 번. 나머지 성공한 단계는 그대로 이어받으므로 실패한 지점부터 시작된다.
        GenJob retry = jobRepository.save(
                GenJob.start(petId, GenKind.HATCH, (int) attempts + 1, version, Instant.now()));
        log.info("재시도 — petId={} attempt={}", petId, attempts + 1);
        runner.run(retry.getId(), petId, version);

        GenJob after = jobRepository.findById(retry.getId()).orElse(null);
        if (after != null && after.getStatus() == GenStatus.FAILED) {
            recorder.markPetFailed(petId);
        }
    }

    public String currentVersion() {
        return registry.currentVersion();
    }
}
