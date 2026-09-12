package com.lore.zzal.generation;

import com.lore.zzal.generation.steps.GridStep;
import com.lore.zzal.generation.steps.IdentityStep;
import com.lore.zzal.generation.steps.PostProcessStep;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.motion.MotionSeeder;
import com.lore.zzal.pet.ZzalPetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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

    @Async("hatchExecutor")
    public void hatch(Long jobId, Long petId, String version) {
        RunResult first = runAttempt(jobId, petId, version);
        if (first != null && first.success()) {
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
            discardModerationInputs(petId, version);
        }

        // ★ 격자 구조 게이트가 막은 것이면 격자부터 다시 굽는다 — 거부와 같은 종류의 병이다.
        //   재시도는 성공한 단계를 건너뛰므로, 격자를 폐기하지 않으면 **바로 그 깨진 격자**를
        //   다시 자르게 되고 두 번째도 같은 자리에서 실패한다(시간만 쓰고 결과는 같다).
        if (first != null && first.gridRejected()) {
            int discarded = recorder.discardSucceeded(petId, GenKind.HATCH, GridStep.NAME)
                    + recorder.discardSucceeded(petId, GenKind.HATCH, PostProcessStep.GRID2);
            log.info("격자 구조 이상 — 격자 {}건을 폐기하고 다시 굽는다 (petId={})", discarded, petId);
        }

        // 다시 한 번. 나머지 성공한 단계는 그대로 이어받으므로 실패한 지점부터 시작된다.
        GenJob retry = jobRepository.save(
                GenJob.start(petId, GenKind.HATCH, (int) attempts + 1, version, Instant.now()));
        log.info("재시도 — petId={} attempt={}", petId, attempts + 1);
        RunResult second = runAttempt(retry.getId(), petId, version);
        if (second == null || !second.success()) {
            recorder.markPetFailed(petId);
        }
    }

    /**
     * 한 번 굽는다. 성공하면 펫을 살린다.
     *
     * ★ 재료를 여기서 채워 넘긴다 — 실행기는 무엇을 굽는지 모르고, 부화가 무엇으로
     *   시작하는지(원본 그림)와 무엇으로 끝나는지(살아난 펫)는 부화의 일이다.
     *
     * @return 이번 시도의 결과. 펫이 없어 아예 못 돌렸으면 {@code null}.
     *         성공 여부만이 아니라 <b>왜 실패했는가</b>(격자 자체가 못 쓸 물건인가)를
     *         부르는 쪽이 알아야 재시도 전에 무엇을 폐기할지 정할 수 있다.
     */
    private RunResult runAttempt(Long jobId, Long petId, String version) {
        ZzalPet pet = petRepository.findById(petId).orElse(null);
        if (pet == null) {
            log.warn("펫이 없습니다 — petId={}", petId);
            return null;
        }

        StepContext ctx = new StepContext(petId, pet.getName(), pet.getNote(), version);
        ctx.putImage("source", pet.getSourceImageKey());

        RunResult r = runner.run(jobId, ctx,
                registry.stages(GenKind.HATCH, version),
                recorder.loadSucceeded(petId, GenKind.HATCH, version));
        if (!r.success()) {
            return r;
        }
        log.info("굽기 완료 — petId={} version={} 비용=${}", petId, version, r.costUsd());
        // ★ 굽기가 끝났다고 바로 살리지 않는다 — 이름이 아직 없을 수 있다(아래).
        completeIfReady(petId, version);
        return r;
    }

    /**
     * <b>굽기가 끝났고 이름도 들어왔으면</b> 살린다. 둘 중 하나라도 없으면 아무 일도 안 한다.
     *
     * <h3>★ 왜 두 조건인가</h3>
     * 그림을 올리는 순간부터 끝까지 굽기 때문에, 사용자가 이름을 짓는 동안 굽기가 먼저 끝날 수 있다.
     * 그때 바로 살리면 <b>이름 없는 펫이 방에 나타난다.</b> 반대로 이름이 먼저 들어올 수도 있다.
     * 그래서 <b>둘 다 갖춰진 순간</b>에 살리고, 그 순간이 어느 쪽인지는 상관하지 않는다.
     *
     * <h3>★ 두 길에서 불린다</h3>
     * 굽기가 끝난 직후(여기)와, 이름이 들어와 커밋된 직후({@code PetNamed} 알림)다.
     * 먼저 도착한 쪽은 조건이 모자라 그냥 돌아가고, 나중에 도착한 쪽이 살린다.
     *
     * @return 이번 호출로 살아났으면 true
     */
    public boolean completeIfReady(Long petId, String version) {
        ZzalPet pet = petRepository.findById(petId).orElse(null);
        if (pet == null || pet.getName() == null || pet.getName().isBlank()) {
            return false;               // 이름을 아직 안 지었다 — 굽기만 끝난 상태로 기다린다
        }
        List<GenStepRecord> done = recorder.loadSucceeded(petId, GenKind.HATCH, version);
        // ★★ 개수가 아니라 <b>이름 집합</b>으로 본다. 세기만 하면 sheet 가 빠지고 엉뚱한 이름이 하나
        //   들어와도 개수가 맞아 그대로 통과한다 — 그러면 sheetKey 나 identityText 가 null 인 채로
        //   펫이 ALIVE 가 되고, 필수 산출물 없이 사용자에게 지급된다. 예외도 로그도 안 난다.
        Set<String> succeeded = done.stream().map(GenStepRecord::getName).collect(Collectors.toSet());
        Set<String> expected = stepNames(version);
        if (!succeeded.containsAll(expected)) {
            return false;               // 아직 굽는 중이다(또는 빠진 산출물이 있다)
        }
        String sheetKey = outputOf(done, com.lore.zzal.generation.steps.SheetStep.NAME, GenStepRecord::getOutputKey);
        String identity = outputOf(done, IdentityStep.NAME, GenStepRecord::getOutputText);

        Instant now = Instant.now();
        recorder.markPetAlive(petId, sheetKey, identity, now);
        // ★ 부화 완료 = 동작 18행(정본 13장). 1층 8종은 이 순간이 열린 시각. 심화 행동은 아직(NONE).
        //   두 번 불려도 이미 있는 seq 는 건너뛴다(MotionSeeder).
        motionSeeder.seed(petId, now);
        log.info("부화 완료 — petId={} version={}", petId, version);
        return true;
    }

    /**
     * 거부(MODERATION)로 다시 굽기 전에 <b>문단과 그 문단을 재료로 쓴 단계들</b>을 함께 폐기한다.
     *
     * <h3>★★ 문단만 지우면 격자 두 장의 근거가 갈린다</h3>
     * 재시도는 성공한 단계를 건너뛴다. 1층 격자가 성공하고 2층만 거부된 경우, 문단만 지우면
     * <b>1층은 옛 문단으로 구운 그림을 그대로 쓰고 2층만 새 문단으로</b> 구워진다.
     * 같은 아이인데 두 격자의 묘사 근거가 달라, 1층과 2층의 생김새가 어긋난 채 사용자에게 간다.
     * 예외가 안 나므로 아무도 모른다 — 그림을 열어 봐야만 드러난다.
     *
     * ★ 무엇이 문단에 기대는지는 {@link PipelineRegistry#identityDependents} 한 곳에 적혀 있다.
     */
    private void discardModerationInputs(Long petId, String version) {
        int discarded = 0;
        for (String step : registry.identityDependents(GenKind.HATCH, version)) {
            discarded += recorder.discardSucceeded(petId, GenKind.HATCH, step);
        }
        log.info("거부로 실패 — 문단과 그것을 쓴 단계 {}건을 폐기하고 다시 만든다 (petId={})", discarded, petId);
    }

    private static String outputOf(List<GenStepRecord> done, String stepName,
                                   java.util.function.Function<GenStepRecord, String> field) {
        return done.stream()
                .filter(r -> stepName.equals(r.getName()))
                .map(field)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
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
        return stepNames(version).size();
    }

    /**
     * 그 버전이 끝나려면 <b>어떤 이름들</b>이 성공해 있어야 하나.
     *
     * ★ 개수가 아니라 이름이다 — 완료 판정이 개수만 세면 엉뚱한 이름이 섞여도 통과한다.
     *   {@code stepsTotal} 도 같은 집합의 크기로 답해 진행률과 판정이 어긋나지 않게 한다.
     */
    private Set<String> stepNames(String version) {
        return registry.steps(GenKind.HATCH, version).stream()
                .map(GenerationStep::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public String currentVersion() {
        return registry.currentVersion(GenKind.HATCH);
    }
}
