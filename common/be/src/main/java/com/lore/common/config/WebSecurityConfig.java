package com.lore.common.config;

/**
 * 인증/인가 및 CORS 설정 자리.
 *
 * 계정은 3개 도메인이 공유하므로 보안 설정도 common 한 곳에서만 관리한다.
 *
 * spring-security 의존성이 아직 없어서 애노테이션은 주석으로만 남겨둠.
 * 의존성 추가 후 백엔드 담당자가 아래 형태로 채우면 된다.
 *
 * <pre>
 * &#64;Configuration
 * &#64;EnableWebSecurity
 * public class WebSecurityConfig {
 *     &#64;Bean
 *     public SecurityFilterChain filterChain(HttpSecurity http) { ... }
 * }
 * </pre>
 */
public class WebSecurityConfig {
}
