package com.ekusys.exam.runtime.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SnapshotFlushScheduler {
    private final SnapshotFlushCoordinator coordinator;

    public SnapshotFlushScheduler(SnapshotFlushCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Scheduled(
        fixedDelayString = "${app.snapshot.flush-interval-ms:30000}",
        scheduler = "snapshotFlushTaskScheduler"
    )
    public void flushSnapshots() {
        coordinator.flushDue();
    }

    @Scheduled(
        fixedDelayString = "${app.snapshot.flush-reconcile-interval-ms:300000}",
        initialDelayString = "${app.snapshot.flush-reconcile-interval-ms:300000}",
        scheduler = "snapshotFlushTaskScheduler"
    )
    public void reconcileSnapshots() {
        coordinator.reconcile();
    }

    @Scheduled(
        fixedDelayString = "${app.snapshot.flush-cleanup-interval-ms:3600000}",
        initialDelayString = "${app.snapshot.flush-cleanup-interval-ms:3600000}",
        scheduler = "snapshotFlushTaskScheduler"
    )
    public void cleanupFailedSnapshots() {
        coordinator.cleanupFailed();
    }
}
