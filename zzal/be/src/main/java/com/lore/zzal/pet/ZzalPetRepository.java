package com.lore.zzal.pet;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ZzalPetRepository extends JpaRepository<ZzalPet, Long> {

    List<ZzalPet> findByUserIdOrderByIdDesc(Long userId);

    /** 지금 부화 중인 펫이 있는가. 있으면 새로 만들지 못한다("○○이가 부화 중이에요"). */
    Optional<ZzalPet> findFirstByUserIdAndPhase(Long userId, PetPhase phase);

    /**
     * 지금 <b>자리를 차지하고 있는</b> 펫 수 — 칸 수(user.petSlots)와 비교하는 자리.
     *
     * ★★ "FAILED 만 빼고 센다" 가 아니라 <b>세고 싶은 단계를 직접 준다</b>. 자리를 차지하는 것은
     *    HATCHING(굽는 중이라 결과를 받아야 함)과 ALIVE(함께 지내는 중) 뿐이다.
     * <p>
     * 전에는 {@code countByUserIdAndPhaseNot(userId, FAILED)} 였는데, 그러면 <b>DEAD 까지 세어</b>
     * 펫을 보내도(놓아주기) 자리가 안 비고 새로 시작할 수 없었다. 부정형("무엇이 아닌 것")으로
     * 세면 단계가 하나 늘 때마다 그 값이 조용히 자리를 먹는다 — 예외도 로그도 없이
     * "자리 없음" 만 뜬다. 그래서 긍정형(무엇을 세는가)으로 뒤집었다.
     * <p>
     * 떠난 아이(DEAD)와 태어나지 못한 알(FAILED)의 행은 지우지 않고 그대로 남긴다.
     * 이미 돈을 써서 구운 결과물이고, 재회 기능이 붙을 자리이기 때문이다.
     */
    long countByUserIdAndPhaseIn(Long userId, Collection<PetPhase> phases);

    /**
     * 오래 멈춰 있는 알. 서버가 재시작되면 메모리에서 돌던 부화가 사라지므로,
     * 다시 뜰 때 이걸로 찾아 이어서 굽는다(StuckHatchRecovery).
     */
    List<ZzalPet> findByPhaseAndHatchStartedAtBefore(PetPhase phase, java.time.Instant before);
}
