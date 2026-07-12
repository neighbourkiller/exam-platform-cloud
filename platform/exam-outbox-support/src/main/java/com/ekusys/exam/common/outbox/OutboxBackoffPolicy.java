package com.ekusys.exam.common.outbox;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class OutboxBackoffPolicy {
    private final OutboxProperties properties;
    private final DoubleSupplier random;

    @Autowired
    public OutboxBackoffPolicy(OutboxProperties properties) {
        this(properties, () -> ThreadLocalRandom.current().nextDouble());
    }

    OutboxBackoffPolicy(OutboxProperties properties, DoubleSupplier random) {
        this.properties = properties;
        this.random = random;
    }

    public long delayMillis(int failureCount) {
        double multiplier = Math.max(1.0, properties.getBackoffMultiplier());
        double delay = Math.max(1, properties.getBackoffInitialMs());
        long max = Math.max(1, properties.getBackoffMaxMs());
        for (int attempt = 1; attempt < Math.max(1, failureCount) && delay < max; attempt++) {
            delay = Math.min(max, delay * multiplier);
        }
        double jitter = Math.max(0, Math.min(1, properties.getBackoffJitter()));
        double factor = 1 - jitter + random.getAsDouble() * jitter * 2;
        return Math.max(1, Math.min(max, Math.round(delay * factor)));
    }
}
