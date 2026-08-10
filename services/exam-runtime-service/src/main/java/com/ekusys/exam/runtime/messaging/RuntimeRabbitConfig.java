package com.ekusys.exam.runtime.messaging;

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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.ekusys.exam.runtime.entry.ExamEntryMetrics;

@Configuration
public class RuntimeRabbitConfig {
    public static final String EXCHANGE = "exam.events";
    public static final String QUEUE = "exam.grading.submission-accepted";
    public static final String ROUTING_KEY = "SubmissionAccepted";
    public static final String DLX = "exam.grading.dlx";
    public static final String DLQ = "exam.grading.submission-accepted.dlq";
    public static final String DLQ_ROUTING_KEY = "SubmissionAccepted.dlq";
    public static final String LIFECYCLE_QUEUE = "exam.runtime.exam-lifecycle";
    public static final String LIFECYCLE_DLX = "exam.runtime.dlx";
    public static final String LIFECYCLE_DLQ = "exam.runtime.exam-lifecycle.dlq";
    public static final String LIFECYCLE_DLQ_ROUTING_KEY = "ExamLifecycle.dlq";

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

    @Bean
    DirectExchange runtimeLifecycleDeadLetterExchange() {
        return new DirectExchange(LIFECYCLE_DLX, true, false);
    }

    @Bean
    Queue runtimeLifecycleQueue() {
        return QueueBuilder.durable(LIFECYCLE_QUEUE)
            .withArgument(DEAD_LETTER_EXCHANGE_ARGUMENT, LIFECYCLE_DLX)
            .withArgument(DEAD_LETTER_ROUTING_KEY_ARGUMENT, LIFECYCLE_DLQ_ROUTING_KEY)
            .build();
    }

    @Bean
    Queue runtimeLifecycleDeadLetterQueue() {
        return QueueBuilder.durable(LIFECYCLE_DLQ).build();
    }

    @Bean
    Binding runtimeExamPublishedBinding(
        @Qualifier("examEventsExchange") TopicExchange examEventsExchange,
        @Qualifier("runtimeLifecycleQueue") Queue runtimeLifecycleQueue
    ) {
        return BindingBuilder.bind(runtimeLifecycleQueue)
            .to(examEventsExchange)
            .with("ExamPublished");
    }

    @Bean
    Binding runtimeExamTerminatedBinding(
        @Qualifier("examEventsExchange") TopicExchange examEventsExchange,
        @Qualifier("runtimeLifecycleQueue") Queue runtimeLifecycleQueue
    ) {
        return BindingBuilder.bind(runtimeLifecycleQueue)
            .to(examEventsExchange)
            .with("ExamTerminated");
    }

    @Bean
    Binding runtimeLifecycleDeadLetterBinding(
        @Qualifier("runtimeLifecycleDeadLetterExchange") DirectExchange runtimeLifecycleDeadLetterExchange,
        @Qualifier("runtimeLifecycleDeadLetterQueue") Queue runtimeLifecycleDeadLetterQueue
    ) {
        return BindingBuilder.bind(runtimeLifecycleDeadLetterQueue)
            .to(runtimeLifecycleDeadLetterExchange)
            .with(LIFECYCLE_DLQ_ROUTING_KEY);
    }

    @Bean
    SimpleRabbitListenerContainerFactory runtimeLifecycleListenerContainerFactory(
        ConnectionFactory connectionFactory,
        ExamEntryMetrics metrics,
        @Value("${app.rabbitmq.runtime-lifecycle.retry.max-attempts:3}") int maxAttempts,
        @Value("${app.rabbitmq.runtime-lifecycle.retry.initial-interval-ms:1000}") long initialIntervalMs,
        @Value("${app.rabbitmq.runtime-lifecycle.retry.multiplier:2.0}") double multiplier,
        @Value("${app.rabbitmq.runtime-lifecycle.retry.max-interval-ms:10000}") long maxIntervalMs
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setDefaultRequeueRejected(false);
        RejectAndDontRequeueRecoverer delegate = new RejectAndDontRequeueRecoverer();
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
            .maxRetries(Math.max(0, maxAttempts - 1))
            .backOffOptions(initialIntervalMs, multiplier, maxIntervalMs)
            .recoverer((message, cause) -> {
                metrics.lifecycleDeadLettered();
                delegate.recover(message, cause);
            })
            .build());
        return factory;
    }
}
