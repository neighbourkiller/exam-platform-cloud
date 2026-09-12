package com.ekusys.exam.runtime.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

class RuntimeTimeTest {
    @Test
    void epochConversionDoesNotDependOnJvmDefaultTimezone() {
        TimeZone original = TimeZone.getDefault();
        try {
            LocalDateTime databaseTime = LocalDateTime.of(2026, 9, 1, 12, 0);
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            long utcJvm = RuntimeTime.epochMillis(databaseTime);
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            long losAngelesJvm = RuntimeTime.epochMillis(databaseTime);

            assertThat(losAngelesJvm).isEqualTo(utcJvm);
            assertThat(RuntimeTime.localDateTime(utcJvm)).isEqualTo(databaseTime);
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
