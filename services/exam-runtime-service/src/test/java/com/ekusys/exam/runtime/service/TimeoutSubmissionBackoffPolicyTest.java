package com.ekusys.exam.runtime.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ekusys.exam.runtime.config.TimeoutSubmissionProperties;
import org.junit.jupiter.api.Test;

class TimeoutSubmissionBackoffPolicyTest {

    @Test
    void doublesDelayAndCapsAtConfiguredMaximum() {
        TimeoutSubmissionProperties properties = new TimeoutSubmissionProperties();
        properties.setBackoffInitialMs(1_000L);
        properties.setBackoffMultiplier(2.0);
        properties.setBackoffMaxMs(30_000L);
        properties.setBackoffJitter(0.0);
        TimeoutSubmissionBackoffPolicy policy = new TimeoutSubmissionBackoffPolicy(properties);

        assertThat(policy.delayMillis(1)).isEqualTo(1_000L);
        assertThat(policy.delayMillis(2)).isEqualTo(2_000L);
        assertThat(policy.delayMillis(6)).isEqualTo(30_000L);
        assertThat(policy.delayMillis(20)).isEqualTo(30_000L);
    }
}
