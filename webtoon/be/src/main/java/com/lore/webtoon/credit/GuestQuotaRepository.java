package com.lore.webtoon.credit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface GuestQuotaRepository extends JpaRepository<GuestQuota, Long> {

    Optional<GuestQuota> findByIpHashAndDay(String ipHash, LocalDate day);

    /**
     * 한도 안이면 한 번 썼다고 적는다 — <b>한 문장으로</b>(#623).
     *
     * 읽고 → 더하고 → 저장하면, 같은 사람의 두 요청이 동시에 오면 그날 줄을 둘 다 새로 넣으려다 하나가
     * 유일 제약에 걸리거나(500), 둘 다 같은 값을 읽고 한 번만 센다(한도를 넘겨 통과). 그래서 넣기와
     * 더하기와 한도 검사를 DB 가 한 번에 한다.
     *
     * @return 적었으면 1, 한도라서 못 적었으면 0
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            insert into webtoon_guest_quota (ip_hash, day, used) values (:ipHash, :day, 1)
            on conflict (ip_hash, day) do update set used = webtoon_guest_quota.used + 1
            where webtoon_guest_quota.used < :limit
            """, nativeQuery = true)
    int useIfUnder(@Param("ipHash") String ipHash, @Param("day") LocalDate day, @Param("limit") long limit);

    /** 한 번 도로 물린다. 0 밑으로는 안 내려간다. @return 물렸으면 1 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update webtoon_guest_quota set used = used - 1
            where ip_hash = :ipHash and day = :day and used > 0
            """, nativeQuery = true)
    int giveBackOne(@Param("ipHash") String ipHash, @Param("day") LocalDate day);
}
