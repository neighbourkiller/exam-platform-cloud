package com.ekusys.exam.grading.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

class GradingRabbitConfigTest {

    private final GradingRabbitConfig config = new GradingRabbitConfig();

    @Test
    void submissionQueueUsesDeadLetterExchange() {
        Queue queue = config.submissionQueue();

        assertThat(queue.getName()).isEqualTo(GradingRabbitConfig.SUBMISSION_QUEUE);
        assertThat(queue.getArguments())
            .containsEntry("x-dead-letter-exchange", GradingRabbitConfig.SUBMISSION_DLX)
            .containsEntry("x-dead-letter-routing-key", GradingRabbitConfig.SUBMISSION_DLQ_ROUTING_KEY);
    }

    @Test
    void deadLetterQueueIsBoundWithDedicatedRoutingKey() {
        Binding binding = config.gradingSubmissionDeadLetterBinding();

        assertThat(binding.getDestination()).isEqualTo(GradingRabbitConfig.SUBMISSION_DLQ);
        assertThat(binding.getExchange()).isEqualTo(GradingRabbitConfig.SUBMISSION_DLX);
        assertThat(binding.getRoutingKey()).isEqualTo(GradingRabbitConfig.SUBMISSION_DLQ_ROUTING_KEY);
    }

    @Test
    void submissionConsumerUsesRetryContainerFactory() throws NoSuchMethodException {
        Method consume = SubmissionAcceptedConsumer.class.getMethod("consume", String.class);
        RabbitListener listener = consume.getAnnotation(RabbitListener.class);

        assertThat(listener.queues()).containsExactly(GradingRabbitConfig.SUBMISSION_QUEUE);
        assertThat(listener.containerFactory()).isEqualTo("gradingSubmissionListenerContainerFactory");
    }
}
