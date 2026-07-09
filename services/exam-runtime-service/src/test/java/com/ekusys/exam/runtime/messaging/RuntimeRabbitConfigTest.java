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
}
