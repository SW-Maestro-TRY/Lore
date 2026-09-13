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

    /** 지금 줄에 있는 것의 수 — 일꾼을 잡고 있거나 잡으러 갈 것들. */
    long countByStatusIn(java.util.Collection<JobStatus> statuses);

    /**
     * 줄을 <b>선 순서대로</b>. 만든 때가 곧 줄 순서다.
     *
     * 사람이 시트 앞에서 멈췄다 돌아와도 만든 때는 안 바뀌므로 원래 자리로
     * 돌아간다 — 나중에 온 사람에게 자리를 뺏기지 않는다.
     */
    List<WebtoonJob> findByStatusInOrderByCreatedAtAsc(java.util.Collection<JobStatus> statuses);
}
