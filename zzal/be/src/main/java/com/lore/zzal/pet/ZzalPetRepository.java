package com.lore.zzal.pet;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ZzalPetRepository extends JpaRepository<ZzalPet, Long> {

    List<ZzalPet> findByUserIdOrderByIdDesc(Long userId);

    /** 지금 부화 중인 펫이 있는가. 있으면 새로 만들지 못한다("○○이가 부화 중이에요"). */
    Optional<ZzalPet> findFirstByUserIdAndPhase(Long userId, PetPhase phase);

    /** 몇 마리 키우는가 — 칸 수(user.petSlots)와 비교하는 자리. 실패한 알은 세지 않는다. */
    long countByUserIdAndPhaseNot(Long userId, PetPhase phase);

    /**
     * 오래 멈춰 있는 알. 서버가 재시작되면 메모리에서 돌던 부화가 사라지므로,
     * 다시 뜰 때 이걸로 찾아 이어서 굽는다(StuckHatchRecovery).
     */
    List<ZzalPet> findByPhaseAndHatchStartedAtBefore(PetPhase phase, java.time.Instant before);
}
