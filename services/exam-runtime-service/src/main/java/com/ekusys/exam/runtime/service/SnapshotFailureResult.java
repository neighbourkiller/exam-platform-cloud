package com.ekusys.exam.runtime.service;

record SnapshotFailureResult(boolean updated, boolean stale, boolean quarantined, int attempts) {

    static SnapshotFailureResult staleResult() {
        return new SnapshotFailureResult(true, true, false, 0);
    }

    static SnapshotFailureResult leaseLost() {
        return new SnapshotFailureResult(false, true, false, 0);
    }
}
