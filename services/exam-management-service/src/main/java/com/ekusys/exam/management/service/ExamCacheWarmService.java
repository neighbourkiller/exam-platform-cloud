package com.ekusys.exam.management.service;

import com.ekusys.exam.management.client.ContentSnapshotClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ExamCacheWarmService {
    private static final Logger log = LoggerFactory.getLogger(ExamCacheWarmService.class);

    private final ContentSnapshotClient content;
    private final MeterRegistry meterRegistry;

    public ExamCacheWarmService(ContentSnapshotClient content, MeterRegistry meterRegistry) {
        this.content = content;
        this.meterRegistry = meterRegistry;
    }

    public boolean warmPaper(Long examId, Long snapshotId) {
        try {
            content.warm(snapshotId);
            meterRegistry.counter("exam.cache.prewarm", "outcome", "success").increment();
            return true;
        } catch (RuntimeException exception) {
            log.warn("Paper cache prewarm failed, next scan or request will retry: examId={}, snapshotId={}",
                examId, snapshotId, exception);
            meterRegistry.counter("exam.cache.prewarm", "outcome", "failed").increment();
            return false;
        }
    }
}
