package com.ekusys.exam.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.ekusys.exam.common.outbox.OutboxConfiguration;
import com.ekusys.exam.common.outbox.OutboxProperties;
import com.ekusys.exam.runtime.service.SnapshotFlushScheduler;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class SnapshotFlushConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(SnapshotFlushConfiguration.class, OutboxConfiguration.class)
        .withBean(SnapshotProperties.class, SnapshotProperties::new)
        .withBean(OutboxProperties.class, OutboxProperties::new);

    @Test
    void createsBoundedWorkersAndDedicatedScheduler() {
        contextRunner.run(context -> {
            ThreadPoolTaskExecutor executor = context.getBean(
                "snapshotFlushExecutor", ThreadPoolTaskExecutor.class
            );
            ThreadPoolTaskScheduler scheduler = context.getBean(
                "snapshotFlushTaskScheduler", ThreadPoolTaskScheduler.class
            );

            assertThat(executor.getCorePoolSize()).isEqualTo(4);
            assertThat(executor.getMaxPoolSize()).isEqualTo(4);
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(100);
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
            assertThat(scheduler).isNotSameAs(
                context.getBean("outboxTaskScheduler", ThreadPoolTaskScheduler.class)
            );
        });
    }

    @Test
    void usesFifteenMinuteFlushDelayAndThirtySecondPollingByDefault() {
        SnapshotProperties properties = new SnapshotProperties();

        assertThat(properties.getFlushIntervalMs()).isEqualTo(900_000L);
        assertThat(properties.getFlushPollIntervalMs()).isEqualTo(30_000L);
        assertThat(properties.getFlushMaxBatchesPerRun()).isEqualTo(10);
    }

    @Test
    void allSnapshotSchedulesUseDedicatedScheduler() {
        for (String methodName : new String[]{
            "flushSnapshots", "reconcileSnapshots", "cleanupFailedSnapshots"
        }) {
            Method method = org.springframework.util.ReflectionUtils.findMethod(
                SnapshotFlushScheduler.class, methodName
            );
            assertThat(method).isNotNull();
            Scheduled scheduled = method.getAnnotation(Scheduled.class);
            assertThat(scheduled.scheduler()).isEqualTo("snapshotFlushTaskScheduler");
        }
    }

    @Test
    void flushScheduleUsesIndependentPollingInterval() {
        Method method = org.springframework.util.ReflectionUtils.findMethod(
            SnapshotFlushScheduler.class, "flushSnapshots"
        );

        assertThat(method).isNotNull();
        assertThat(method.getAnnotation(Scheduled.class).fixedDelayString())
            .isEqualTo("${app.snapshot.flush-poll-interval-ms:30000}");
    }
}
