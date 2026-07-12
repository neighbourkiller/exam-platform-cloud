package com.ekusys.exam.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class OutboxContextTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(TestConfiguration.class)
        .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
        .withBean(RabbitTemplate.class, () -> mock(RabbitTemplate.class))
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void createsSingleSharedPublisherByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(OutboxPublisher.class);
            assertThat(context).hasSingleBean(OutboxEventWriter.class);
            assertThat(context).hasSingleBean(OutboxRepository.class);
        });
    }

    @Test
    void publisherCanBeDisabledForCoordinatedMaintenance() {
        contextRunner.withPropertyValues("app.outbox.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(OutboxPublisher.class);
            assertThat(context).hasSingleBean(OutboxEventWriter.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
        OutboxProperties.class,
        OutboxBackoffPolicy.class,
        OutboxEventWriter.class,
        OutboxRepository.class,
        OutboxConfiguration.class,
        OutboxPublisher.class
    })
    static class TestConfiguration {
    }
}
