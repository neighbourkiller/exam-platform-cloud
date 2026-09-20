package com.ekusys.exam.common.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class LegacySchemaCompatibilityInitializer {

    private static final Logger log = LoggerFactory.getLogger(LegacySchemaCompatibilityInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    public LegacySchemaCompatibilityInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void ensureCompatibility() {
        warnIfFlywayHistoryMissing();
        ensureExamSessionDeadlineColumn();
        ensureExamSessionDeadlineIndex();
        ensureExamProctoringPolicyColumns();
        ensureAntiCheatIndexes();
    }

    private void warnIfFlywayHistoryMissing() {
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM information_schema.TABLES
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'flyway_schema_history'
            """, Integer.class);
        if (count != null && count > 0) {
            return;
        }
        log.warn("flyway_schema_history is missing; applying compatibility repairs for legacy schema");
    }

    private void ensureExamSessionDeadlineColumn() {
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'exam_session'
              AND COLUMN_NAME = 'deadline_time'
            """, Integer.class);
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.execute("ALTER TABLE exam_session ADD COLUMN deadline_time DATETIME NULL AFTER start_time");
        log.warn("Legacy schema repaired: added exam_session.deadline_time because Flyway history was missing or outdated");
    }

    private void ensureExamSessionDeadlineIndex() {
        ensureIndex(
            "exam_session",
            "idx_exam_session_status_deadline",
            "CREATE INDEX idx_exam_session_status_deadline ON exam_session (status, deadline_time)"
        );
    }

    private void ensureExamProctoringPolicyColumns() {
        ensureColumn(
            "exam",
            "proctoring_level",
            "ALTER TABLE exam ADD COLUMN proctoring_level VARCHAR(32) NOT NULL DEFAULT 'STANDARD' AFTER publisher_id"
        );
        ensureColumn(
            "exam",
            "proctoring_config_json",
            "ALTER TABLE exam ADD COLUMN proctoring_config_json TEXT NULL AFTER proctoring_level"
        );
    }

    private void ensureAntiCheatIndexes() {
        ensureIndex(
            "anti_cheat_event",
            "idx_anti_cheat_event_exam_time",
            "CREATE INDEX idx_anti_cheat_event_exam_time ON anti_cheat_event (exam_id, event_time)"
        );
        ensureIndex(
            "anti_cheat_event",
            "idx_anti_cheat_event_exam_student_time",
            "CREATE INDEX idx_anti_cheat_event_exam_student_time ON anti_cheat_event (exam_id, student_id, event_time)"
        );
    }

    private void ensureIndex(String tableName, String indexName, String ddl) {
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = ?
              AND INDEX_NAME = ?
            """, Integer.class, tableName, indexName);
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.execute(ddl);
        log.warn("Legacy schema repaired: added {}.{}", tableName, indexName);
    }

    private void ensureColumn(String tableName, String columnName, String ddl) {
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = ?
              AND COLUMN_NAME = ?
            """, Integer.class, tableName, columnName);
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.execute(ddl);
        log.warn("Legacy schema repaired: added {}.{}", tableName, columnName);
    }
}
