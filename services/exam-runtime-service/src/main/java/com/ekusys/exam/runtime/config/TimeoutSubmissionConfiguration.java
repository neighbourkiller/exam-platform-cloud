package com.ekusys.exam.runtime.config;

import com.ekusys.exam.runtime.observation.TimeoutSubmissionObservation;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class TimeoutSubmissionConfiguration {
    private static final Logger log = LoggerFactory.getLogger(TimeoutSubmissionConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(TimeoutSubmissionObservation.class)
    TimeoutSubmissionObservation timeoutSubmissionObservation() {
        return TimeoutSubmissionObservation.NOOP;
    }

    @Bean
    ApplicationRunner timeoutSubmissionModeReporter(TimeoutSubmissionProperties properties) {
        return arguments -> log.info(
            "Timeout submission initialized: mode={}, claimStrategy={}, crossShardDelayMs={}, claimSize={}, workerCount={}, leaseMs={}, leaseRenewIntervalMs={}, taskTimeoutMs={}, maxRunMs={}, maxAttempts={}, backlogRefreshIntervalMs={}, reconcileEnabled={}, reconcileIntervalMs={}, reconcileBatchSize={}, reconcileMaxRunMs={}, finalizationTimeoutSeconds={}",
            properties.isEnabled() ? "V2" : "V1",
            properties.isEnabled() ? "shard-first+cross-shard-recovery" : "fixed-shard-scan",
            properties.safeCrossShardDelayMs(),
            properties.safeClaimSize(),
            properties.safeWorkerCount(),
            properties.safeLeaseMs(),
            properties.safeLeaseRenewIntervalMs(),
            properties.safeTaskTimeoutMs(),
            properties.safeMaxRunMs(),
            properties.safeMaxAttempts(),
            properties.safeBacklogRefreshIntervalMs(),
            properties.isReconcileEnabled(),
            properties.safeReconcileIntervalMs(),
            properties.safeReconcileBatchSize(),
            properties.safeReconcileMaxRunMs(),
            properties.safeFinalizationTransactionTimeoutSeconds()
        );
    }

    @Bean
    @Qualifier("timeoutSubmissionExecutor")
    ThreadPoolTaskExecutor timeoutSubmissionExecutor(TimeoutSubmissionProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.safeWorkerCount());
        executor.setMaxPoolSize(properties.safeWorkerCount());
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("timeout-submit-worker-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }

    @Bean
    @Qualifier("timeoutSubmissionLeaseScheduler")
    ThreadPoolTaskScheduler timeoutSubmissionLeaseScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("timeout-submit-lease-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }

    @Bean
    @Qualifier("timeoutSubmissionFinalizationTransactionTemplate")
    TransactionTemplate timeoutSubmissionFinalizationTransactionTemplate(
        PlatformTransactionManager transactionManager, TimeoutSubmissionProperties properties
    ) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        template.setTimeout(properties.safeFinalizationTransactionTimeoutSeconds());
        return template;
    }

    @Bean
    @Qualifier("finalizationPostCommitExecutor")
    ThreadPoolTaskExecutor finalizationPostCommitExecutor(io.micrometer.core.instrument.MeterRegistry registry) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10000);
        executor.setThreadNamePrefix("finalization-post-commit-");
        executor.setRejectedExecutionHandler((runnable, exec) -> {
            registry.counter("exam.timeout.submission.events", "outcome", "post_commit_dropped").increment();
            log.warn("Finalization post-commit task dropped due to saturated queue");
        });
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }
}
