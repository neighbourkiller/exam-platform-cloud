package com.ekusys.exam.runtime.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class TimeoutSubmissionConfiguration {

    @Bean
    @Qualifier("timeoutSubmissionExecutor")
    ThreadPoolTaskExecutor timeoutSubmissionExecutor(TimeoutSubmissionProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.safeWorkerCount());
        executor.setMaxPoolSize(properties.safeWorkerCount());
        executor.setQueueCapacity(properties.safeBatchSize());
        executor.setThreadNamePrefix("timeout-submit-worker-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
