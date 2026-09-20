package com.ekusys.exam.exam.service;

import com.ekusys.exam.exam.dto.ExamSubmissionMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class ExamSubmissionConsumer {

    private static final Logger log = LoggerFactory.getLogger(ExamSubmissionConsumer.class);

    private final ExamSubmissionProcessingService examSubmissionProcessingService;

    public ExamSubmissionConsumer(ExamSubmissionProcessingService examSubmissionProcessingService) {
        this.examSubmissionProcessingService = examSubmissionProcessingService;
    }

    @RabbitListener(
        queues = "#{@examSubmissionRabbitProperties.queue}",
        containerFactory = "examSubmissionRabbitListenerContainerFactory"
    )
    public void consume(ExamSubmissionMessage message) {
        if (message == null || message.getSubmissionId() == null) {
            return;
        }
        log.info("Received exam submission message: submissionId={}, examId={}, studentId={}, timeoutSubmit={}",
            message.getSubmissionId(), message.getExamId(), message.getStudentId(), message.isTimeoutSubmit());
        examSubmissionProcessingService.processAcceptedSubmission(message.getSubmissionId());
    }
}
