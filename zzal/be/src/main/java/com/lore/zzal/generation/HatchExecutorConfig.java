package com.lore.zzal.generation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 부화를 돌릴 스레드 묶음.
 *
 * ★ 스레드 수 = 동시에 구울 수 있는 개수다. 무제한으로 두면 여러 명이 같은 시각에 올렸을 때
 *   이미지 API 한도에 걸리거나 요금이 한꺼번에 나간다.
 *   3 으로 두고 나머지는 줄을 세운다 — 기다리는 사람은 어차피 여울 튜토리얼 중이라 체감이 작다.
 *
 * ★ 큐가 가득 차면 요청을 거부하지 않고 **부른 쪽 스레드에서 처리**한다(CallerRuns).
 *   그러면 그 요청만 느려지고, 작업이 조용히 사라지지는 않는다.
 */
@Configuration
@EnableAsync
public class HatchExecutorConfig {

    @Bean("hatchExecutor")
    public Executor hatchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(3);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("hatch-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
