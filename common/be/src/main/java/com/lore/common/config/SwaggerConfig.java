package com.lore.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger(API 문서) 설정.
 *
 * 도메인이 3개로 나뉘어 있어도 문서는 한 곳에서 모아 보는 게 편하므로 common 에 둔다.
 * springdoc 의존성만으로도 문서는 뜨지만, 제목·설명·버전은 여기서 정해준다.
 *
 * 문서 주소: http://localhost:8080/swagger-ui.html
 */
@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI loreOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("Lore API")
                .description("Lore 창작 플랫폼 API 문서 (comic / webtoon / trailer)")
                .version("v0.0.1"));
    }
}
