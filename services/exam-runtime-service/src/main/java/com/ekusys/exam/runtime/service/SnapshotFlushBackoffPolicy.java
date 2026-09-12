package com.ekusys.exam.runtime.service;

import com.ekusys.exam.runtime.config.SnapshotProperties;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import org.springframework.stereotype.Component;

@Component
public class SnapshotFlushBackoffPolicy {
    private final SnapshotProperties properties;
    private final DoubleSupplier random;

    public SnapshotFlushBackoffPolicy(SnapshotProperties properties) {
        this(properties, () -> ThreadLocalRandom.current().nextDouble());
    }

    SnapshotFlushBackoffPolicy(SnapshotProperties properties, DoubleSupplier random) {
        this.properties = properties;
        this.random = random;
    }

    public long delayMillis(int attempt) {
        double delay = properties.safeFlushBackoffInitialMs();
        double multiplier = Math.max(1.0, properties.getFlushBackoffMultiplier());
        long maximum = properties.safeFlushBackoffMaxMs();
        for (int index = 1; index < Math.max(1, attempt) && delay < maximum; index++) {
            delay = Math.min(maximum, delay * multiplier);
        }
        double jitter = Math.max(0.0, Math.min(1.0, properties.getFlushBackoffJitter()));
        double factor = 1.0 - jitter + random.getAsDouble() * jitter * 2.0;
        return Math.max(1L, Math.min(maximum, Math.round(delay * factor)));
    }
}
