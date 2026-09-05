package com.lore.zzal.generation;

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

    private final GenerationRecorder recorder;

    /** 단계에 시간 제한을 걸기 위한 일회용 스레드. 제한을 넘기면 이 스레드를 끊는다. */
    private final ExecutorService timeoutExecutor = Executors.newCachedThreadPool();

    /** 검증용 제한 시간(초). 0 이면 단계가 정한 값을 쓴다. 운영에서는 절대 켜지 않는다. */
    @org.springframework.beans.factory.annotation.Value("${app.zzal.generation.limit-override-seconds:0}")
    private int limitOverrideSeconds;

    public GenerationRunner(GenerationRecorder recorder) {
        this.recorder = recorder;
    }

    /**
     * 단계 목록을 순서대로 돌린다.
     *
     * ★ 이 메서드는 <b>무엇을 굽는지 모른다</b>. 부화인지 모션인지, 끝나면 무엇이 되어야 하는지
     *   모두 부르는 쪽의 일이다. 여기서는 돌리고, 시간을 재고, 기록하고, 결과를 돌려준다.
     *
     * @param ctx    무엇으로 굽는지가 담긴 재료(부르는 쪽이 채워서 준다)
     * @param steps  돌릴 단계 목록
     * @param resume 앞선 시도에서 성공한 단계들. 이어받아 건너뛴다
     */
    public RunResult run(Long jobId, StepContext ctx, List<GenerationStep> steps,
                         List<GenStepRecord> resume) {
        String version = ctx.version();
        recorder.markJobRunning(jobId);

        // 앞선 시도에서 성공한 단계의 결과를 그대로 이어받는다(재시도는 새 job 이므로
        // 그 job 의 기록만 보면 항상 비어 있다).
        resume.forEach(rec -> {
            if (rec.getOutputKey() != null) {
                ctx.putImage(rec.getName(), rec.getOutputKey());
            }
            if (rec.getOutputText() != null) {
                ctx.putText(rec.getName(), rec.getOutputText());
            }
        });

        BigDecimal total = BigDecimal.ZERO;

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
                log.warn("시간 초과 — jobId={} step={} ({}초)", jobId, step.name(),
                        limitOverrideSeconds > 0 ? limitOverrideSeconds : step.limitSeconds());
                recorder.failStep(stepId, GenErrorCode.TIMEOUT);
                recorder.failJob(jobId, GenErrorCode.TIMEOUT, total);
                return RunResult.failed(ctx, total, GenErrorCode.TIMEOUT);
            } catch (Exception e) {
                GenErrorCode code = classify(e);
                log.warn("단계 실패 — jobId={} step={} code={} : {}", jobId, step.name(), code, e.toString());
                recorder.failStep(stepId, code);
                recorder.failJob(jobId, code, total);
                return RunResult.failed(ctx, total, code);
            }
        }

        recorder.succeedJob(jobId, total, Instant.now());
        log.info("생성 완료 — petId={} version={} 비용=${}", ctx.petId(), version, total);
        return RunResult.ok(ctx, total);
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
        // 검증용으로 제한을 줄일 수 있게 한다. 0 이하면 단계가 정한 값을 그대로 쓴다.
        int limit = limitOverrideSeconds > 0 ? limitOverrideSeconds : step.limitSeconds();
        try {
            return future.get(limit, TimeUnit.SECONDS);
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
