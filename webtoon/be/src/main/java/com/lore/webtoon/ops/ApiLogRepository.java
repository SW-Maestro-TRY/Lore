package com.lore.webtoon.ops;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApiLogRepository extends JpaRepository<ApiLog, Long> {

    @Modifying
    @Query("delete from ApiLog a where a.at < :cut")
    int deleteBefore(@Param("cut") Instant cut);
}
