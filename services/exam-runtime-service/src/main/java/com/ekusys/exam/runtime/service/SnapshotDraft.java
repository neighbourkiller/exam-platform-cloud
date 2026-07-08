package com.ekusys.exam.runtime.service;

import java.time.LocalDateTime;
import java.util.Map;

public record SnapshotDraft(Map<Long, String> answers, long version, LocalDateTime updatedAt) {
    public static SnapshotDraft empty() {
        return new SnapshotDraft(Map.of(), 0L, null);
    }
}
