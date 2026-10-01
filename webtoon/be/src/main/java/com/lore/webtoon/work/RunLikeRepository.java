package com.lore.webtoon.work;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RunLikeRepository extends JpaRepository<RunLike, Long> {

    Optional<RunLike> findByRunIdAndUserId(String runId, Long userId);

    long countByRunId(String runId);

    /** 이 사람이 찜한 작품 번호들 — 최근에 찜한 것부터. */
    @Query("select l.runId from RunLike l where l.userId = :userId order by l.createdAt desc")
    List<String> runIdsLikedBy(@Param("userId") Long userId);

    /** 이 사람이 찜한 것 중 이 목록에 있는 것만 — 둘러보기 카드에 「내가 찜했나」를 붙일 때. */
    @Query("select l.runId from RunLike l where l.userId = :userId and l.runId in :runIds")
    List<String> likedAmong(@Param("userId") Long userId, @Param("runIds") List<String> runIds);

    @Modifying
    @Query("delete from RunLike l where l.runId in :runIds")
    int deleteByRunIds(@Param("runIds") List<String> runIds);

    @Modifying
    @Query("delete from RunLike l where l.userId = :userId")
    int deleteByUser(@Param("userId") Long userId);
}
