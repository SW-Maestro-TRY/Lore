package com.lore.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * 엔티티의 생성/수정 시각을 자동으로 채워주는 기능을 켠다.
 *
 * 이 설정이 있어야 엔티티의 @CreatedDate / @LastModifiedDate 가 동작한다.
 * (엔티티 쪽에는 @EntityListeners(AuditingEntityListener.class) 가 함께 필요하다)
 *
 * 세 도메인이 모두 쓰는 기능이라 common 에 둔다.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
