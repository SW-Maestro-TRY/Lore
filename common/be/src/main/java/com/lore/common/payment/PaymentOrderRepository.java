package com.lore.common.payment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, Long> {

    Optional<PaymentOrder> findByOrderNo(String orderNo);

    /**
     * 통보를 처리할 때 쓴다 — <b>그 주문 줄을 잠그고</b> 읽는다.
     *
     * 페이앱은 같은 통보를 여러 번 보낼 수 있고(재시도), 결제완료와 취소가 거의
     * 동시에 올 수도 있다. 잠그지 않으면 둘이 같은 옛 상태를 읽고 각자 판단한다.
     * 크레딧 지급 자체는 장부 유일키가 두 번 막아 주지만, 주문 상태가 엇갈리는 것은
     * 이 잠금이 막는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PaymentOrder o where o.orderNo = :orderNo")
    Optional<PaymentOrder> lockByOrderNo(@Param("orderNo") String orderNo);
}
