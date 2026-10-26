package com.lore.webtoon.runs;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PageRegenRepository extends JpaRepository<PageRegen, String> {

    /** 이 작품에서 아직 안 끝난 그리기 — 남은 장 이어 그리기가 같은 장을 두 번 받지 않게 본다. */
    java.util.List<PageRegen> findByRunIdAndStatusInAndCreatedAtAfter(String runId,
                                                                      java.util.Collection<String> statuses,
                                                                      java.time.Instant after);
}
