package com.ekusys.exam.common.outbox;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class OutboxConfiguration {

    @Bean
    @Qualifier("outboxPublisherExecutor")
    ThreadPoolTaskExecutor outboxPublisherExecutor(OutboxProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.safeWorkerCount());
        executor.setMaxPoolSize(properties.safeWorkerCount());
        executor.setQueueCapacity(properties.safeBatchSize());
        executor.setThreadNamePrefix("outbox-publisher-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
