package com.lore.zzal.motion;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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

    /** 굽다 만 채로 오래 남은 것들. 서버가 뜰 때 이어서 굽는다. */
    List<ZzalMotion> findByStatusAndUpdatedAtBefore(MotionStatus status, java.time.Instant before);

    /** 아직 상훈님이 안 보신 것들. 관리자 화면이 이걸 쓴다. */
    List<ZzalMotion> findByHumanVerdictIsNullOrderByIdAsc();

    /** 밤 큐 전체(상태별). 이월분(지난 밤 nightOf)도 포함된다. */
    List<ZzalMotion> findByStatusOrderByIdAsc(MotionStatus status);

    List<ZzalMotion> findByPetIdAndStatus(Long petId, MotionStatus status);

    /**
     * ★ 큐에서 하나를 <b>집는다</b> — 서버 여러 대가 같은 밤을 돌아도 한 건은 한 번만 굽는다.
     *
     * {@code WHERE status = 'QUEUED'} 가 잠금 역할을 한다: 두 서버가 같은 행을 UPDATE 하면 DB 가 직렬화해
     * 먼저 온 쪽만 1 을 받고 뒤는 0 을 받는다(이미 BAKING). 0 이면 굽지 않는다. 별도 SELECT FOR UPDATE 가 필요 없다.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update ZzalMotion m set m.status = com.lore.zzal.motion.MotionStatus.BAKING, m.claimedAt = :now, m.claimedBy = :by "
            + "where m.id = :id and m.status = com.lore.zzal.motion.MotionStatus.QUEUED")
    int claim(@Param("id") Long id, @Param("now") java.time.Instant now, @Param("by") String by);
}
