package com.lore.webtoon;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface UsageRepository extends JpaRepository<UsageRecord, Long> {

    boolean existsByRunIdAndSeq(String runId, int seq);

    /** 이 작품에 지금까지 몇 번째까지 올라와 있나. 올리는 쪽이 이어서 보낼 자리를 찾는다. */
    @Query("select coalesce(max(u.seq), -1) from UsageRecord u where u.runId = :runId")
    int lastSeqOf(@Param("runId") String runId);

    /** 이 사이에 나간 돈(원). 하나도 없으면 0. */
    @Query("select coalesce(sum(u.costKrw), 0) from UsageRecord u "
            + "where u.calledAt >= :from and u.calledAt < :to")
    long krwBetween(@Param("from") Instant from, @Param("to") Instant to);

    /** 이 사이에 만든 작품 수. 상한을 "몇 편" 으로 세는 자리다. */
    @Query("select count(distinct u.runId) from UsageRecord u "
            + "where u.calledAt >= :from and u.calledAt < :to")
    long runsBetween(@Param("from") Instant from, @Param("to") Instant to);

    /** 무엇에 얼마나 썼는지 — [단계, 모델, 원, 건수]. 그림이 대부분이라 그것부터 보인다. */
    @Query("select u.stage, u.model, sum(u.costKrw), count(u) from UsageRecord u "
            + "where u.calledAt >= :from and u.calledAt < :to "
            + "group by u.stage, u.model order by sum(u.costKrw) desc")
    List<Object[]> breakdownBetween(@Param("from") Instant from, @Param("to") Instant to);
}
