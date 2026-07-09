package com.ekusys.exam.importing;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class CsvImportValues {
    private static final DateTimeFormatter SPACE_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private CsvImportValues() {
    }

    public static void require(CsvImportParser.CsvRow row, String... fields) {
        for (String field : fields) {
            if (row.value(field).isBlank()) {
                throw new RowImportException(field, "字段不能为空", row.value(field));
            }
        }
    }

    public static String nullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static Long longValue(String value, String field) {
        String normalized = nullable(value);
        if (normalized == null) {
            return null;
        }
        try {
            return Long.valueOf(normalized);
        } catch (NumberFormatException exception) {
            throw new RowImportException(field, "必须是整数", value);
        }
    }

    public static Integer intValue(String value, String field) {
        String normalized = nullable(value);
        if (normalized == null) {
            return null;
        }
        try {
            return Integer.valueOf(normalized);
        } catch (NumberFormatException exception) {
            throw new RowImportException(field, "必须是整数", value);
        }
    }

    public static LocalDateTime dateTime(String value, String field) {
        String normalized = nullable(value);
        if (normalized == null) {
            throw new RowImportException(field, "时间不能为空", value);
        }
        try {
            return LocalDateTime.parse(normalized);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(normalized, SPACE_DATE_TIME);
            } catch (DateTimeParseException exception) {
                throw new RowImportException(field, "时间格式应为 yyyy-MM-ddTHH:mm:ss 或 yyyy-MM-dd HH:mm:ss", value);
            }
        }
    }

    public static List<Long> longList(String value, String field) {
        String normalized = nullable(value);
        if (normalized == null) {
            return List.of();
        }
        return Arrays.stream(normalized.split("[,;，；]"))
            .map(String::trim)
            .filter(item -> !item.isEmpty())
            .map(item -> longValue(item, field))
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
            .stream().toList();
    }

    public static boolean booleanValue(String value) {
        String normalized = nullable(value);
        return normalized != null && List.of("true", "1", "yes", "y")
            .contains(normalized.toLowerCase(Locale.ROOT));
    }
}
