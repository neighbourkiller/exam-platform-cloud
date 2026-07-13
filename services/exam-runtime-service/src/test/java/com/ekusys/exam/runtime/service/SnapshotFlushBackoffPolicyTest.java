package com.ekusys.exam.runtime.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ekusys.exam.runtime.config.SnapshotProperties;
import org.junit.jupiter.api.Test;

class SnapshotFlushBackoffPolicyTest {

    @Test
    void appliesExponentialBackoffJitterAndCap() {
        SnapshotProperties properties = new SnapshotProperties();

        assertThat(new SnapshotFlushBackoffPolicy(properties, () -> 0.0).delayMillis(1))
            .isEqualTo(4_000L);
        assertThat(new SnapshotFlushBackoffPolicy(properties, () -> 0.5).delayMillis(2))
            .isEqualTo(10_000L);
        assertThat(new SnapshotFlushBackoffPolicy(properties, () -> 1.0).delayMillis(3))
            .isEqualTo(24_000L);
        assertThat(new SnapshotFlushBackoffPolicy(properties, () -> 1.0).delayMillis(20))
            .isEqualTo(300_000L);
    }
}
