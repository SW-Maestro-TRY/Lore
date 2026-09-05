package com.lore.webtoon;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 이 도메인의 설정을 켜는 자리.
 *
 * {@link HarnessProperties} 를 여기서 켠다 — 공용 진입점(apps/api)의
 * 클래스를 안 건드리려는 것이다. 도메인이 늘어도 서로의 설정을 모른 채
 * 각자 켜면 된다.
 */
@Configuration
@EnableConfigurationProperties(HarnessProperties.class)
public class WebtoonConfig {
}
