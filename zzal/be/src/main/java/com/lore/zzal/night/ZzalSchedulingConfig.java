package com.lore.zzal.night;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 밤 스위프의 시각 트리거와 실행기.
 *
 * <h3>{@code @EnableScheduling} 이 여기 있는 이유</h3>
 * 이 서비스의 시각 트리거는 {@link NightSweep} 하나뿐이다(플랜 T1 핵심 판단 2). 공용 설정에 두지 않고 zzal 안에
 * 둬서 "누가 타이머를 켰나" 가 한 파일에서 보이게 한다. 스위프 자체는 {@code app.zzal.night.sweep-enabled} 로 막힌다.
 *
 * <h3>{@code nightExecutor}</h3>
 * {@code hatchExecutor}(3·큐 50·CallerRuns)에 200건을 넣으면 스케줄러 스레드가 굽기를 떠안고 부화와 자리를 다툰다.
 * 밤 굽기는 2스레드·큐 = K 로 따로 돈다. K 를 넘길 일이 없어 거절 정책은 CallerRuns 로 두되, 그 경우도 스케줄러 스레드가
 * 한 건 굽고 돌아올 뿐 잃지 않는다.
 *
 * <h3>★★ 종료 중일 때는 조용히 버리지 않고 예외를 던진다</h3>
 * JDK 의 {@code CallerRunsPolicy} 는 실행기가 <b>종료 중이면 호출자 실행도 하지 않고 작업을 버린다.</b>
 * {@code execute()} 는 정상적으로 돌아오므로 부르는 쪽은 "넘겼다" 로 알고, 그 모션은 아무도 굽지 않는 채
 * {@code BAKING} 으로 남는다(2026-09-05 리뷰 Codex 4). 그래서 종료 중이면 예외를 던져
 * {@code NightSweep.claimOne} 이 집기를 되돌리게 한다 — <b>실패가 보여야 되돌릴 수 있다.</b>
 */
@Configuration
@EnableScheduling
public class ZzalSchedulingConfig {

    @Bean("nightExecutor")
    public Executor nightExecutor(@Value("${app.zzal.night.max-bakes:200}") int maxBakes) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(Math.max(maxBakes, 10));
        executor.setThreadNamePrefix("night-");
        ThreadPoolExecutor.CallerRunsPolicy callerRuns = new ThreadPoolExecutor.CallerRunsPolicy();
        executor.setRejectedExecutionHandler((task, pool) -> {
            if (pool.isShutdown()) {
                throw new RejectedExecutionException("nightExecutor 가 종료 중이라 밤 굽기를 받지 못했습니다");
            }
            callerRuns.rejectedExecution(task, pool);
        });
        executor.initialize();
        return executor;
    }
}
