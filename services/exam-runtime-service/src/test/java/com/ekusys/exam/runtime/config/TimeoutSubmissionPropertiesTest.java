package com.ekusys.exam.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TimeoutSubmissionPropertiesTest {

    @Test
    void defaultsLeaveRecoveryHeadroomInsideCompletionSla() {
        TimeoutSubmissionProperties properties = new TimeoutSubmissionProperties();

        assertThat(properties.safeLeaseMs()).isEqualTo(30_000L);
        assertThat(properties.safeLeaseRenewIntervalMs()).isEqualTo(10_000L);
        assertThat(properties.safeTaskTimeoutMs()).isEqualTo(20_000L);
        assertThat(properties.safeBacklogRefreshIntervalMs()).isEqualTo(10_000L);
        assertThat(properties.safeLeaseMs()).isLessThan(60_000L);
        assertThat(properties.safeLeaseMs()).isGreaterThan(properties.safeTaskTimeoutMs());
    }

    @Test
    void backlogRefreshIntervalHasOneSecondLowerBound() {
        TimeoutSubmissionProperties properties = new TimeoutSubmissionProperties();
        properties.setBacklogRefreshIntervalMs(0L);

        assertThat(properties.safeBacklogRefreshIntervalMs()).isEqualTo(1_000L);
    }

    @Test
    void unsafeRenewIntervalIsCappedAtHalfOfLease() {
        TimeoutSubmissionProperties properties = new TimeoutSubmissionProperties();
        properties.setLeaseMs(30_000L);
        properties.setLeaseRenewIntervalMs(29_000L);

        assertThat(properties.safeLeaseRenewIntervalMs()).isEqualTo(15_000L);
    }

    @Test
    void pathologicalDurationsStillKeepWorkAndRenewalInsideLease() {
        TimeoutSubmissionProperties properties = new TimeoutSubmissionProperties();
        properties.setLeaseMs(1L);
        properties.setTaskTimeoutMs(30_000L);
        properties.setLeaseRenewIntervalMs(30_000L);

        assertThat(properties.safeLeaseMs()).isEqualTo(2_000L);
        assertThat(properties.safeTaskTimeoutMs()).isEqualTo(1_999L);
        assertThat(properties.safeLeaseRenewIntervalMs()).isEqualTo(1_000L);
    }
}
