package com.lore.zzal.generation;

import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalPetRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 2층 상태를 펫 행에 적는 곳(#696). {@link GenerationRecorder} 와 같은 이유로 <b>별도 빈 + 단계마다 커밋</b>이다 —
 * 굽기는 트랜잭션 밖(실행기 스레드)에서 돌고, 같은 클래스 안의 자기 호출은 {@code @Transactional} 을 안 탄다.
 *
 * ★ 집기({@link #claim})는 행을 잠근다 — 부화 완료 훅과 관리자 재시도가 겹쳐도 한쪽만 굽는다.
 */
@Component
public class Layer2Recorder {

    private final ZzalPetRepository petRepository;

    public Layer2Recorder(ZzalPetRepository petRepository) {
        this.petRepository = petRepository;
    }

    /** RUNNING 으로 집는다. 이미 굽는 중이거나 READY 면 false. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(Long petId, Instant now) {
        return petRepository.findByIdForUpdate(petId)
                .filter(ZzalPet::isAlive)
                .map(p -> p.startLayer2Attempt(now))
                .orElse(false);
    }

    /** 지금 몇 번째 시도인가. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public int attempts(Long petId) {
        return petRepository.findById(petId).map(ZzalPet::getLayer2Attempts).orElse(0);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void next(Long petId, String reason, Instant now) {
        petRepository.findByIdForUpdate(petId).ifPresent(p -> p.nextLayer2Attempt(reason, now));
    }

    /**
     * 다 구웠다 — <b>판을 올리고 READY 로 바꾸는 것을 한 커밋에서</b> 한다.
     * 판만 먼저 올라가면 2층이 안 열린 채 새 주소를 받는 틈이 생긴다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ready(Long petId, int round, Instant now) {
        petRepository.findByIdForUpdate(petId).ifPresent(p -> {
            p.markBasicBaked(round);
            p.markLayer2Ready(now);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(Long petId, String reason, Instant now) {
        petRepository.findByIdForUpdate(petId).ifPresent(p -> p.markLayer2Failed(reason, now));
    }

    /** 처음부터 다시(시도 수 0) — 관리자 재시도·1층 교체. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reset(Long petId, Instant now) {
        petRepository.findByIdForUpdate(petId).ifPresent(p -> p.resetLayer2(now));
    }
}
