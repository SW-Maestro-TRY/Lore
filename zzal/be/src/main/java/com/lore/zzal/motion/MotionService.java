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
 * 원장 통과율이 95.7% 라 기대 장수는 약 1.05장이다 — <b>한 장 굽고 실패하면 다시</b> 가 싸다.
 *
 * ★ 정본 6장은 <b>API 1회</b>다({@code app.zzal.max-motion-attempts: 1}). API 로 반복하면 같은 돈이 두세 배로 나가고,
 *   재생성은 돈이 안 드는 맥미니(codex)가 맡는다 — 그 배선은 PR-7.
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
    /** 맥미니 재생성 상한(정본 6장 = 2). */
    private final int localRegenMax;

    public MotionService(GenerationRunner runner, GenerationRecorder recorder,
                         MotionRecorder motionRecorder,
                         GenJobRepository jobRepository, GenStepRecordRepository stepRepository,
                         ZzalMotionRepository motionRepository, ZzalPetRepository petRepository,
                         PipelineRegistry registry, MotionCatalog catalog, MotionGate gate,
                         @Value("${app.zzal.max-motion-attempts:1}") int maxAttempts,
                         @Value("${app.zzal.night.local-regen-max:2}") int localRegenMax) {
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
        this.localRegenMax = localRegenMax;
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

    /**
     * 같은 일을 부른 스레드에서. 밤 스위프는 자기 실행기(nightExecutor)에서 이걸 부른다 — hatchExecutor 를 부화와 안 나눈다.
     *
     * <h3>★★ 어떤 예외가 나도 여기서 끝을 낸다</h3>
     * 이 메서드는 <b>남의 스레드에서</b> 돈다. 예외가 그대로 탈출하면 부르는 쪽이 없어 아무도 못 받고,
     * 그 행은 스위프가 이미 {@code BAKING} 으로 집어 둔 상태라 <b>영영 그 자리에 남는다</b> —
     * 다음 밤 계획은 {@code NONE}·{@code FAILED} 만 보고, 스위프의 claim 은 {@code QUEUED} 만 본다.
     * 실제로 지시문 파일 하나가 없어서 이 일이 났다(2026-09-05 리뷰 주입 INJ-C).
     * 그래서 {@code try/catch} 로 감싸 <b>무엇이 터지든 {@code FAILED}</b> 로 내린다 — 다음 밤에 다시 오른다(정본 16장).
     */
    public void bakeNow(Long motionId) {
        try {
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                if (runAttempt(motionId, attempt)) {
                    return;
                }
                log.warn("모션 실패 — motionId={} attempt={}/{}", motionId, attempt, maxAttempts);
            }
        } catch (RuntimeException | Error e) {
            // 지시문 누락·S3·OpenAI·DB — 무엇이든 여기로 온다. 로그만 남기고 두면 BAKING 고착이다.
            // ★ 이 길은 맥미니에게 넘기지 않는다 — 지시문·시트가 없어서 터진 것이면 맥미니도 못 만든다.
            log.error("모션 굽기 중 예외 — motionId={} 를 FAILED 로 내립니다(다음 밤에 다시 오릅니다)", motionId, e);
            markFailedQuietly(motionId);
            return;
        }
        // API 몫이 끝났다. 다시 만드는 일은 돈이 안 드는 맥미니가 맡는다(정본 6장).
        handOverToLocal(motionId);
    }

    /**
     * API 가 못 만든 자리를 맥미니(codex)에게 넘긴다. 한도({@code night.local-regen-max})를 다 썼으면
     * 그 밤은 포기하고 {@code FAILED} — 조각은 소모하지 않고 다음 밤에 같은 동작이 다시 오른다(정본 16장).
     */
    private void handOverToLocal(Long motionId) {
        try {
            if (motionRecorder.requestLocalRegen(motionId, localRegenMax)) {
                log.info("맥미니 재생성 요청 — motionId={} (관리자 GET /regen-requests 로 나간다)", motionId);
            } else {
                log.warn("로컬 재생성 한도({})를 다 썼다 — motionId={} 그 밤은 실패, 다음 밤에 다시", localRegenMax, motionId);
            }
        } catch (RuntimeException | Error e) {
            log.error("재생성 요청 기록 실패 — motionId={} (기동 복구가 회수합니다)", motionId, e);
        }
    }

    /**
     * 실패 기록마저 실패해도 이 스레드는 조용히 끝난다 — 여기서 예외가 또 나면 그 행은 다시 BAKING 고착이다.
     * 그 경우는 {@code StuckMotionRecovery} 가 유예 뒤 회수한다.
     */
    private void markFailedQuietly(Long motionId) {
        try {
            motionRecorder.markFailed(motionId);
        } catch (RuntimeException | Error e) {
            log.error("모션 실패 기록마저 실패 — motionId={} (기동 복구가 회수합니다)", motionId, e);
        }
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
            // 게이트가 실패라 하면 API 몫은 여기서 끝이다(정본 6장 — 다시 만드는 것은 맥미니).
            // 판정은 기록에 남겨 기계 판정과 사람 판정을 비교할 수 있게 한다.
            motionRecorder.recordGate(motionId, imageKey, v);

            // ★★ 성공 기록을 지운다. 재시도(설정으로 2 이상을 줬을 때)는 성공한 단계를 건너뛰는데,
            //   격자도 후처리도 "성공" 으로 남아 있으면 실행기가 둘 다 건너뛰고
            //   <b>방금 퇴짜 맞은 그 그림을 또 판정한다.</b>
            int discarded = recorder.discardMotionSteps(motionId);
            log.info("게이트 실패 — 성공 기록 {}건을 지운다 (motionId={})", discarded, motionId);
            return false;
        }

        // ★★ 검수 대기까지가 서버 몫이다. 사용자 화면은 상훈님이 OK 를 누르고, 그다음
        //   펫이 깨어 있는 첫 정산에 도착한다(정본 2장 "기상 첫 화면").
        motionRecorder.toReview(motionId, imageKey, v);
        log.info("모션 구움 — motionId={} 동작={} 게이트={} 비용=${} (검수 대기)",
                motionId, motion.getName(), v.verdict(), r.costUsd());
        return true;
    }

    /** 도감에 보일 것들. 굽는 중이거나 실패한 것은 안 보인다. */
    public List<ZzalMotion> opened(Long petId) {
        return motionRepository.findByPetIdAndStatusOrderBySeqAsc(petId, MotionStatus.OPEN);
    }
}
