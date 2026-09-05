package com.lore.common.credit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CreditEventRepository extends JpaRepository<CreditEvent, Long> {

    /** 지금 잔액 = 움직인 것 전부의 합. 한 줄도 없으면 0 이다. */
    @Query("select coalesce(sum(e.delta), 0) from CreditEvent e where e.userId = :userId")
    int balanceOf(@Param("userId") Long userId);

    boolean existsByUserIdAndReasonAndRefId(Long userId, CreditReason reason, String refId);

    /** 최근 것부터. id 로 내림차순이면 같은 순간에 적힌 것도 순서가 흔들리지 않는다. */
    @Query("select e from CreditEvent e where e.userId = :userId order by e.id desc")
    List<CreditEvent> historyOf(@Param("userId") Long userId, Pageable page);
}
