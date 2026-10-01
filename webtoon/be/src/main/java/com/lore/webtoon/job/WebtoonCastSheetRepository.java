package com.lore.webtoon.job;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebtoonCastSheetRepository extends JpaRepository<WebtoonCastSheet, Long> {

    List<WebtoonCastSheet> findByJobIdOrderByCreatedAtAsc(String jobId);

    boolean existsByJobIdAndName(String jobId, String name);
}
