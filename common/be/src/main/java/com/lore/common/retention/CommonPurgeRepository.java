package com.lore.common.retention;

import com.lore.common.credit.CreditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/** 공용 표에서 지우거나 비우는 문들. 파기 전용이다. */
public interface CommonPurgeRepository extends JpaRepository<CreditEvent, Long> {

    /* ---- 탈퇴한 사람 ------------------------------------------------- */

    /** 올린 사진의 S3 키. <b>행을 지우기 전에</b> 먼저 모아야 한다. */
    @Query("select t.s3Key from UploadTicket t where t.userId = :userId")
    List<String> uploadKeysOf(@Param("userId") Long userId);

    @Modifying
    @Query("delete from UploadTicket t where t.userId = :userId")
    int deleteUploadTickets(@Param("userId") Long userId);

    @Modifying
    @Query("delete from CreditEvent e where e.userId = :userId")
    int deleteCreditEvents(@Param("userId") Long userId);

    /* ---- 기간이 지난 것 ---------------------------------------------- */

    /**
     * 오래된 크레딧 기록에서 <b>사람이 적은 말만</b> 지운다.
     *
     * ★ 행을 못 지우는 이유는 {@link RetentionPolicy} 에 적었다 — 잔액이
     *   합산이라 지우면 틀어지고, 중복 지급 방지도 같이 깨진다.
     *
     * ★ {@code memo is not null} 을 붙인다. 안 붙이면 이미 비운 행까지 매번
     *   다시 쓰게 되어, 할 일이 없는 날에도 표 전체를 건드린다.
     */
    @Modifying
    @Query("update CreditEvent e set e.memo = null where e.createdAt < :cut and e.memo is not null")
    int clearOldCreditMemos(@Param("cut") Instant cut);
}
