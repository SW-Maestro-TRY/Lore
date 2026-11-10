package com.lore.zzal.diary;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
@Configuration
@ConditionalOnProperty(name="zzal.diary.enabled",havingValue="true")
public class DiarySchedulingConfiguration {
    @Bean("diaryTaskScheduler") public ThreadPoolTaskScheduler diaryTaskScheduler(){
        var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("diary-worker-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);return scheduler;
    }
}
