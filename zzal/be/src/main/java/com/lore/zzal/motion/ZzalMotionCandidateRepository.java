package com.lore.zzal.motion;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ZzalMotionCandidateRepository extends JpaRepository<ZzalMotionCandidate, Long> {

    /** 판정 화면이 보는 순서 — 라운드 순, 같은 라운드면 만들어진 순. */
    List<ZzalMotionCandidate> findByMotionIdOrderByRoundAscIdAsc(Long motionId);

    List<ZzalMotionCandidate> findByMotionIdInOrderByRoundAscIdAsc(List<Long> motionIds);

    Optional<ZzalMotionCandidate> findByMotionIdAndChosenTrue(Long motionId);
}
