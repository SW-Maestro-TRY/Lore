package com.lore.zzal.generation;

import com.lore.zzal.pet.ZzalPetRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 생성 진행을 DB 에 남기는 곳.
 *
 * ★★ 별도 클래스인 이유가 둘이다.
 *
 *   1) 스프링은 프록시라는 대역을 통해 트랜잭션을 걸어 준다. 같은 클래스 안에서 자기
 *      메서드를 부르면 대역을 거치지 않아 **@Transactional 이 통째로 무시된다.**
 *      (2026-09-02 실제로 이 상태였다 — 로그에는 "부화 완료" 가 찍히는데 DB 는 그대로였다)
 *
 *   2) REQUIRES_NEW 로 **단계마다 따로 커밋**해야 한다. 부화 전체를 한 트랜잭션으로 묶으면
 *      다 끝날 때까지 아무것도 저장되지 않아, 그동안 화면이 진행 상황을 읽을 수 없다.
 */
@Component
public class GenerationRecorder {

    private final GenJobRepository jobRepository;
    private final GenStepRecordRepository stepRepository;
    private final ZzalPetRepository petRepository;

    public GenerationRecorder(GenJobRepository jobRepository,
                              GenStepRecordRepository stepRepository,
                              ZzalPetRepository petRepository) {
        this.jobRepository = jobRepository;
        this.stepRepository = stepRepository;
        this.petRepository = petRepository;
    }

    /**
     * 이 펫이 지금까지 성공시킨 단계들. **시도(job)가 아니라 펫 단위로 본다.**
     * 재시도가 앞 단계를 다시 굽지 않게 하는 핵심이다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<GenStepRecord> loadSucceeded(Long petId, GenKind kind) {
        return stepRepository.findSucceededByPet(petId, kind);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markJobRunning(Long jobId) {
        jobRepository.findById(jobId).ifPresent(j -> j.markRunning(Instant.now()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long startStep(Long jobId, int seq, String name) {
        return stepRepository.findByJobIdAndName(jobId, name)
                .map(GenStepRecord::getId)
                .orElseGet(() -> stepRepository
                        .save(GenStepRecord.start(jobId, seq, name, Instant.now()))
                        .getId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeedStep(Long stepId, StepResult r) {
        stepRepository.findById(stepId)
                .ifPresent(s -> s.succeed(r.imageKey(), r.text(), r.model(), r.costUsd(), Instant.now()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failStep(Long stepId, GenErrorCode code) {
        stepRepository.findById(stepId)
                .ifPresent(s -> s.fail(code, BigDecimal.ZERO, Instant.now()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeedJob(Long jobId, Long petId, String sheetKey, String identityText,
                           BigDecimal total, Instant now) {
        jobRepository.findById(jobId).ifPresent(j -> j.succeed(total, now));
        petRepository.findById(petId).ifPresent(p -> p.markAlive(sheetKey, identityText, now));
    }

    /** 이 시도만 실패로 남긴다. 펫을 FAILED 로 만들지는 부르는 쪽이 정한다(재시도가 남았을 수 있다). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failJob(Long jobId, GenErrorCode code, BigDecimal total) {
        jobRepository.findById(jobId).ifPresent(j -> j.fail(code, total, Instant.now()));
    }


    /**
     * 거부(moderation)로 실패했을 때, 원인이 된 단계의 성공 기록을 지운다.
     *
     * ★★ 왜 필요한가 — 거부는 **입력 자체가 막힌 것**이라 같은 걸 다시 보내면 또 막힌다.
     *   그런데 우리 재시도는 "성공한 단계는 건너뛴다". 그래서 문단이 원인인데 문단을
     *   건너뛰면 **똑같은 문단으로 격자를 또 시도하고 또 거부당한다** — 시간만 쓰고
     *   결과는 같다("재시도 3번 하고 실패" 라는 최악).
     *
     *   2026-08-26 실측에서 실제로 있었다. 고양이 시트를 보고 엉뚱한 캐릭터를 묘사한
     *   문단이 나왔고 그 때문에 격자가 차단됐다. 문단을 새로 만들어야 풀린다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int discardSucceeded(Long petId, GenKind kind, String stepName) {
        List<GenStepRecord> targets = stepRepository.findSucceededByPet(petId, kind).stream()
                .filter(s -> s.getName().equals(stepName))
                .toList();
        targets.forEach(stepRepository::delete);
        return targets.size();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPetFailed(Long petId) {
        petRepository.findById(petId).ifPresent(p -> p.markHatchFailed());
    }
}
