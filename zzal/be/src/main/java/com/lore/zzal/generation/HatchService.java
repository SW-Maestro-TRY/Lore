package com.lore.zzal.generation;

import com.lore.zzal.generation.steps.IdentityStep;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.motion.MotionSeeder;
import com.lore.zzal.pet.ZzalPetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

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
    private final MotionSeeder motionSeeder;
    private final ZzalPetRepository petRepository;
    private final int maxAttempts;

    public HatchService(GenerationRunner runner, GenerationRecorder recorder,
                        GenJobRepository jobRepository, PipelineRegistry registry,
                        ZzalPetRepository petRepository,
                        @Value("${app.zzal.max-hatch-attempts:2}") int maxAttempts,
                        MotionSeeder motionSeeder) {
        this.runner = runner;
        this.recorder = recorder;
        this.jobRepository = jobRepository;
        this.registry = registry;
        this.petRepository = petRepository;
        this.maxAttempts = maxAttempts;
        this.motionSeeder = motionSeeder;
    }

    /**
     * 캐릭터 시트만 굽는다 — 그림을 등록한 직후.
     *
     * <h3>★ 파이프라인을 둘로 나누지 않는다</h3>
     * 같은 5단계 목록에서 <b>앞의 한 개만 잘라</b> 넘긴다. 나머지는 이름이 들어온 뒤
     * {@link #hatch} 가 전체 목록으로 다시 부르는데, 실행기가 "이미 성공한 단계" 를 건너뛰므로
     * 시트는 두 번 구워지지 않는다. 재시도·실패 복구 규칙도 그대로 산다.
     *
     * <h3>★ 여기서 실패해도 초안을 죽이지 않는다</h3>
     * 이름을 짓는 중이라 사용자는 아직 아무것도 못 본다. 이름이 들어와 전체를 돌릴 때
     * 그 자리에서 다시 시도하고, 그때도 안 되면 그 판정이 사용자에게 간다.
     */
    @Async("hatchExecutor")
    public void sheet(Long jobId, Long petId, String version) {
        ZzalPet pet = petRepository.findById(petId).orElse(null);
        if (pet == null) {
            log.warn("펫이 없습니다 — petId={}", petId);
            return;
        }
        StepContext ctx = new StepContext(petId, pet.getName(), pet.getNote(), version);
        ctx.putImage("source", pet.getSourceImageKey());

        List<GenerationStep> all = registry.steps(GenKind.HATCH, version);
        RunResult r = runner.run(jobId, ctx, all.subList(0, 1),
                recorder.loadSucceeded(petId, GenKind.HATCH, version));
        log.info("시트 미리 굽기 {} — petId={} 비용=${}", r.success() ? "완료" : "실패", petId, r.costUsd());
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
                recorder.loadSucceeded(petId, GenKind.HATCH, version));
        if (!r.success()) {
            return false;
        }
        Instant now = Instant.now();
        recorder.markPetAlive(petId, ctx.image("sheet"), ctx.text("identity"), now);
        // ★ 부화 완료 = 동작 18행(정본 13장). 1층 8종은 이 순간이 열린 시각. 심화 행동은 아직(NONE).
        motionSeeder.seed(petId, now);
        log.info("부화 완료 — petId={} version={} 비용=${}", petId, version, r.costUsd());
        return true;
    }

    /** 이 펫의 부화가 몇 단계까지 끝났나. 진행률 표시에 쓴다. */
    public int stepsDone(Long petId, String version) {
        return (int) recorder.loadSucceeded(petId, GenKind.HATCH, version).stream()
                .map(GenStepRecord::getName)
                .distinct()
                .count();
    }

    /** 부화가 모두 몇 단계인가. */
    public int stepsTotal(String version) {
        return registry.steps(GenKind.HATCH, version).size();
    }

    public String currentVersion() {
        return registry.currentVersion(GenKind.HATCH);
    }
}
