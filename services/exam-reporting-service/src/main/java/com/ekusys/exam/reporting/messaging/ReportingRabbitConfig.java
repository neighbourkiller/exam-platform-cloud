package com.ekusys.exam.reporting.messaging;

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
public class ReportingRabbitConfig {
    public static final String EXCHANGE = "exam.events";
    public static final String QUEUE = "exam.reporting.events";
    public static final String DLX = "exam.reporting.dlx";
    public static final String DLQ = "exam.reporting.events.dlq";
    public static final String DLQ_ROUTING_KEY = "ReportingEvent.dlq";

    private static final String DEAD_LETTER_EXCHANGE_ARGUMENT = "x-dead-letter-exchange";
    private static final String DEAD_LETTER_ROUTING_KEY_ARGUMENT = "x-dead-letter-routing-key";

    @Bean
    TopicExchange reportingExamEventsExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    DirectExchange reportingDeadLetterExchange() {
        return new DirectExchange(DLX, true, false);
    }

    @Bean
    Queue reportingEventsQueue() {
        return QueueBuilder.durable(QUEUE)
            .withArgument(DEAD_LETTER_EXCHANGE_ARGUMENT, DLX)
            .withArgument(DEAD_LETTER_ROUTING_KEY_ARGUMENT, DLQ_ROUTING_KEY)
            .build();
    }

    @Bean
    Queue reportingDeadLetterQueue() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    Binding reportingDeadLetterBinding() {
        return BindingBuilder.bind(reportingDeadLetterQueue())
            .to(reportingDeadLetterExchange())
            .with(DLQ_ROUTING_KEY);
    }

    @Bean
    Binding reportingExamPublishedBinding(TopicExchange reportingExamEventsExchange, Queue reportingEventsQueue) {
        return BindingBuilder.bind(reportingEventsQueue).to(reportingExamEventsExchange).with("ExamPublished");
    }

    @Bean
    Binding reportingExamTerminatedBinding(TopicExchange reportingExamEventsExchange, Queue reportingEventsQueue) {
        return BindingBuilder.bind(reportingEventsQueue).to(reportingExamEventsExchange).with("ExamTerminated");
    }

    @Bean
    Binding reportingGradeCompletedBinding(TopicExchange reportingExamEventsExchange, Queue reportingEventsQueue) {
        return BindingBuilder.bind(reportingEventsQueue).to(reportingExamEventsExchange).with("GradeCompleted");
    }

    @Bean
    Binding reportingSubmissionAcceptedBinding(TopicExchange reportingExamEventsExchange, Queue reportingEventsQueue) {
        return BindingBuilder.bind(reportingEventsQueue).to(reportingExamEventsExchange).with("SubmissionAccepted");
    }

    @Bean
    Binding reportingProctoringBinding(TopicExchange reportingExamEventsExchange, Queue reportingEventsQueue) {
        return BindingBuilder.bind(reportingEventsQueue).to(reportingExamEventsExchange).with("ProctoringEventRecorded");
    }

    @Bean
    Binding reportingAuditBinding(TopicExchange reportingExamEventsExchange, Queue reportingEventsQueue) {
        return BindingBuilder.bind(reportingEventsQueue).to(reportingExamEventsExchange).with("AuditOperationRecorded");
    }

    @Bean
    SimpleRabbitListenerContainerFactory reportingEventsListenerContainerFactory(
        ConnectionFactory connectionFactory,
        @Value("${app.rabbitmq.reporting.retry.max-attempts:3}") int maxAttempts,
        @Value("${app.rabbitmq.reporting.retry.initial-interval-ms:1000}") long initialIntervalMs,
        @Value("${app.rabbitmq.reporting.retry.multiplier:2.0}") double multiplier,
        @Value("${app.rabbitmq.reporting.retry.max-interval-ms:10000}") long maxIntervalMs
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
