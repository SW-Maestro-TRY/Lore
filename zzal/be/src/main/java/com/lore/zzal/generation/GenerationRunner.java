package com.lore.zzal.generation;

import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalPetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 파이프라인을 실제로 돌리는 곳. **단계가 몇 개인지, 무슨 일을 하는지 모른다.**
 *
 * 하는 일 넷
 *   1) 버전에 맞는 단계 목록을 순서대로 실행
 *   2) 단계마다 시간 제한을 걸고, 넘으면 끊는다
 *   3) 단계마다 결과를 기록한다(성공·실패·비용·산출물)
 *   4) 재시도면 **이미 성공한 단계는 건너뛴다** — 시트가 됐으면 $0.063 을 다시 쓰지 않는다
 */
@Component
public class GenerationRunner {

    private static final Logger log = LoggerFactory.getLogger(GenerationRunner.class);

    private final PipelineRegistry registry;
    private final GenerationRecorder recorder;
    private final ZzalPetRepository petRepository;

    /** 단계에 시간 제한을 걸기 위한 일회용 스레드. 제한을 넘기면 이 스레드를 끊는다. */
    private final ExecutorService timeoutExecutor = Executors.newCachedThreadPool();

    public GenerationRunner(PipelineRegistry registry, GenerationRecorder recorder,
                            ZzalPetRepository petRepository) {
        this.registry = registry;
        this.recorder = recorder;
        this.petRepository = petRepository;
    }

    /**
     * 한 번의 시도. 성공하면 펫이 ALIVE 가 되고, 실패하면 이 시도만 실패로 남는다
     * (다시 할지는 부르는 쪽이 정한다).
     */
    public void run(Long jobId, Long petId, String version) {
        ZzalPet pet = petRepository.findById(petId).orElse(null);
        if (pet == null) {
            log.warn("펫이 없습니다 — petId={}", petId);
            return;
        }

        StepContext ctx = new StepContext(petId, pet.getName(), pet.getNote(), version);
        ctx.putImage("source", pet.getSourceImageKey());
        recorder.markJobRunning(jobId);

        // 앞선 시도에서 성공한 단계의 결과를 그대로 이어받는다.
        recorder.loadSucceeded(jobId).forEach(rec -> {
            if (rec.getOutputKey() != null) {
                ctx.putImage(rec.getName(), rec.getOutputKey());
            }
            if (rec.getOutputText() != null) {
                ctx.putText(rec.getName(), rec.getOutputText());
            }
        });

        BigDecimal total = BigDecimal.ZERO;
        List<GenerationStep> steps = registry.steps(version);

        for (int i = 0; i < steps.size(); i++) {
            GenerationStep step = steps.get(i);

            if (ctx.image(step.name()) != null || ctx.text(step.name()) != null) {
                log.info("건너뜀(이미 성공) — jobId={} step={}", jobId, step.name());
                continue;
            }

            Long stepId = recorder.startStep(jobId, i, step.name());
            try {
                StepResult r = runWithLimit(step, ctx);
                recorder.succeedStep(stepId, r);
                if (r.imageKey() != null) {
                    ctx.putImage(r.name(), r.imageKey());
                }
                if (r.text() != null) {
                    ctx.putText(r.name(), r.text());
                }
                total = total.add(r.costUsd());
            } catch (TimeoutException e) {
                log.warn("시간 초과 — jobId={} step={} ({}초)", jobId, step.name(), step.limitSeconds());
                recorder.failStep(stepId, GenErrorCode.TIMEOUT);
                recorder.failJob(jobId, GenErrorCode.TIMEOUT, total);
                return;
            } catch (Exception e) {
                GenErrorCode code = classify(e);
                log.warn("단계 실패 — jobId={} step={} code={} : {}", jobId, step.name(), code, e.toString());
                recorder.failStep(stepId, code);
                recorder.failJob(jobId, code, total);
                return;
            }
        }

        recorder.succeedJob(jobId, petId,
                ctx.image("sheet"), ctx.text("identity"), total, Instant.now());
        log.info("부화 완료 — petId={} version={} 비용=${}", petId, version, total);
    }

    /**
     * 단계에 시간 제한을 건다.
     *
     * ★ 제한이 없으면 응답이 영영 안 오는 호출 하나가 스레드를 붙들고, 그 스레드가
     *   동시 생성 3개 중 하나이므로 다른 사람의 부화까지 막힌다.
     */
    private StepResult runWithLimit(GenerationStep step, StepContext ctx) throws Exception {
        Callable<StepResult> task = () -> step.run(ctx);
        Future<StepResult> future = timeoutExecutor.submit(task);
        try {
            return future.get(step.limitSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

    /**
     * 실패 종류를 가른다. **처방이 정반대라 반드시 구분해야 한다.**
     *   거부당함 → 같은 걸 다시 보내면 또 막힌다. 앞 단계(문단)부터 새로
     *   그 외    → 같은 입력으로 다시 하면 대개 된다
     */
    private GenErrorCode classify(Exception e) {
        String msg = String.valueOf(e.getMessage()).toLowerCase();
        if (msg.contains("moderation") || msg.contains("safety") || msg.contains("content_policy")) {
            return GenErrorCode.MODERATION_BLOCKED;
        }
        return GenErrorCode.UNKNOWN;
    }
}
