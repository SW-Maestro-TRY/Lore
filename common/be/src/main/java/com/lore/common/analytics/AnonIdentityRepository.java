package com.lore.common.analytics;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * "이 브라우저는 나중에 이 사람이 되었다" 저장소.
 *
 * ★ 이어 붙이는 일은 한 번만 일어나야 한다. 유니크 제약(uk_anon_identity)이 최종 방어이고,
 *   {@link #existsByAnonIdAndUserId} 는 평상시에 그 제약을 건드리지 않으려는 앞단 확인이다.
 *   제약에 걸려 예외가 나는 것 자체는 정상 동작이지만(동시 요청), 매번 예외로 처리하면
 *   트랜잭션이 깨져 같은 배치의 이벤트 저장까지 함께 말려 들어간다.
 */
public interface AnonIdentityRepository extends JpaRepository<AnonIdentity, Long> {

    boolean existsByAnonIdAndUserId(String anonId, Long userId);
}
