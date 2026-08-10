package com.ekusys.exam.runtime.entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PaperCacheWarmScheduler {
    private static final Logger log = LoggerFactory.getLogger(PaperCacheWarmScheduler.class);

    private final RuntimeExamDefinitionRepository definitions;
    private final PaperDeliveryCache cache;

    public PaperCacheWarmScheduler(RuntimeExamDefinitionRepository definitions,
                                   PaperDeliveryCache cache) {
        this.definitions = definitions;
        this.cache = cache;
    }

    @Scheduled(fixedDelayString = "${app.exam-entry.cache-warm-interval-ms:60000}")
    public void warmUpcomingPapers() {
        for (Long snapshotId : definitions.readySnapshotsStartingWithinMinutes(10)) {
            try {
                cache.prewarm(snapshotId);
            } catch (RuntimeException exception) {
                log.warn("Upcoming paper prewarm failed: snapshotId={}", snapshotId, exception);
            }
        }
    }
}
