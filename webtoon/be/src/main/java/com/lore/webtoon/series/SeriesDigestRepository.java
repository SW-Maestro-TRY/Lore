package com.lore.webtoon.series;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SeriesDigestRepository extends JpaRepository<SeriesDigest, String> {
    @Transactional @Modifying
    @Query("update SeriesDigest d set d.status=:status, d.resultJson=:result, d.error=:error, d.updatedAt=CURRENT_TIMESTAMP "
         + "where d.sourceJob=:id and d.status='RUNNING'")
    int finish(@Param("id") String id, @Param("status") String status,
               @Param("result") String result, @Param("error") String error);
}
