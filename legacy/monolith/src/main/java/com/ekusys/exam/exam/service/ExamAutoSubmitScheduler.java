package com.ekusys.exam.exam.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ExamAutoSubmitScheduler {

    private final ExamService examService;

    public ExamAutoSubmitScheduler(ExamService examService) {
        this.examService = examService;
    }

    @Scheduled(fixedDelayString = "${app.exam.timeout-scan-interval-ms:15000}")
    public void autoSubmitExpiredSessions() {
        examService.autoSubmitExpiredSessions();
    }
}
