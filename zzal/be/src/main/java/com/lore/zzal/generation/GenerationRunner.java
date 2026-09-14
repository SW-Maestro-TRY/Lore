package com.lore.zzal.generation;

import com.lore.zzal.generation.client.BilledFailureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 파이프라인을 실제로 돌리는 곳. **단계가 몇 개인지, 무슨 일을 하는지 모른다.**
 *
 * 하는 일 넷
 *   1) 버전에 맞는 <b>묶음</b>들을 순서대로 실행 — 한 묶음 안의 단계는 나란히 돈다
 *   2) 단계마다 시간 제한을 걸고, 넘으면 끊는다
 *   3) 단계마다 결과를 기록한다(성공·실패·비용·산출물)
 *   4) 재시도면 **이미 성공한 단계는 건너뛴다** — 시트가 됐으면 $0.063 을 다시 쓰지 않는다
 *
 * <h3>★ 나란히 돌 때도 기록은 단계마다 따로다</h3>
 * 한 묶음에서 한쪽이 실패해도 <b>다른 쪽의 성공 기록은 그대로 남는다</b>(기록이 단계별 커밋이라).
 * 그래서 다시 시도하면 실패한 쪽만 구워진다 — 격자 두 장 중 한 장 값을 다시 쓰지 않는다.
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
     * 묶음 목록을 순서대로 돌린다.
     *
     * ★ 이 메서드는 <b>무엇을 굽는지 모른다</b>. 부화인지 모션인지, 끝나면 무엇이 되어야 하는지
     *   모두 부르는 쪽의 일이다. 여기서는 돌리고, 시간을 재고, 기록하고, 결과를 돌려준다.
     *
     * @param ctx    무엇으로 굽는지가 담긴 재료(부르는 쪽이 채워서 준다)
     * @param stages 돌릴 묶음 목록. 묶음 안은 동시에, 묶음 사이는 순서대로
     * @param resume 앞선 시도에서 성공한 단계들. 이어받아 건너뛴다
     */
    public RunResult run(Long jobId, StepContext ctx, List<List<GenerationStep>> stages,
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
        int seq = 0;

        for (List<GenerationStep> stage : stages) {
            List<Pending> todo = new ArrayList<>();
            for (GenerationStep step : stage) {
                if (ctx.image(step.name()) != null || ctx.text(step.name()) != null) {
                    log.info("건너뜀(이미 성공) — jobId={} step={}", jobId, step.name());
                } else {
                    todo.add(new Pending(step, seq));
                }
                seq++;
            }
            if (todo.isEmpty()) {
                continue;
            }

            StageOutcome outcome = runStage(jobId, ctx, todo);
            total = total.add(outcome.cost());
            if (outcome.error() != null) {
                recorder.failJob(jobId, outcome.error(), total);
                // ★ 격자 구조 게이트가 막은 것이면 같은 격자로 다시 해 봐야 소용없다 — 부르는 쪽에 알린다.
                return outcome.gridRejected()
                        ? RunResult.gridRejected(ctx, total, outcome.error())
                        : RunResult.failed(ctx, total, outcome.error());
            }
        }

        recorder.succeedJob(jobId, total, Instant.now());
        log.info("생성 완료 — petId={} version={} 비용=${}", ctx.petId(), version, total);
        return RunResult.ok(ctx, total);
    }

    /** 아직 안 돈 단계와 그 순번. */
    private record Pending(GenerationStep step, int seq) {
    }

    /** 돌고 있는 단계 — 기록 번호와 제 시간 제한을 함께 들고 있는다. */
    private record Running(GenerationStep step, Long stepId, Future<StepResult> future, long deadlineNanos) {
    }

    /**
     * 한 묶음의 결과. {@code error} 가 있으면 그 묶음에서 멈춘다.
     *
     * ★ {@code gridRejected} 는 "이 격자는 못 쓴다" 는 별도의 신호다. 실패 코드와 따로 두는 이유는
     *   {@link GenErrorCode} 가 DB 컬럼의 CHECK 제약에 묶여 있어 값을 늘리려면 마이그레이션이
     *   필요하기 때문이다(→ {@link RunResult#gridRejected}).
     */
    private record StageOutcome(BigDecimal cost, GenErrorCode error, boolean gridRejected) {
    }

    /**
     * 한 묶음을 돌린다. 단계가 하나면 그대로, 여럿이면 나란히.
     *
     * <h3>★ 다 끝난 뒤에 결과를 넣는다</h3>
     * {@link StepContext} 는 평범한 맵이라 여러 스레드가 동시에 쓰면 깨진다. 그래서 돌고 있는 동안에는
     * 아무도 ctx 에 쓰지 않고, <b>전부 끝난 뒤 이 스레드가</b> 한 번에 넣는다.
     *
     * <h3>★ 한쪽이 실패해도 끝까지 기다린다</h3>
     * 먼저 실패했다고 바로 나가면 남은 스레드가 뒤늦게 기록을 쓰고, 그 뒤에 시작된 재시도와 겹친다.
     */
    private StageOutcome runStage(Long jobId, StepContext ctx, List<Pending> todo) {
        List<Running> running = new ArrayList<>(todo.size());
        for (Pending p : todo) {
            Long stepId = recorder.startStep(jobId, p.seq(), p.step().name());
            int limit = limitOverrideSeconds > 0 ? limitOverrideSeconds : p.step().limitSeconds();
            Callable<StepResult> task = () -> p.step().run(ctx);
            running.add(new Running(p.step(), stepId, timeoutExecutor.submit(task),
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(limit)));
        }
        if (running.size() > 1) {
            log.info("나란히 굽기 {}개 — jobId={} steps={}", running.size(), jobId,
                    running.stream().map(r -> r.step().name()).toList());
        }

        BigDecimal cost = BigDecimal.ZERO;
        GenErrorCode error = null;
        boolean gridRejected = false;
        List<StepResult> done = new ArrayList<>(running.size());

        for (Running r : running) {
            try {
                long left = Math.max(0, r.deadlineNanos() - System.nanoTime());
                StepResult result = r.future().get(left, TimeUnit.NANOSECONDS);
                recorder.succeedStep(r.stepId(), result);
                done.add(result);
                cost = cost.add(result.costUsd());
            } catch (TimeoutException e) {
                r.future().cancel(true);
                log.warn("시간 초과 — jobId={} step={}", jobId, r.step().name());
                // ★ 끊은 호출은 얼마가 나갔는지 알 길이 없다(응답을 못 받았다). 0 이 맞다.
                recorder.failStep(r.stepId(), GenErrorCode.TIMEOUT, BigDecimal.ZERO);
                error = worse(error, GenErrorCode.TIMEOUT);
            } catch (Exception e) {
                Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
                GenErrorCode code = classify(cause);
                // ★ 나란히 도는 묶음에서 한 장만 게이트에 막혀도 그 격자는 버려야 한다 — 누적한다.
                gridRejected |= gridRejected(cause);
                // ★★ 실패해도 <b>이미 나간 돈</b>은 적는다. 유료 호출은 200 이 돌아온 순간 과금이 끝나므로,
                //   응답 파싱·S3 업로드에서 터진 실패는 공짜가 아니다. 여기서 안 더하면 원가가
                //   실제보다 낮게 보여 중복 과금이나 급증을 못 본다.
                BigDecimal billed = BilledFailureException.billed(e);
                log.warn("단계 실패 — jobId={} step={} code={} 비용=${} : {}",
                        jobId, r.step().name(), code, billed, String.valueOf(e));
                recorder.failStep(r.stepId(), code, billed);
                cost = cost.add(billed);
                error = worse(error, code);
            }
        }

        // ★ 실패한 묶음이어도 성공한 쪽의 산출물은 넣는다 — 같은 시도 안의 뒤 단계가 쓸 수 있고,
        //   기록에도 남아 다음 시도가 그 단계를 건너뛴다.
        for (StepResult result : done) {
            if (result.imageKey() != null) {
                ctx.putImage(result.name(), result.imageKey());
            }
            if (result.text() != null) {
                ctx.putText(result.name(), result.text());
            }
        }
        return new StageOutcome(cost, error, gridRejected);
    }

    /**
     * 두 실패 중 <b>처방이 더 무거운 쪽</b>을 남긴다.
     *
     * ★ 거부(MODERATION_BLOCKED)는 앞 단계(문단)부터 다시 해야 풀린다. 나란히 돈 두 장 중
     *   한 장만 거부당했는데 그냥 "알 수 없음" 으로 남기면, 재시도가 같은 문단으로 또 거부당한다.
     */
    private static GenErrorCode worse(GenErrorCode current, GenErrorCode next) {
        if (current == GenErrorCode.MODERATION_BLOCKED || next == GenErrorCode.MODERATION_BLOCKED) {
            return GenErrorCode.MODERATION_BLOCKED;
        }
        return current == null ? next : current;
    }

    /**
     * 실패 종류를 가른다. **처방이 정반대라 반드시 구분해야 한다.**
     *   거부당함 → 같은 걸 다시 보내면 또 막힌다. 앞 단계(문단)부터 새로
     *   그 외    → 같은 입력으로 다시 하면 대개 된다
     */
    private GenErrorCode classify(Throwable e) {
        String msg = String.valueOf(e == null ? null : e.getMessage()).toLowerCase();
        if (msg.contains("moderation") || msg.contains("safety") || msg.contains("content_policy")) {
            return GenErrorCode.MODERATION_BLOCKED;
        }
        return GenErrorCode.UNKNOWN;
    }

    /**
     * 격자 구조 게이트가 "이 격자는 4x4 가 아니다" 로 막았는가.
     *
     * ★ 이 표식은 후처리 스크립트({@code zzal/pipeline/v4/service_post.py})가 찍고,
     *   {@code PythonPostProcessor} 가 스크립트가 남긴 말을 예외 메시지에 그대로 붙여 올린다.
     *   판정은 <b>코드가 결정적으로</b> 한다 — 표식이 있으면 격자를 버리고, 없으면 평범한 재시도다.
     * ★ 표식이 없는 버전(v1·v2)의 스크립트는 이 표식을 찍지 않으므로 예전 동작 그대로다.
     */
    private static boolean gridRejected(Throwable e) {
        return e != null && String.valueOf(e.getMessage()).contains(GRID_STRUCTURE_MARK);
    }

    /** 후처리 스크립트가 격자 구조 이상을 알릴 때 찍는 표식. 스크립트와 글자가 같아야 한다. */
    static final String GRID_STRUCTURE_MARK = "GRID_STRUCTURE_INVALID";
}
