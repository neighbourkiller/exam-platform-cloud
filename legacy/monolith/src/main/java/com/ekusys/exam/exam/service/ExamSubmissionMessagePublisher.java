package com.ekusys.exam.exam.service;

import com.ekusys.exam.common.config.ExamSubmissionRabbitProperties;
import com.ekusys.exam.exam.dto.ExamSubmissionMessage;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
public class ExamSubmissionMessagePublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ExamSubmissionRabbitProperties rabbitProperties;

    public ExamSubmissionMessagePublisher(RabbitTemplate rabbitTemplate,
                                          ExamSubmissionRabbitProperties rabbitProperties) {
        this.rabbitTemplate = rabbitTemplate;
        this.rabbitProperties = rabbitProperties;
    }

    public void publish(ExamSubmissionMessage message) {
        rabbitTemplate.convertAndSend(
            rabbitProperties.getExchange(),
            rabbitProperties.getRoutingKey(),
            message
        );
    }
}
