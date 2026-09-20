SET @ddl = IF (
    EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'exam_session'
          AND COLUMN_NAME = 'deadline_time'
    ),
    'SELECT 1',
    'ALTER TABLE exam_session ADD COLUMN deadline_time DATETIME NULL AFTER start_time'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF (
    EXISTS (
        SELECT 1
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'exam_session'
          AND INDEX_NAME = 'idx_exam_session_status_deadline'
    ),
    'SELECT 1',
    'CREATE INDEX idx_exam_session_status_deadline ON exam_session (status, deadline_time)'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF (
    EXISTS (
        SELECT 1
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'anti_cheat_event'
          AND INDEX_NAME = 'idx_anti_cheat_event_exam_time'
    ),
    'SELECT 1',
    'CREATE INDEX idx_anti_cheat_event_exam_time ON anti_cheat_event (exam_id, event_time)'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF (
    EXISTS (
        SELECT 1
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'anti_cheat_event'
          AND INDEX_NAME = 'idx_anti_cheat_event_exam_student_time'
    ),
    'SELECT 1',
    'CREATE INDEX idx_anti_cheat_event_exam_student_time ON anti_cheat_event (exam_id, student_id, event_time)'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
