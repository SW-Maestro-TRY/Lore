package com.lore.trailer.credit;

import com.lore.common.credit.CreditEvent;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/**
 * Trailer의 접수 트랜잭션에서만 쓰는 잠금과 크레딧 기록.
 * 공통 Repository를 상속하지 않아 기존 CreditEventRepository 주입에 후보가 추가되지 않는다.
 * CreditEvent의 생성자는 공통 패키지 전용이므로, 접근 범위를 바꾸지 않고 같은 표에 기록한다.
 */
public interface TrailerCreditRepository extends Repository<CreditEvent, Long> {

    @Query(value = "select id from users where id = :userId for no key update", nativeQuery = true)
    Long lockAccount(@Param("userId") Long userId);

    @Modifying
    @Query(value = """
            insert into credit_event (user_id, delta, reason, domain, ref_id, memo, created_at)
            values (:userId, :delta, :reason, 'TRAILER', :refId, :memo, :createdAt)
            """, nativeQuery = true)
    int append(@Param("userId") Long userId, @Param("delta") int delta,
               @Param("reason") String reason, @Param("refId") String refId,
               @Param("memo") String memo, @Param("createdAt") Instant createdAt);
}
