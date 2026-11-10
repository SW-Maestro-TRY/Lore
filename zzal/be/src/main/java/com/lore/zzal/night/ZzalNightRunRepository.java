package com.lore.zzal.night;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface ZzalNightRunRepository extends JpaRepository<ZzalNightRun, ZzalNightRun.Key> {

    /**
     * 그 구간에 시작한 판들이 집은 수의 합 — 굽기 상한 K 를 <b>KST 하루</b>(10:00~다음 날 10:00)로 나눠 쓰기 위해.
     * 시간대가 여럿이면 판도 여럿이라, 판마다 K 를 주면 하루 비용 상한이 시간대 수만큼 불어난다.
     */
    @Query("select coalesce(sum(r.claimed), 0) from ZzalNightRun r where r.startedAt >= :from and r.startedAt < :to")
    long sumClaimedStartedBetween(@Param("from") Instant from, @Param("to") Instant to);
}
