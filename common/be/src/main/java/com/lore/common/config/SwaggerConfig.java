package com.lore.common.config;

/**
 * Swagger(API 문서) 설정 자리.
 *
 * 도메인이 3개로 나뉘어 있어도 문서는 한 곳에서 모아 보는 게 편하므로 common 에 둔다.
 *
 * springdoc 의존성이 아직 없어서 애노테이션은 주석으로만 남겨둠.
 * 의존성 추가 후 백엔드 담당자가 아래 형태로 채우면 된다.
 *
 * <pre>
 * &#64;Configuration
 * public class SwaggerConfig {
 *     &#64;Bean
 *     public OpenAPI openAPI() {
 *         return new OpenAPI().info(new Info().title("Lore API").version("v0.0.1"));
 *     }
 * }
 * </pre>
 */
public class SwaggerConfig {
}
