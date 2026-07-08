package com.ekusys.exam.runtime.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SnapshotFlushScheduler {
    private final ExamSnapshotService snapshotService;

    public SnapshotFlushScheduler(ExamSnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    @Scheduled(fixedDelayString = "${app.snapshot.flush-interval-ms:30000}")
    public void flushSnapshots() {
        snapshotService.flushAll();
    }
}
