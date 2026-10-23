package com.lore.webtoon.lorebook;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
public interface LoreExtractionRepository extends JpaRepository<LoreExtraction,String> {
 @Modifying @Query(value="insert into webtoon_lore_quota(owner_id,day,used) values(:owner,:day,1) on conflict(owner_id,day) do update set used=webtoon_lore_quota.used+1 where webtoon_lore_quota.used < :limit",nativeQuery=true)
 int reserve(@Param("owner") Long owner,@Param("day") LocalDate day,@Param("limit") int limit);
 @Query(value="select coalesce(sum(used),0) from webtoon_lore_quota where owner_id=:owner and day=:day",nativeQuery=true)
 int used(@Param("owner") Long owner,@Param("day") LocalDate day);
 @Transactional @Modifying @Query("update LoreExtraction e set e.status=:status,e.resultJson=:result,e.error=:error where e.id=:id and e.status='RUNNING'")
 int finish(@Param("id") String id,@Param("status") String status,@Param("result") String result,@Param("error") String error);
}
