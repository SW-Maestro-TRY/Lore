package com.lore.zzal.motion;

import com.lore.zzal.generation.GenJob;
import com.lore.zzal.generation.GenJobRepository;
import com.lore.zzal.generation.GenKind;
import com.lore.zzal.generation.GenStepRecordRepository;
import com.lore.zzal.generation.GenerationRecorder;
import com.lore.zzal.generation.GenerationRunner;
import com.lore.zzal.generation.PipelineRegistry;
import com.lore.zzal.generation.RunResult;
import com.lore.zzal.generation.StepContext;
import com.lore.zzal.generation.steps.MotionGridStep;
import com.lore.zzal.generation.steps.MotionPostStep;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalPetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 모션 한 개를 끝까지 책임진다 — 굽고, 실패하면 다시 굽고, 게이트에 물어보고, 열어 준다.
 *
 * <h3>언제 도는가</h3>
 * 재우는 순간 시작해서 자는 동안(6시간) 돈다. 깨우면 이미 준비돼 있다.
 * 이 구조가 세 가지를 한꺼번에 푼다 — 생성 대기가 "자는 중" 으로 흡수되고,
 * 실패해서 다시 굽는 시간이 사용자에게 안 보이고, 상훈님 확인 시간도 그 안에 들어간다.
 *
 * <h3>★ 한 장씩 굽는다</h3>
 * 여러 장을 굽고 그중 고르는 방식도 있지만, 동작 하나가 격자 한 장이라 장수만큼 돈이 곱해진다.
 * 원장 통과율이 95.7% 라 기대 장수는 약 1.05장이다 — <b>한 장 굽고 실패하면 다시</b> 가 싸다
 * (2026-09-03 상훈님 확정, 최대 3번).
 */
@Service
public class MotionService {

    private static final Logger log = LoggerFactory.getLogger(MotionService.class);

    private final GenerationRunner runner;
    private final GenerationRecorder recorder;
    private final MotionRecorder motionRecorder;
    private final GenJobRepository jobRepository;
    private final GenStepRecordRepository stepRepository;
    private final ZzalMotionRepository motionRepository;
    private final ZzalPetRepository petRepository;
    private final PipelineRegistry registry;
    private final MotionCatalog catalog;
    private final MotionGate gate;
    private final int maxAttempts;

    public MotionService(GenerationRunner runner, GenerationRecorder recorder,
                         MotionRecorder motionRecorder,
                         GenJobRepository jobRepository, GenStepRecordRepository stepRepository,
                         ZzalMotionRepository motionRepository, ZzalPetRepository petRepository,
                         PipelineRegistry registry, MotionCatalog catalog, MotionGate gate,
                         @Value("${app.zzal.max-motion-attempts:3}") int maxAttempts) {
        this.runner = runner;
        this.recorder = recorder;
        this.motionRecorder = motionRecorder;
        this.jobRepository = jobRepository;
        this.stepRepository = stepRepository;
        this.motionRepository = motionRepository;
        this.petRepository = petRepository;
        this.registry = registry;
        this.catalog = catalog;
        this.gate = gate;
        this.maxAttempts = maxAttempts;
    }

    /**
     * 자는 동안 다음 동작을 굽는다.
     *
     * ★ 기다리지 않고 즉시 돌아간다(재우기 응답이 생성을 붙들고 있으면 안 된다).
     */
    @Async("hatchExecutor")
    public void bake(Long motionId) {
        bakeNow(motionId);
    }

