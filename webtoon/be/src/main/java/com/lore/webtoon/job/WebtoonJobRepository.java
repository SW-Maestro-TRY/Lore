package com.lore.webtoon.job;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WebtoonJobRepository extends JpaRepository<WebtoonJob, Long> {

    Optional<WebtoonJob> findByPublicId(String publicId);

    /** 차례를 기다리는 것들. 먼저 온 것부터. */
    List<WebtoonJob> findByStatusOrderByIdAsc(JobStatus status);

    /** 지금 돌고 있는 것이 있나. 한 번에 하나씩만 돌리려고 본다. */
    boolean existsByStatus(JobStatus status);
}
