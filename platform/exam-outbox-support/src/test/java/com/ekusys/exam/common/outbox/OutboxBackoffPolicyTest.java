package com.ekusys.exam.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OutboxBackoffPolicyTest {

    @Test
    void appliesExponentialBackoffJitterAndCap() {
        OutboxProperties properties = new OutboxProperties();
        OutboxBackoffPolicy lower = new OutboxBackoffPolicy(properties, () -> 0.0);
        OutboxBackoffPolicy middle = new OutboxBackoffPolicy(properties, () -> 0.5);
        OutboxBackoffPolicy upper = new OutboxBackoffPolicy(properties, () -> 1.0);

        assertThat(lower.delayMillis(1)).isEqualTo(4_000);
        assertThat(middle.delayMillis(2)).isEqualTo(10_000);
        assertThat(upper.delayMillis(3)).isEqualTo(24_000);
        assertThat(upper.delayMillis(20)).isEqualTo(300_000);
    }
}
