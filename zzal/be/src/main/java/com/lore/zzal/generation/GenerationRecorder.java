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

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<GenStepRecord> loadSucceeded(Long jobId) {
        return stepRepository.findByJobIdOrderBySeqAsc(jobId).stream()
                .filter(GenStepRecord::isSucceeded)
                .toList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markJobRunning(Long jobId) {
        jobRepository.findById(jobId).ifPresent(GenJob::markRunning);
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
                .ifPresent(s -> s.succeed(r.imageKey(), r.text(), r.costUsd(), Instant.now()));
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPetFailed(Long petId) {
        petRepository.findById(petId).ifPresent(p -> p.markHatchFailed());
    }
}
