package com.lore.webtoon.story;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WebtoonStoryRepository extends JpaRepository<WebtoonStory, Long> {

    List<WebtoonStory> findByRunIdOrderByNAsc(String runId);

    Optional<WebtoonStory> findByRunIdAndChosenTrue(String runId);

    Optional<WebtoonStory> findByRunIdAndN(String runId, int n);

    boolean existsByRunId(String runId);
}
