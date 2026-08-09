package com.ekusys.exam.runtime.service;

import com.ekusys.exam.runtime.config.TimeoutSubmissionProperties;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

@Component
public class TimeoutSubmissionBackoffPolicy {
    private final TimeoutSubmissionProperties properties;

    public TimeoutSubmissionBackoffPolicy(TimeoutSubmissionProperties properties) {
        this.properties = properties;
    }

    public long delayMillis(int attempt) {
        int exponent = Math.max(0, attempt - 1);
        double raw = properties.safeBackoffInitialMs()
            * Math.pow(properties.safeBackoffMultiplier(), exponent);
        long capped = Math.min(properties.safeBackoffMaxMs(), Math.round(raw));
        double jitter = properties.safeBackoffJitter();
        if (jitter == 0.0) {
            return capped;
        }
        double factor = ThreadLocalRandom.current().nextDouble(1.0 - jitter, 1.0 + jitter);
        return Math.max(1L, Math.round(capped * factor));
    }
}
