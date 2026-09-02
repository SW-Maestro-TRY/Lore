package com.lore.zzal.generation;

import com.lore.zzal.pet.ZzalPetRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 부화 진행 상황을 **단계마다 따로 저장**하는 곳.
 *
 * ★★ 왜 별도 클래스인가 — 스프링은 프록시라는 대역을 통해 트랜잭션을 걸어 준다.
 *   같은 클래스 안에서 자기 메서드를 부르면 대역을 거치지 않아 **@Transactional 이 통째로 무시된다.**
 *   그러면 트랜잭션 없이 실행되어 엔티티를 고쳐도 DB 에 저장되지 않는다.
 *
 *   2026-09-02 에 실제로 이 상태였다 — 로그에는 "부화 완료" 가 찍히는데 DB 는 QUEUED 그대로였다.
 *   (같은 날 아침 RefreshTokenService 에서도 같은 함정에 걸렸다. 스프링을 쓰면 반복해서 만난다)
 *
 * ★ REQUIRES_NEW = 단계마다 따로 커밋한다. 부화 전체를 한 트랜잭션으로 묶으면
 *   다 끝날 때까지 아무것도 저장되지 않아, 그동안 화면이 "지금 어느 단계인지" 를 읽을 수 없다.
 */
@Component
public class HatchProgress {

    private final ZzalPetRepository petRepository;
    private final GenJobRepository jobRepository;

    public HatchProgress(ZzalPetRepository petRepository, GenJobRepository jobRepository) {
        this.petRepository = petRepository;
        this.jobRepository = jobRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void moveTo(Long jobId, GenStep step) {
        jobRepository.findById(jobId).ifPresent(j -> j.moveTo(step));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeed(Long petId, Long jobId, String sheetImageKey, String identityText,
                        BigDecimal costUsd, Instant now) {
        jobRepository.findById(jobId).ifPresent(j -> j.succeed(costUsd, now));
        petRepository.findById(petId).ifPresent(p -> p.markAlive(sheetImageKey, identityText, now));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Long petId, Long jobId, GenErrorCode errorCode, BigDecimal costUsd, Instant now) {
        jobRepository.findById(jobId).ifPresent(j -> j.fail(errorCode, costUsd, now));
        petRepository.findById(petId).ifPresent(pet -> pet.markHatchFailed());
    }
}
