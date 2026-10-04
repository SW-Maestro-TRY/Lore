package com.lore.webtoon.event;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WebtoonEventRepository extends JpaRepository<WebtoonEvent, Long> {

    /** 보관 기간이 지난 줄. 받은 시각으로 잰다 — 화면 시각은 브라우저 시계라 믿을 수 없다. */
    @Modifying
    @Query("delete from WebtoonEvent e where e.receivedAt < :cut")
    int deleteReceivedBefore(@Param("cut") Instant cut);

    /** 탈퇴한 사람의 줄. */
    @Modifying
    @Query("delete from WebtoonEvent e where e.userId = :userId")
    int deleteByUser(@Param("userId") Long userId);
}
