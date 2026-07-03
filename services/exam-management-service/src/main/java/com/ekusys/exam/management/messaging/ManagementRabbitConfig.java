package com.ekusys.exam.management.messaging;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ManagementRabbitConfig {
    public static final String EXCHANGE = "exam.events";

    @Bean
    TopicExchange managementExamEventsExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }
}
