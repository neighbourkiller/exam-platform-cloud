package com.ekusys.exam.runtime.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RuntimeRabbitConfig {
    public static final String EXCHANGE = "exam.events";
    public static final String QUEUE = "exam.grading.submission-accepted";
    public static final String ROUTING_KEY = "SubmissionAccepted";
    public static final String DLX = "exam.grading.dlx";
    public static final String DLQ = "exam.grading.submission-accepted.dlq";
    public static final String DLQ_ROUTING_KEY = "SubmissionAccepted.dlq";

    private static final String DEAD_LETTER_EXCHANGE_ARGUMENT = "x-dead-letter-exchange";
    private static final String DEAD_LETTER_ROUTING_KEY_ARGUMENT = "x-dead-letter-routing-key";

    @Bean
    TopicExchange examEventsExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    DirectExchange gradingDeadLetterExchange() {
        return new DirectExchange(DLX, true, false);
    }

    @Bean
    Queue gradingQueue() {
        return QueueBuilder.durable(QUEUE)
            .withArgument(DEAD_LETTER_EXCHANGE_ARGUMENT, DLX)
            .withArgument(DEAD_LETTER_ROUTING_KEY_ARGUMENT, DLQ_ROUTING_KEY)
            .build();
    }

    @Bean
    Queue gradingDeadLetterQueue() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    Binding gradingBinding() {
        return BindingBuilder.bind(gradingQueue()).to(examEventsExchange()).with(ROUTING_KEY);
    }

    @Bean
    Binding gradingDeadLetterBinding() {
        return BindingBuilder.bind(gradingDeadLetterQueue())
            .to(gradingDeadLetterExchange())
            .with(DLQ_ROUTING_KEY);
    }
}
