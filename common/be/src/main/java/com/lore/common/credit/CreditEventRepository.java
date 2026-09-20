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

    boolean existsByUserIdAndReasonAndDomainAndRefId(
            Long userId, CreditReason reason, CreditDomain domain, String refId);

    /**
     * 이 사람이 <b>이 일로</b> 적은 줄. 환원이 낸 줄을 찾을 때 쓴다.
     *
     * 전에는 최근 내역 몇 줄을 받아다 그 안에서 골랐는데, 그러면 낸 뒤로
     * 줄이 그만큼 쌓인 사람은 낸 기록이 목록 밖으로 밀려나 <b>환원이 조용히
     * 0 이 된다.</b> 돌려받아야 할 사람이 아무 말도 못 듣고 못 돌려받는다.
     * 찾는 것을 DB 에 직접 묻는다.
     */
    List<CreditEvent> findByUserIdAndReasonAndDomainAndRefId(
            Long userId, CreditReason reason, CreditDomain domain, String refId);

    /** 최근 것부터. id 로 내림차순이면 같은 순간에 적힌 것도 순서가 흔들리지 않는다. */
    @Query("select e from CreditEvent e where e.userId = :userId order by e.id desc")
    List<CreditEvent> historyOf(@Param("userId") Long userId, Pageable page);
}
