package com.ekusys.exam.runtime.service;

record SnapshotFlushBacklog(long dirty, long processing, long failed, long oldestDirtyOverdueMs) {
}
