package com.lore.webtoon.runs;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BakedPageRepository extends JpaRepository<BakedPage, Long> {

    Optional<BakedPage> findByRunIdAndPageNoAndWidth(String runId, int pageNo, int width);

    /** 이 작품에서 구운 것 전부. 장 번호 · 폭 순서로 준다. */
    List<BakedPage> findByRunIdOrderByPageNoAscWidthAsc(String runId);
}
