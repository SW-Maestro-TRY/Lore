package com.lore.zzal.generation;

import com.lore.zzal.generation.steps.IdentityStep;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalPetRepository;
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
    private final ZzalPetRepository petRepository;
    private final int maxAttempts;

    public HatchService(GenerationRunner runner, GenerationRecorder recorder,
                        GenJobRepository jobRepository, PipelineRegistry registry,
                        ZzalPetRepository petRepository,
                        @Value("${app.zzal.max-hatch-attempts:2}") int maxAttempts) {
        this.runner = runner;
        this.recorder = recorder;
        this.jobRepository = jobRepository;
        this.registry = registry;
        this.petRepository = petRepository;
        this.maxAttempts = maxAttempts;
    }

    @Async("hatchExecutor")
    public void hatch(Long jobId, Long petId, String version) {
        if (runAttempt(jobId, petId, version)) {
            return;
        }

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
        if (!runAttempt(retry.getId(), petId, version)) {
            recorder.markPetFailed(petId);
        }
    }

    /**
     * 한 번 굽는다. 성공하면 펫을 살린다.
     *
     * ★ 재료를 여기서 채워 넘긴다 — 실행기는 무엇을 굽는지 모르고, 부화가 무엇으로
     *   시작하는지(원본 그림)와 무엇으로 끝나는지(살아난 펫)는 부화의 일이다.
     */
    private boolean runAttempt(Long jobId, Long petId, String version) {
        ZzalPet pet = petRepository.findById(petId).orElse(null);
        if (pet == null) {
            log.warn("펫이 없습니다 — petId={}", petId);
            return false;
        }

        StepContext ctx = new StepContext(petId, pet.getName(), pet.getNote(), version);
        ctx.putImage("source", pet.getSourceImageKey());

        RunResult r = runner.run(jobId, ctx,
                registry.steps(GenKind.HATCH, version),
                recorder.loadSucceeded(petId, GenKind.HATCH));
        if (!r.success()) {
            return false;
        }
        recorder.markPetAlive(petId, ctx.image("sheet"), ctx.text("identity"), Instant.now());
        log.info("부화 완료 — petId={} version={} 비용=${}", petId, version, r.costUsd());
        return true;
    }

    public String currentVersion() {
        return registry.currentVersion(GenKind.HATCH);
    }
}