    /** 같은 일을 부른 스레드에서. 밤 스위프는 자기 실행기(nightExecutor)에서 이걸 부른다 — hatchExecutor 를 부화와 안 나눈다. */
    public void bakeNow(Long motionId) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (runAttempt(motionId, attempt)) {
                return;
            }
            log.warn("모션 실패 — motionId={} attempt={}/{}", motionId, attempt, maxAttempts);
        }
        // 다 써도 못 구웠다. 사용자에게는 안 보이고, 상훈님이 나중에 다시 구워 넣으실 수 있다.
        motionRecorder.markFailed(motionId);
        log.warn("모션 실패 확정 — motionId={} 시도={}회", motionId, maxAttempts);
    }

    /** 한 번 굽는다. 성공하면 게이트에 물어보고 열어 준다. */
    private boolean runAttempt(Long motionId, int attempt) {
        ZzalMotion motion = motionRepository.findById(motionId).orElse(null);
        if (motion == null) {
            log.warn("모션이 없습니다 — motionId={}", motionId);
            return false;
        }
        ZzalPet pet = petRepository.findById(motion.getPetId()).orElse(null);
        if (pet == null) {
            log.warn("펫이 없습니다 — petId={}", motion.getPetId());
            return false;
        }

        // ★ 버전이 비어 있으면 지금 버전으로 채운다. 옛 데이터나 손으로 넣은 행이 섞이면
        //   여기서 null 이 그대로 흘러가 job 저장이 터지는데, 굽기는 비동기라
        //   **로그만 남고 아무도 모른 채** 그 모션이 영영 PENDING 으로 남는다.
        String version = motion.getPipelineVersion() != null
                ? motion.getPipelineVersion()
                : registry.currentVersion(GenKind.MOTION);
        GenJob job = jobRepository.save(
                GenJob.startMotion(pet.getId(), motionId, attempt, version, Instant.now()));
        motionRecorder.beginAttempt(motionId);

        StepContext ctx = new StepContext(
                pet.getId(), pet.getName(), pet.getNote(), version,
                "images/zzal/pets/%d/motions/%d".formatted(pet.getId(), motionId));

        // ★ 시트와 정체성 문단은 부화 때 만든 것을 그대로 쓴다. 다시 만들면 돈이 더 들고,
        //   무엇보다 캐릭터가 조금씩 달라진다.
        ctx.putImage(MotionGridStep.SHEET_IN, pet.getSheetImageKey());
        ctx.putText(MotionGridStep.IDENTITY_IN, pet.getIdentityText());
        ctx.putText(MotionGridStep.MOTION_IN, catalog.block(motion.getName()));

        // ★ 이어받기는 반드시 이 모션 것만. 펫으로 묶으면 다른 동작의 격자를 물려받는다.
        RunResult r = runner.run(job.getId(), ctx,
                registry.steps(GenKind.MOTION, version),
                stepRepository.findSucceededByMotion(motionId));
        if (!r.success()) {
            return false;
        }

        String imageKey = ctx.image(MotionPostStep.NAME);
        MotionGate.Verdict v = gate.judge(imageKey);

        if (v.verdict() == GateVerdict.FAIL) {
            // 게이트가 실패라 하면 다시 굽는다. 판정은 기록에 남겨 두 판정을 비교할 수 있게 한다.
            motionRecorder.recordGate(motionId, imageKey, v);

            // ★★ 성공 기록을 지워야 다음 시도가 실제로 다시 굽는다.
            //   재시도는 성공한 단계를 건너뛰는데, 격자도 후처리도 "성공" 으로 남아 있으면
            //   실행기가 둘 다 건너뛰고 <b>방금 퇴짜 맞은 그 그림을 또 판정한다.</b>
            //   그러면 세 번을 시도해도 같은 결과가 세 번 나오고 시간만 쓴다.
            int discarded = recorder.discardMotionSteps(motionId);
            log.info("게이트 실패 — 성공 기록 {}건을 지우고 다시 굽는다 (motionId={})", discarded, motionId);
            return false;
        }

        // ★ 상훈님 확인 전에도 연다(2026-09-03 확정). 밤에 재운 사용자가 아침에 깼을 때
        //   상훈님이 주무시는 동안 갇히면 안 된다. 확인은 사후에 하고, 반려되면 바꿔 끼운다.
        motionRecorder.open(motionId, imageKey, v, Instant.now());
        log.info("모션 완성 — motionId={} 동작={} 게이트={} 비용=${}",
                motionId, motion.getName(), v.verdict(), r.costUsd());
        return true;
    }

    /** 도감에 보일 것들. 굽는 중이거나 실패한 것은 안 보인다. */
    public List<ZzalMotion> opened(Long petId) {
        return motionRepository.findByPetIdAndStatusOrderBySeqAsc(petId, MotionStatus.OPEN);
    }
}
