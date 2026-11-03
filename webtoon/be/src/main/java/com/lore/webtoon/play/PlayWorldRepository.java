package com.lore.webtoon.play;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlayWorldRepository extends JpaRepository<PlayWorld, String> {

    List<PlayWorld> findAllByOwnerIdOrderByUpdatedAtDesc(Long ownerId);

    List<PlayWorld> findAllByStatusOrderByPublishedAtDesc(String status);

    Optional<PlayWorld> findByIdAndOwnerId(String id, Long ownerId);

    /** 오늘 몫을 하나 쓴다. 다 썼으면 0 (설정 정리 webtoon_lore_quota 와 같은 방식). */
    @Modifying
    @Query(value = "insert into webtoon_play_world_quota(owner_id,day,used) values(:owner,:day,1) "
            + "on conflict(owner_id,day) do update set used=webtoon_play_world_quota.used+1 "
            + "where webtoon_play_world_quota.used < :limit", nativeQuery = true)
    int reserve(@Param("owner") Long owner, @Param("day") LocalDate day, @Param("limit") int limit);

    @Query(value = "select coalesce(sum(used),0) from webtoon_play_world_quota where owner_id=:owner and day=:day",
            nativeQuery = true)
    int used(@Param("owner") Long owner, @Param("day") LocalDate day);
}
