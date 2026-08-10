package com.ekusys.exam.runtime.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;

class RuntimeRabbitConfigTest {

    private final RuntimeRabbitConfig config = new RuntimeRabbitConfig();

    @Test
    void gradingQueueUsesSameDeadLetterExchangeAsConsumerSide() {
        Queue queue = config.gradingQueue();

        assertThat(queue.getName()).isEqualTo(RuntimeRabbitConfig.QUEUE);
        assertThat(queue.getArguments())
            .containsEntry("x-dead-letter-exchange", RuntimeRabbitConfig.DLX)
            .containsEntry("x-dead-letter-routing-key", RuntimeRabbitConfig.DLQ_ROUTING_KEY);
    }

    @Test
    void gradingDeadLetterQueueIsBoundWithDedicatedRoutingKey() {
        Binding binding = config.gradingDeadLetterBinding();

        assertThat(binding.getDestination()).isEqualTo(RuntimeRabbitConfig.DLQ);
        assertThat(binding.getExchange()).isEqualTo(RuntimeRabbitConfig.DLX);
        assertThat(binding.getRoutingKey()).isEqualTo(RuntimeRabbitConfig.DLQ_ROUTING_KEY);
    }

    @Test
    void lifecycleQueueHasIndependentDeadLetterTopology() {
        Queue queue = config.runtimeLifecycleQueue();
        Binding binding = config.runtimeLifecycleDeadLetterBinding(
            config.runtimeLifecycleDeadLetterExchange(),
            config.runtimeLifecycleDeadLetterQueue()
        );

        assertThat(queue.getName()).isEqualTo(RuntimeRabbitConfig.LIFECYCLE_QUEUE);
        assertThat(queue.getArguments())
            .containsEntry("x-dead-letter-exchange", RuntimeRabbitConfig.LIFECYCLE_DLX)
            .containsEntry("x-dead-letter-routing-key", RuntimeRabbitConfig.LIFECYCLE_DLQ_ROUTING_KEY);
        assertThat(binding.getDestination()).isEqualTo(RuntimeRabbitConfig.LIFECYCLE_DLQ);
        assertThat(binding.getExchange()).isEqualTo(RuntimeRabbitConfig.LIFECYCLE_DLX);
    }

    @Test
    void lifecycleQueueReceivesPublishedAndTerminatedEvents() {
        Binding published = config.runtimeExamPublishedBinding(
            config.examEventsExchange(), config.runtimeLifecycleQueue()
        );
        Binding terminated = config.runtimeExamTerminatedBinding(
            config.examEventsExchange(), config.runtimeLifecycleQueue()
        );

        assertThat(published.getDestination()).isEqualTo(RuntimeRabbitConfig.LIFECYCLE_QUEUE);
        assertThat(published.getRoutingKey()).isEqualTo("ExamPublished");
        assertThat(terminated.getDestination()).isEqualTo(RuntimeRabbitConfig.LIFECYCLE_QUEUE);
        assertThat(terminated.getRoutingKey()).isEqualTo("ExamTerminated");
    }
}
