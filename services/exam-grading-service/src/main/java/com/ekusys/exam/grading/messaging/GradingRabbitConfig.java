package com.ekusys.exam.grading.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GradingRabbitConfig {
    public static final String EXCHANGE = "exam.events";
    public static final String SUBMISSION_QUEUE = "exam.grading.submission-accepted";
    public static final String SUBMISSION_ROUTING_KEY = "SubmissionAccepted";
    public static final String SUBMISSION_DLX = "exam.grading.dlx";
    public static final String SUBMISSION_DLQ = "exam.grading.submission-accepted.dlq";
    public static final String SUBMISSION_DLQ_ROUTING_KEY = "SubmissionAccepted.dlq";

    private static final String DEAD_LETTER_EXCHANGE_ARGUMENT = "x-dead-letter-exchange";
    private static final String DEAD_LETTER_ROUTING_KEY_ARGUMENT = "x-dead-letter-routing-key";

    @Bean
    TopicExchange examEvents() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    DirectExchange gradingSubmissionDeadLetterExchange() {
        return new DirectExchange(SUBMISSION_DLX, true, false);
    }

    @Bean
    Queue submissionQueue() {
        return QueueBuilder.durable(SUBMISSION_QUEUE)
            .withArgument(DEAD_LETTER_EXCHANGE_ARGUMENT, SUBMISSION_DLX)
            .withArgument(DEAD_LETTER_ROUTING_KEY_ARGUMENT, SUBMISSION_DLQ_ROUTING_KEY)
            .build();
    }

    @Bean
    Queue gradingSubmissionDeadLetterQueue() {
        return QueueBuilder.durable(SUBMISSION_DLQ).build();
    }

    @Bean
    Binding submissionBinding() {
        return BindingBuilder.bind(submissionQueue()).to(examEvents()).with(SUBMISSION_ROUTING_KEY);
    }

    @Bean
    Binding gradingSubmissionDeadLetterBinding() {
        return BindingBuilder.bind(gradingSubmissionDeadLetterQueue())
            .to(gradingSubmissionDeadLetterExchange())
            .with(SUBMISSION_DLQ_ROUTING_KEY);
    }

    @Bean
    SimpleRabbitListenerContainerFactory gradingSubmissionListenerContainerFactory(
        ConnectionFactory connectionFactory,
        @Value("${app.rabbitmq.grading-submission.retry.max-attempts:3}") int maxAttempts,
        @Value("${app.rabbitmq.grading-submission.retry.initial-interval-ms:1000}") long initialIntervalMs,
        @Value("${app.rabbitmq.grading-submission.retry.multiplier:2.0}") double multiplier,
        @Value("${app.rabbitmq.grading-submission.retry.max-interval-ms:10000}") long maxIntervalMs
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
            .maxRetries(Math.max(0, maxAttempts - 1))
            .backOffOptions(initialIntervalMs, multiplier, maxIntervalMs)
            .recoverer(new RejectAndDontRequeueRecoverer())
            .build());
        return factory;
    }
}
