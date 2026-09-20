package com.lore.webtoon.runs;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OverlayRepository extends JpaRepository<WebtoonOverlay, Long> {

    Optional<WebtoonOverlay> findByRunIdAndEpisode(String runId, int episode);
}
