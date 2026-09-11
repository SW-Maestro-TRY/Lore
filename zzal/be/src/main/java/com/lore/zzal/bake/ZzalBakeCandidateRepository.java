package com.lore.zzal.bake;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ZzalBakeCandidateRepository extends JpaRepository<ZzalBakeCandidate, Long> {

    List<ZzalBakeCandidate> findByBakeIdOrderByRoundAscIdAsc(Long bakeId);
}
