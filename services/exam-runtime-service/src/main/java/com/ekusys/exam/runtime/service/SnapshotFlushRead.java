package com.ekusys.exam.runtime.service;

record SnapshotFlushRead(boolean leaseValid, String payload, Long version) {

    static SnapshotFlushRead leaseLost() {
        return new SnapshotFlushRead(false, null, null);
    }

    static SnapshotFlushRead missing() {
        return new SnapshotFlushRead(true, null, null);
    }
}
