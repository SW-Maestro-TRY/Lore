package com.lore.zzal.chat.memory.facts;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
@Configuration
public class MemoryCaptureExecutorConfig {
    @Bean("memoryCaptureExecutor")
    public ThreadPoolTaskExecutor memoryCaptureExecutor(){
        var executor=new ThreadPoolTaskExecutor();executor.setCorePoolSize(1);executor.setMaxPoolSize(1);
        executor.setQueueCapacity(100);executor.setThreadNamePrefix("zzal-memory-");
        executor.setWaitForTasksToCompleteOnShutdown(true);executor.setAwaitTerminationSeconds(5);
        return executor;
    }
}
