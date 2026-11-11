package com.lore.zzal.generation;

import com.lore.zzal.alert.ZzalAlerts;
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
    private final ZzalAlerts alerts;

    /**
     * 단계가 도는 곳 — 창구의 가상 스레드 실행기(설계 3-8). 예전의 무제한 감시 풀(아무도 닫지 않고
     * 크기 제한도 없음)을 대신한다. 동시 실행은 스레드 수가 아니라 창구 줄 자리가 막는다.
     */
    private final ExecutorService stepExecutor;

    /**
     * CPU 를 쓰는 후처리(파이썬 자르기)는 동시에 2개까지(설계 3-8). 가상 스레드라도 CPU 일은 코어 수를 넘기면 안 된다.
     * ★ {@code synchronized} 가 아니라 세마포어 — 가상 스레드 핀 고정을 피한다.
     */
    private final java.util.concurrent.Semaphore postprocess = new java.util.concurrent.Semaphore(POSTPROCESS_PERMITS);
    static final int POSTPROCESS_PERMITS = 2;
    private static final java.util.Set<String> POSTPROCESS_STEPS = java.util.Set.of(
            com.lore.zzal.generation.steps.PostProcessStep.NAME,
            com.lore.zzal.generation.steps.Layer2PostStep.NAME,
            com.lore.zzal.generation.steps.MotionPostStep.NAME);

    /** 검증용 제한 시간(초). 0 이면 단계가 정한 값을 쓴다. 운영에서는 절대 켜지 않는다. */
    @org.springframework.beans.factory.annotation.Value("${app.zzal.generation.limit-override-seconds:0}")
    private int limitOverrideSeconds;

    /**
     * 줄 자리 대기를 단계 기한에서 빼 주는 상한(초). 단계 제한은 자리를 얻은 뒤부터 세지만(P1 검토 표 2번),
     * 자리 대기 자체가 끝없이 길어지면(자리 누수·줄 막힘) 부화 스레드가 영영 묶이므로 여기까지만 빼 준다.
     * 기본 300초 = 그림 요청 제한(90초)짜리 호출 세 바퀴. 그보다 길면 TIMEOUT 으로 드러나게 한다.
     */
    @org.springframework.beans.factory.annotation.Value("${app.zzal.generation.lane-wait-cap-seconds:300}")
    private int laneWaitCapSeconds = 300;

    /**
     * 시험 전용 — 창구 없이 혼자 쓰는 가상 스레드 실행기. 이 실행기는 HTTP 를 보내지 않는다(단계가 쓰는 클라이언트가
     * 창구를 거친다). 운영 빈은 아래 창구 생성자만 쓴다.
     */
    public GenerationRunner(GenerationRecorder recorder, ZzalAlerts alerts) {
        this(recorder, alerts, Executors.newVirtualThreadPerTaskExecutor());
    }

    /** 운영 생성자. ★ 창구는 필수다(P1 검토 표 3번) — 없으면 기동이 실패한다. */
    @org.springframework.beans.factory.annotation.Autowired
    public GenerationRunner(GenerationRecorder recorder, ZzalAlerts alerts, com.lore.zzal.openai.OpenAiGateway gateway) {
        this(recorder, alerts, java.util.Objects.requireNonNull(gateway, "OpenAI 창구 빈이 없습니다(OpenAiGatewayConfig)").executor());
    }

    private GenerationRunner(GenerationRecorder recorder, ZzalAlerts alerts, ExecutorService stepExecutor) {
        this.recorder = recorder;
        this.alerts = alerts;
        this.stepExecutor = stepExecutor;
    }

    /**
     * 한 판이 끝날 때마다 <b>누적 비용이 다음 $10 단위를 넘었는지</b> 본다.
     *
     * <h3>★ 왜 여기인가 — 돈이 나가는 모든 길이 이 한 곳을 지난다</h3>
     * 부화도 심화 행동도 결국 이 메서드로 굽는다. 부화 쪽에만 달면 밤 굽기로 나간 돈은
     * <b>한 번도 안 알린다</b>. 성공·실패·한도 막힘 어느 쪽으로 끝나도 그때까지 나간 돈은 이미
     * job 에 적혔으므로, 나가는 길이 아니라 <b>끝나는 길 전부</b>에 건다({@code finally}).
     *
     * <h3>★★ 경보가 굽기를 되돌리지 않는다</h3>
     * {@code finally} 에서 예외가 나면 원래 돌려주려던 결과가 사라지고 부화가 통째로 깨진다.
     * 그래서 {@link ZzalAlerts} 는 어떤 경우에도 예외를 안 내보낸다(그 클래스 주석) —
     * 여기서 한 번 더 감싸지 않는 이유가 그것이고, 그 규약은 시험이 못 박는다.
     */
    public RunResult run(Long jobId, StepContext ctx, List<List<GenerationStep>> stages,
                         List<GenStepRecord> resume) {
        boolean[] interrupted = {false};
        try {
            return bake(jobId, ctx, stages, resume, interrupted);
        } finally {
            alerts.generationFinished(Instant.now());
            // ★ 바깥 끼어들기(종료 등)를 받았으면 기록을 다 남긴 뒤 표시를 되살린다 — 부르는 쪽이
            //   "끊겼다"를 알고 새 시도를 시작하지 않게(설계 3-8).
            if (interrupted[0]) {
                Thread.currentThread().interrupt();
            }
        }
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
    private RunResult bake(Long jobId, StepContext ctx, List<List<GenerationStep>> stages,
                           List<GenStepRecord> resume, boolean[] interrupted) {
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
            if (outcome.outerInterrupted()) {
                interrupted[0] = true;
            }
            if (outcome.error() != null) {
                recorder.failJob(jobId, outcome.error(), total);
                if (outcome.quotaBlocked()) {
                    // ★★ 바깥이 한도로 막았다 — 다시 구우면 또 막히고 돈만 두 번 나간다.
                    return RunResult.quotaBlocked(ctx, total, outcome.error()).withDetail(outcome.detail());
                }
                // ★ 격자 구조 게이트가 막았거나 후처리 스크립트가 죽은 것이면 같은 격자로 다시 해 봐야
                //   소용없다 — 부르는 쪽에 알린다(무엇을 버릴지는 부르는 쪽이 정한다).
                if (outcome.gridRejected() || outcome.postprocessCrashed()) {
                    return RunResult.failedWith(ctx, total, outcome.error(),
                            outcome.gridRejected(), outcome.postprocessCrashed(), outcome.failedGrid())
                            .withDetail(outcome.detail());
                }
                return RunResult.failed(ctx, total, outcome.error()).withDetail(outcome.detail());
            }
        }

        recorder.succeedJob(jobId, total, Instant.now());
        log.info("생성 완료 — petId={} version={} 비용=${}", ctx.petId(), version, total);
        return RunResult.ok(ctx, total);
    }

    /** 아직 안 돈 단계와 그 순번. */
    private record Pending(GenerationStep step, int seq) {
    }

    /**
     * 돌고 있는 단계 — 기록 번호, 제출 시각과 제 시간 제한, 줄 자리 대기 시계를 함께 들고 있는다.
     * 기한 = 제출 시각 + 제한 + 자리 대기(상한까지). 자리를 기다린 시간은 기한에 넣지 않는다(P1 검토 표 2번).
     */
    private record Running(GenerationStep step, Long stepId, Future<StepResult> future,
                           long startNanos, long limitNanos, com.lore.zzal.openai.LaneWaitClock clock) {
    }

    /** 이 단계의 지금 기한(nanoTime). */
    private long deadline(Running r, long now) {
        long excluded = Math.min(r.clock().waitedNanos(now), TimeUnit.SECONDS.toNanos(Math.max(0, laneWaitCapSeconds)));
        return r.startNanos() + r.limitNanos() + excluded;
    }

    /**
     * 기한까지 결과를 기다린다. 기다리는 사이 단계가 줄 자리를 기다렸으면 기한이 그만큼 늦춰지므로 다시 기다린다.
     *
     * @throws TimeoutException 자리를 얻은 뒤의 시간이 제한을 넘었다(또는 자리 대기가 상한을 넘었다)
     */
    private StepResult await(Running r) throws InterruptedException, ExecutionException, TimeoutException {
        while (true) {
            long left = deadline(r, System.nanoTime()) - System.nanoTime();
            try {
                return r.future().get(Math.max(0, left), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                if (deadline(r, System.nanoTime()) - System.nanoTime() <= 0) {
                    throw e;
                }
                // 기다리는 사이 자리 대기만큼 기한이 늦춰졌다 — 남은 만큼 더 기다린다.
            }
        }
    }

    /**
     * 한 묶음의 결과. {@code error} 가 있으면 그 묶음에서 멈춘다.
     *
     * ★ {@code gridRejected} 는 "이 격자는 못 쓴다" 는 별도의 신호다. 실패 코드와 따로 두는 이유는
     *   {@link GenErrorCode} 가 DB 컬럼의 CHECK 제약에 묶여 있어 값을 늘리려면 마이그레이션이
     *   필요하기 때문이다(→ {@link RunResult#gridRejected}).
     */
    private record StageOutcome(BigDecimal cost, GenErrorCode error, boolean gridRejected,
                               boolean quotaBlocked, boolean postprocessCrashed, String failedGrid,
                               String detail, boolean outerInterrupted) {
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
            // 그림 줄 차례(부화 먼저)는 스레드에 붙어 있어 단계 스레드에서 다시 연다.
            com.lore.zzal.openai.CallPriority priority = com.lore.zzal.openai.CallPriority.current();
            boolean cpu = POSTPROCESS_STEPS.contains(p.step().name());
            var clock = new com.lore.zzal.openai.LaneWaitClock();
            Callable<StepResult> task = () -> {
                try (var order = com.lore.zzal.openai.CallPriority.open(priority);
                     var waits = com.lore.zzal.openai.LaneWaitClock.open(clock);
                     var usage = com.lore.zzal.generation.client.ProviderUsageContext.open(
                        "generation_" + p.step().name(), ctx.petId(), jobId, "step-" + stepId, 1)) {
                    if (!cpu) {
                        return p.step().run(ctx);
                    }
                    postprocess.acquire();
                    try {
                        return p.step().run(ctx);
                    } finally {
                        postprocess.release();
                    }
                }
            };
            Future<StepResult> future;
            try {
                future = stepExecutor.submit(task);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // ★ 창구 실행기가 먼저 닫혔다(종료 중) — 끼어들기와 같다(P1 검토 표 1번). 이미 낸 단계도 끊고
                //   전부 INTERRUPTED·비용 0 으로 적는다. 올려 보내면 잡이 RUNNING 고아로 남는다.
                log.warn("단계 실행기가 닫혀 있다(종료 중) — 끊긴 것으로 적는다 jobId={} step={}", jobId, p.step().name());
                recorder.failStep(stepId, GenErrorCode.INTERRUPTED, BigDecimal.ZERO);
                for (Running r : running) {
                    r.future().cancel(true);
                    recorder.failStep(r.stepId(), GenErrorCode.INTERRUPTED, BigDecimal.ZERO);
                }
                return new StageOutcome(BigDecimal.ZERO, GenErrorCode.INTERRUPTED, false, false, false, null,
                        "실행기 닫힘 — " + p.step().name(), true);
            }
            running.add(new Running(p.step(), stepId, future, System.nanoTime(), TimeUnit.SECONDS.toNanos(limit), clock));
        }
        if (running.size() > 1) {
            log.info("나란히 굽기 {}개 — jobId={} steps={}", running.size(), jobId,
                    running.stream().map(r -> r.step().name()).toList());
        }

        BigDecimal cost = BigDecimal.ZERO;
        GenErrorCode error = null;
        boolean gridRejected = false;
        boolean quotaBlocked = false;
        boolean postprocessCrashed = false;
        String failedGrid = null;
        String detail = null;
        List<StepResult> done = new ArrayList<>(running.size());
        boolean outerInterrupted = false;

        for (Running r : running) {
            if (outerInterrupted) {
                // 바깥이 이미 끊겼다 — 남은 단계도 끊긴 것으로 적는다(응답을 못 받았으니 비용 0).
                r.future().cancel(true);
                recorder.failStep(r.stepId(), GenErrorCode.INTERRUPTED, BigDecimal.ZERO);
                error = worse(error, GenErrorCode.INTERRUPTED);
                continue;
            }
            try {
                StepResult result = await(r);
                recorder.succeedStep(r.stepId(), result);
                done.add(result);
                cost = cost.add(result.costUsd());
            } catch (InterruptedException e) {
                // ★★ 바깥(부화 스레드)이 끊겼다 — 안쪽 단계도 전부 끊는다(설계 3-8). 안 끊으면 아무도 안 받을
                //   응답에 돈이 나간다. cancel(true) 가 가상 스레드를 끊고, JDK HttpClient 는 진행 중 요청을 취소한다.
                outerInterrupted = true;
                for (Running other : running) {
                    other.future().cancel(true);
                }
                log.warn("끼어들기 — 진행 중 단계를 끊는다 jobId={} step={}", jobId, r.step().name());
                recorder.failStep(r.stepId(), GenErrorCode.INTERRUPTED, BigDecimal.ZERO);
                error = worse(error, GenErrorCode.INTERRUPTED);
                if (detail == null) {
                    detail = "끼어들기 — " + r.step().name();
                }
            } catch (TimeoutException e) {
                r.future().cancel(true);
                log.warn("시간 초과 — jobId={} step={}", jobId, r.step().name());
                // ★ 끊은 호출은 얼마가 나갔는지 알 길이 없다(응답을 못 받았다). 0 이 맞다.
                recorder.failStep(r.stepId(), GenErrorCode.TIMEOUT, BigDecimal.ZERO);
                error = worse(error, GenErrorCode.TIMEOUT);
                if (detail == null) {
                    detail = "시간 초과 — " + r.step().name();
                }
            } catch (Exception e) {
                Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
                GenErrorCode code = classify(cause);
                // ★ 나란히 도는 묶음에서 한 장만 게이트에 막혀도 그 격자는 버려야 한다 — 누적한다.
                gridRejected |= gridRejected(cause);
                // ★ 한 장만 한도에 걸려도 그 시도 전체가 한도에 걸린 것이다(같은 계정·같은 키).
                quotaBlocked |= quotaBlocked(cause);
                // ★ 후처리 스크립트가 0 이 아닌 코드로 끝났나(시간 초과 제외) · 어느 격자를 자르다 그랬나.
                postprocessCrashed |= postprocessCrashed(cause);
                if (failedGrid == null) {
                    failedGrid = failedGrid(cause);
                }
                if (detail == null) {
                    detail = r.step().name() + ": " + String.valueOf(cause == null ? e : cause.getMessage());
                }
                // ★★ 실패해도 <b>이미 나간 돈</b>은 적는다. 유료 호출은 200 이 돌아온 순간 과금이 끝나므로,
                //   응답 파싱·S3 업로드에서 터진 실패는 공짜가 아니다. 여기서 안 더하면 원가가
                //   실제보다 낮게 보여 중복 과금이나 급증을 못 본다.
                BigDecimal billed = BilledFailureException.billed(e);
                log.warn("단계 실패 — jobId={} step={} code={} 비용=${} : {}",
                        jobId, r.step().name(), codeLabel(code, cause), billed, String.valueOf(e));
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
        return new StageOutcome(cost, error, gridRejected, quotaBlocked, postprocessCrashed, failedGrid, detail,
                outerInterrupted);
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
    static GenErrorCode classify(Throwable e) {
        if (e == null) {
            return GenErrorCode.UNKNOWN;
        }
        // 끼어들기(서버 종료·바깥 취소) — 응답을 못 받았다. 처방은 "그대로 이어 굽기"(3-5).
        if (e instanceof InterruptedException || e instanceof java.util.concurrent.CancellationException
                || e instanceof java.nio.channels.ClosedByInterruptException) {
            return GenErrorCode.INTERRUPTED;
        }
        String msg = String.valueOf(e.getMessage()).toLowerCase();
        if (msg.contains("moderation") || msg.contains("safety") || msg.contains("content_policy")) {
            return GenErrorCode.MODERATION_BLOCKED;
        }
        if (quotaBlocked(e)) {
            return GenErrorCode.QUOTA;
        }
        if (gridRejected(e)) {
            return GenErrorCode.GATE_REJECTED;
        }
        if (postprocessCrashed(e)) {
            return GenErrorCode.POSTPROCESS_FAILED;
        }
        // HTTP 요청 시간 제한(HttpRequest.timeout)에 걸렸다 — 단계 시간 초과와 같은 처방.
        if (e instanceof java.net.http.HttpTimeoutException) {
            return GenErrorCode.TIMEOUT;
        }
        // OpenAI 5xx·연결 실패 — 창구가 ProviderServerException 으로 올린다.
        if (causedBy(e, com.lore.zzal.openai.ProviderServerException.class) || e instanceof java.io.IOException) {
            return GenErrorCode.PROVIDER_ERROR;
        }
        return GenErrorCode.UNKNOWN;
    }

    /**
     * 격자 구조 게이트가 "이 격자는 4x4 가 아니다" 로 막았는가.
     *
     * ★ 이 표식은 후처리 스크립트({@code zzal/pipeline/v1/service_post.py})가 찍고,
     *   {@code PythonPostProcessor} 가 스크립트가 남긴 말을 예외 메시지에 그대로 붙여 올린다.
     *   판정은 <b>코드가 결정적으로</b> 한다 — 표식이 있으면 격자를 버리고, 없으면 평범한 재시도다.
     * ★ 이 표식을 안 찍는 스크립트를 쓰는 버전은 예전 동작 그대로다.
     */
    /**
     * 바깥이 <b>한도</b>로 막았는가 — 창구가 429·잔액·한도 응답을 {@link com.lore.zzal.openai.ProviderQuotaException}
     * 하나로 올린다(설계 2-2). 예전에는 예외 메시지의 문구로 갈랐다.
     *
     * ★ 예외가 감싸여 올라올 수 있어 원인 사슬을 따라간다.
     */
    static boolean quotaBlocked(Throwable e) {
        return causedBy(e, com.lore.zzal.openai.ProviderQuotaException.class);
    }

    private static boolean causedBy(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /**
     * 후처리 스크립트가 <b>0 이 아닌 코드로</b> 끝났나 — {@code PythonPostProcessor} 가 붙이는
     * "후처리 실패(exit N)" 문구로 가른다. 시간 초과("후처리 시간 초과")는 여기 안 걸린다(재시도만, 폐기 없음).
     */
    static boolean postprocessCrashed(Throwable e) {
        return e != null && String.valueOf(e.getMessage()).contains(POSTPROCESS_EXIT_MARK);
    }

    /**
     * 어느 격자를 자르다 실패했나 — {@code PostProcessStep} 이 예외 메시지 앞에 붙이는
     * {@code [격자=grid2]} 표식에서 읽는다. 없으면 null(부르는 쪽이 둘 다 버린다).
     */
    static String failedGrid(Throwable e) {
        if (e == null) {
            return null;
        }
        java.util.regex.Matcher m = GRID_SOURCE.matcher(String.valueOf(e.getMessage()));
        return m.find() ? m.group(1) : null;
    }

    /** {@code PythonPostProcessor} 가 스크립트 종료 코드를 알릴 때 쓰는 문구. 글자가 같아야 한다. */
    static final String POSTPROCESS_EXIT_MARK = "후처리 실패(exit ";

    /** {@code PostProcessStep#GRID_SOURCE_FORMAT} 과 짝. */
    private static final java.util.regex.Pattern GRID_SOURCE = java.util.regex.Pattern.compile("\\[격자=([A-Za-z0-9_]+)]");

    /**
     * 로그에 찍는 실패 코드. 격자 구조 게이트가 막은 것은 {@code GRID_STRUCTURE_INVALID} 로 적는다.
     *
     * ★ 기록({@code zzal_gen_step.error_code})에는 여전히 {@link GenErrorCode} 값이 들어간다 — 그 칼럼은
     *   값 목록 CHECK 에 묶여 있어 새 값을 넣으려면 마이그레이션이 필요하다({@link RunResult#gridRejected}).
     *   게이트 거부를 {@code code=UNKNOWN} 으로 찍으면 원인 모를 실패로 읽혀 로그를 다시 뒤지게 된다(#706).
     */
    static String codeLabel(GenErrorCode code, Throwable cause) {
        if (code != GenErrorCode.MODERATION_BLOCKED && gridRejected(cause)) {
            return GRID_STRUCTURE_MARK;
        }
        return String.valueOf(code);
    }

    private static boolean gridRejected(Throwable e) {
        return e != null && String.valueOf(e.getMessage()).contains(GRID_STRUCTURE_MARK);
    }

    /** 후처리 스크립트가 격자 구조 이상을 알릴 때 찍는 표식. 스크립트와 글자가 같아야 한다. */
    static final String GRID_STRUCTURE_MARK = "GRID_STRUCTURE_INVALID";
}
