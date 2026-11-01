package com.lore.webtoon.runs;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaintRepository extends JpaRepository<WebtoonPaint, Long> {

    Optional<WebtoonPaint> findByRunIdAndEpisodeAndPageNo(String runId, int episode, int pageNo);

    List<WebtoonPaint> findByRunIdAndEpisode(String runId, int episode);
}
