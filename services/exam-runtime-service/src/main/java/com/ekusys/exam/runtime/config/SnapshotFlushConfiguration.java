package com.ekusys.exam.runtime.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class SnapshotFlushConfiguration {

    @Bean
    @Qualifier("snapshotFlushExecutor")
    ThreadPoolTaskExecutor snapshotFlushExecutor(SnapshotProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.safeFlushWorkerCount());
        executor.setMaxPoolSize(properties.safeFlushWorkerCount());
        executor.setQueueCapacity(properties.safeFlushBatchSize());
        executor.setThreadNamePrefix("snapshot-flush-worker-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }

    @Bean
    @Qualifier("snapshotFlushTaskScheduler")
    ThreadPoolTaskScheduler snapshotFlushTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("snapshot-flush-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }
}
