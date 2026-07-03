package com.ekusys.exam.grading.messaging;

import com.ekusys.exam.grading.service.GradingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SubmissionAcceptedConsumer {
    private static final String CONSUMER = "grading-submission-accepted";

    private final GradingService service;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    public SubmissionAcceptedConsumer(GradingService service, ObjectMapper objectMapper, JdbcTemplate jdbc) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
    }

    @RabbitListener(queues = "exam.grading.submission-accepted")
    @Transactional
    public void consume(String payload) throws Exception {
        JsonNode root = objectMapper.readTree(payload);
        String eventId = root.path("eventId").asText();
        int inserted = jdbc.update(
            "insert ignore into inbox_event(event_id,consumer_name,processed_at) values(?,?,current_timestamp(3))",
            eventId, CONSUMER
        );
        if (inserted == 0) {
            return;
        }
        service.processSubmission(root.path("data").path("submissionId").longValue());
    }
}
