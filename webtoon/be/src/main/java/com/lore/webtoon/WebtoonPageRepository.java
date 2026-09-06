package com.lore.webtoon;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WebtoonPageRepository extends JpaRepository<WebtoonPage, Long> {

    Optional<WebtoonPage> findByRunIdAndPageNoAndWidth(String runId, int pageNo, int width);

    /** 이 작품의 장 전부. 장 번호 · 폭 순서로 준다. */
    List<WebtoonPage> findByRunIdOrderByPageNoAscWidthAsc(String runId);

    boolean existsByRunId(String runId);
}
