package com.lore.zzal.motion;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ZzalMotionRepository extends JpaRepository<ZzalMotion, Long> {

    List<ZzalMotion> findByPetIdOrderBySeqAsc(Long petId);

    /** 도감에 보일 것들 — 다 구워져 열린 것만. 실패·굽는 중은 사용자에게 안 보인다. */
    List<ZzalMotion> findByPetIdAndStatusOrderBySeqAsc(Long petId, MotionStatus status);

    Optional<ZzalMotion> findFirstByPetIdAndStatus(Long petId, MotionStatus status);

    /** 그 펫의 몇 번째 자리. 재우기·깨우기가 이걸로 "지금 굽는 것" 을 찾는다. */
    Optional<ZzalMotion> findByPetIdAndSeq(Long petId, int seq);

    long countByPetIdAndStatus(Long petId, MotionStatus status);

    /** 아직 상훈님이 안 보신 것들. 관리자 화면이 이걸 쓴다. */
    List<ZzalMotion> findByHumanVerdictIsNullOrderByIdAsc();
}
