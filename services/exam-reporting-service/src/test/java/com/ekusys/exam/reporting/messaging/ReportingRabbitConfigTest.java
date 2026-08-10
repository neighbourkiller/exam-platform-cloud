package com.ekusys.exam.reporting.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.test.util.ReflectionTestUtils;

class ReportingRabbitConfigTest {

    private final ReportingRabbitConfig config = new ReportingRabbitConfig();

    @Test
    void reportingQueueUsesDeadLetterExchange() {
        Queue queue = config.reportingEventsQueue();

        assertThat(queue.getName()).isEqualTo(ReportingRabbitConfig.QUEUE);
        assertThat(queue.getArguments())
            .containsEntry("x-dead-letter-exchange", ReportingRabbitConfig.DLX)
            .containsEntry("x-dead-letter-routing-key", ReportingRabbitConfig.DLQ_ROUTING_KEY);
    }

    @Test
    void deadLetterQueueIsBoundWithDedicatedRoutingKey() {
        Binding binding = config.reportingDeadLetterBinding();

        assertThat(binding.getDestination()).isEqualTo(ReportingRabbitConfig.DLQ);
        assertThat(binding.getExchange()).isEqualTo(ReportingRabbitConfig.DLX);
        assertThat(binding.getRoutingKey()).isEqualTo(ReportingRabbitConfig.DLQ_ROUTING_KEY);
    }

    @Test
    void reportingQueueBindsSessionStartedBeforeRuntimePublishesIt() {
        Binding binding = config.reportingSessionStartedBinding(
            config.reportingExamEventsExchange(), config.reportingEventsQueue()
        );

        assertThat(binding.getDestination()).isEqualTo(ReportingRabbitConfig.QUEUE);
        assertThat(binding.getRoutingKey()).isEqualTo("SessionStarted");
    }

    @Test
    void reportingConsumerUsesBoundedRetryContainerFactory() throws NoSuchMethodException {
        Method consume = ReportingEventConsumer.class.getMethod("consume", String.class);
        RabbitListener listener = consume.getAnnotation(RabbitListener.class);
        SimpleRabbitListenerContainerFactory factory = config.reportingEventsListenerContainerFactory(
            mock(ConnectionFactory.class), 3, 1000L, 2.0, 10000L
        );

        assertThat(listener.queues()).containsExactly(ReportingRabbitConfig.QUEUE);
        assertThat(listener.containerFactory()).isEqualTo("reportingEventsListenerContainerFactory");
        assertThat(ReflectionTestUtils.getField(factory, "defaultRequeueRejected")).isEqualTo(false);
        assertThat(factory.getAdviceChain()).hasSize(1);
    }
}
