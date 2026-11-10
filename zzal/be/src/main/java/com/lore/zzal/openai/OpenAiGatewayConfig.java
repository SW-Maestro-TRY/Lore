package com.lore.zzal.openai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 창구 빈 하나. 실제 생성이 꺼져 있어도 만든다 — 생성 단계 실행기({@link OpenAiGateway#executor()})를 같이 쓰기 때문이다. */
@Configuration
public class OpenAiGatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(OpenAiGatewayConfig.class);

    @Bean(destroyMethod = "close")
    public OpenAiGateway openAiGateway(Environment env) {
        var lanes = LaneConfig.from(env);
        var block = java.time.Duration.ofSeconds(env.getProperty("app.zzal.openai.quota-default-block-seconds", Long.class,
                OpenAiGateway.DEFAULT_QUOTA_BLOCK.toSeconds()));
        log.info("OpenAI 창구 — 줄 {} · 429 기본 차단 {}초", lanes, block.toSeconds());
        return new OpenAiGateway(lanes, block);
    }
}
