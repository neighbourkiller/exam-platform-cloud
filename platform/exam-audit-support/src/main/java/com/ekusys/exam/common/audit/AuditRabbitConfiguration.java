package com.ekusys.exam.common.audit;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AuditRabbitConfiguration {
    public static final String EXCHANGE = "exam.events";

    @Bean
    TopicExchange auditExamEventsExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }
}
