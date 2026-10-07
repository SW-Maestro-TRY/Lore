package com.lore.zzal.generation;

import com.lore.zzal.alert.ZzalAlerts;
import com.lore.zzal.generation.steps.IdentityStep;
import com.lore.zzal.generation.steps.Layer2PostStep;
import com.lore.zzal.generation.steps.PostProcessStep;
import com.lore.zzal.generation.steps.SheetStep;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalPetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 2층(격자 2장·8종)을 <b>부화가 끝난 뒤에</b> 굽는다(#696 — 1층 우선 부화).
 *
 * <h3>★ 왜 떼어 냈나</h3>
 * 운영 10/6 실측 — 격자 78장 중 거부 15장, 그중 2층 격자 거부도 펫 하나를 통째로 실패시켰다. 1층만 성공해도
 * 아이는 방에 들어올 수 있다(1층 8종이 처음부터 열린 전부다). 2층은 행동 조건으로 열리는데 실측상 가장 빨라도
 * 부화 뒤 14시간 넘어 열리므로, 그 사이에 뒤에서 구우면 사용자는 기다릴 일이 거의 없다.
 *
 * <h3>★ 틀은 부화·선물과 같다</h3>
 * <ul>
 *   <li>큐 = 선물 움짤을 굽는 {@code nightExecutor}(2스레드). 부화(hatchExecutor 3)와 자리를 다투지 않는다.</li>
 *   <li>재시도 = 같은 job 표에 시도마다 한 줄({@code kind = LAYER2}). 성공한 단계(grid2)는 이어받고,
 *       게이트 거부·후처리 실패면 그 격자를 {@code rejected/} 에 보존하고 버린 뒤 다시 굽는다.</li>
 *   <li>상한 = {@code app.zzal.layer2.max-attempts}(기본은 부화와 같은 값). 다 쓰면 FAILED + 경보 —
 *       사용자에게는 실패 화면이 없다(2층이 "연습 중" 으로 남는다). 관리자 화면에서 살린다.</li>
 *   <li>바깥 한도(429)면 다시 굽지 않는다(돈만 두 배) — 바로 FAILED, 관리자가 재시도한다.</li>
 * </ul>
 */
@Service
public class Layer2Service {

    private static final Logger log = LoggerFactory.getLogger(Layer2Service.class);

    private final GenerationRunner runner;
    private final GenerationRecorder recorder;
    private final Layer2Recorder layer2;
    private final GenJobRepository jobRepository;
    private final PipelineRegistry registry;
    private final ZzalPetRepository petRepository;
    private final RejectedGridArchive archive;
    private final ZzalAlerts alerts;
    private final Executor executor;
    private final int maxAttempts;

    public Layer2Service(GenerationRunner runner, GenerationRecorder recorder, Layer2Recorder layer2,
                         GenJobRepository jobRepository, PipelineRegistry registry,
                         ZzalPetRepository petRepository, RejectedGridArchive archive, ZzalAlerts alerts,
                         @Qualifier("nightExecutor") Executor executor,
                         @Value("${app.zzal.layer2.max-attempts:${app.zzal.max-hatch-attempts:5}}") int maxAttempts) {
        this.runner = runner;
        this.recorder = recorder;
        this.layer2 = layer2;
        this.jobRepository = jobRepository;
        this.registry = registry;
        this.petRepository = petRepository;
        this.archive = archive;
        this.alerts = alerts;
        this.executor = executor;
        this.maxAttempts = maxAttempts;
    }

    /**
     * 뒤에서 굽도록 넘긴다. 부른 쪽은 기다리지 않는다.
     *
     * ★ 넘기기에 실패해도(실행기 종료 중) 펫은 PENDING 으로 남고, 다음 기동의 {@link Layer2Recovery} 가 줍는다.
     */
    public void schedule(Long petId) {
        try {
            executor.execute(() -> bake(petId));
        } catch (RuntimeException e) {
            log.warn("2층 굽기를 넘기지 못했다(다음 기동 때 다시 집는다) — petId={} : {}", petId, String.valueOf(e));
        }
    }

    /**
     * 집고, 굽고, 실패하면 다시 굽고, 다 쓰면 FAILED. <b>이미 굽는 중이거나 READY 면 아무 일도 안 한다.</b>
     */
    public void bake(Long petId) {
        Instant now = Instant.now();
        if (!layer2.claim(petId, now)) {
            log.info("2층 굽기 건너뜀(이미 굽는 중·READY·살아 있지 않음) — petId={}", petId);
            return;
        }
        try {
            loop(petId);
        } catch (RuntimeException e) {
            // ★ 예상 밖 예외로 RUNNING 에 갇히면 아무도 다시 안 굽는다 — FAILED 로 내려 관리자 목록에 올린다.
            log.error("2층 굽기 중 예외 — petId={}", petId, e);
            layer2.failed(petId, "예외: " + e, Instant.now());
            alerts.layer2Failed(petId, String.valueOf(e), Instant.now());
        }
    }

    private void loop(Long petId) {
        while (true) {
            ZzalPet pet = petRepository.findById(petId).orElse(null);
            if (pet == null || !pet.isAlive()) {
                layer2.failed(petId, "살아 있는 펫이 아니다", Instant.now());
                return;
            }
            if (pet.getSheetImageKey() == null) {
                layer2.failed(petId, "시트가 없다(옛 펫)", Instant.now());
                return;
            }
            String version = pet.getHatchPipelineVersion() != null
                    ? pet.getHatchPipelineVersion() : registry.currentVersion(GenKind.HATCH);
            int attempt = layer2.attempts(petId);

            GenJob job = jobRepository.save(GenJob.start(petId, GenKind.LAYER2, attempt, version, Instant.now()));
            StepContext ctx = new StepContext(petId, pet.getName(), pet.getNote(), version);
            ctx.putImage(SheetStep.NAME, pet.getSheetImageKey());
            if (pet.getIdentityText() != null) {
                ctx.putText(IdentityStep.NAME, pet.getIdentityText());
            }
            log.info("2층 굽기 — petId={} attempt={}/{} jobId={}", petId, attempt, maxAttempts, job.getId());
            RunResult r = runner.run(job.getId(), ctx, registry.stages(GenKind.LAYER2, version),
                    recorder.loadSucceeded(petId, GenKind.LAYER2, version));

            if (r.success()) {
                int round = Integer.parseInt(ctx.text(Layer2PostStep.NAME));
                layer2.ready(petId, round, Instant.now());
                log.info("2층 완료 — petId={} 판={} 시도={} 비용=${}", petId, round, attempt, r.costUsd());
                return;
            }

            String reason = summarize(r);
            if (r.quotaBlocked()) {
                log.warn("2층 — 바깥 한도(429), 다시 굽지 않는다 petId={}", petId);
                layer2.failed(petId, reason, Instant.now());
                alerts.layer2Failed(petId, reason, Instant.now());
                return;
            }
            // ★ 못 쓸 격자면 보존하고 버린다 — 안 버리면 재시도가 그 격자를 그대로 다시 자른다.
            //   마지막 시도의 거부 격자도 보존한다(버리지는 않는다 — 관리자 재시도가 제 길에서 버린다).
            boolean bad = r.gridRejected() || r.postprocessCrashed();
            if (bad) {
                for (GenStepRecord rec : recorder.loadSucceeded(petId, GenKind.LAYER2)) {
                    if (PostProcessStep.GRID2.equals(rec.getName())) {
                        archive.preserve(petId, rec);
                    }
                }
            }
            if (attempt >= maxAttempts) {
                log.warn("2층 실패 확정 — petId={} 시도={}회 사유={}", petId, attempt, reason);
                layer2.failed(petId, reason, Instant.now());
                alerts.layer2Failed(petId, reason, Instant.now());
                return;
            }
            if (bad) {
                recorder.discardSucceeded(petId, GenKind.LAYER2, PostProcessStep.GRID2);
            }
            layer2.next(petId, reason, Instant.now());
        }
    }

    /**
     * 관리자 목록에 남길 실패 사유 한 줄.
     *
     * ★ 후처리 실패 메시지는 파이썬 로그 전체가 붙어 온다 — 게이트 표식이 있는 줄을 집고, 없으면 마지막 줄들.
     */
    static String summarize(RunResult r) {
        String d = r.errorDetail();
        if (d == null || d.isBlank()) {
            return r.errorCode() == null ? "알 수 없음" : r.errorCode().name();
        }
        List<String> lines = d.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        String head = lines.isEmpty() ? d : lines.get(0);
        for (String l : lines) {
            if (l.contains(GenerationRunner.GRID_STRUCTURE_MARK)) {
                return cut(head + " / " + l);
            }
        }
        String tail = lines.size() > 1 ? lines.get(lines.size() - 1) : "";
        return cut(tail.isEmpty() ? head : head + " / " + tail);
    }

    private static String cut(String s) {
        return s.length() > 480 ? s.substring(0, 480) : s;
    }
}
