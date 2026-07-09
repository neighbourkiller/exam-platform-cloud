package com.ekusys.exam.reporting.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ReportingRabbitConfig {
    public static final String QUEUE = "exam.reporting.events";

    @Bean
    TopicExchange reportingExamEventsExchange() {
        return new TopicExchange("exam.events", true, false);
    }

    @Bean
    Queue reportingEventsQueue() {
        return QueueBuilder.durable(QUEUE).build();
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
}
