package com.ekusys.exam.runtime.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

public final class RuntimeTime {
    private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Shanghai");

    private RuntimeTime() {
    }

    public static long epochMillis(LocalDateTime value) {
        return value.atZone(DATABASE_ZONE).toInstant().toEpochMilli();
    }

    public static Long nullableEpochMillis(LocalDateTime value) {
        return value == null ? null : epochMillis(value);
    }

    public static LocalDateTime localDateTime(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), DATABASE_ZONE);
    }
}
