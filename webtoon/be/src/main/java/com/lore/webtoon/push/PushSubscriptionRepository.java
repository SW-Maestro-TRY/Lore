package com.lore.webtoon.push;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {

    Optional<PushSubscription> findByEndpoint(String endpoint);

    List<PushSubscription> findByUserId(Long userId);

    /** 게스트 작업의 받는 기기. 계정에 묶인 기기도 같은 브라우저면 받는다 — 로그인 전에 만들던 작업이다. */
    List<PushSubscription> findByBrowserUid(String browserUid);

    @Modifying
    @Transactional
    @Query("delete from PushSubscription s where s.endpoint = :endpoint")
    int deleteByEndpoint(@Param("endpoint") String endpoint);

    /** 이때보다 오래 안 쓰인 구독을 지운다({@link PushSweep}). */
    @Modifying
    @Transactional
    @Query("delete from PushSubscription s where s.usedAt < :cut")
    int deleteIdle(@Param("cut") Instant cut);

    @Modifying
    @Transactional
    @Query("update PushSubscription s set s.usedAt = :at where s.id = :id")
    int touch(@Param("id") Long id, @Param("at") Instant at);
}
