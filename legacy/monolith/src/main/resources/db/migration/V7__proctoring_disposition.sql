CREATE TABLE IF NOT EXISTS proctoring_disposition (
    id BIGINT PRIMARY KEY,
    exam_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    remark VARCHAR(500),
    handled_by BIGINT,
    handled_at DATETIME,
    create_time DATETIME,
    update_time DATETIME,
    create_by BIGINT,
    update_by BIGINT,
    UNIQUE KEY uq_proctoring_disposition_exam_student (exam_id, student_id),
    KEY idx_proctoring_disposition_exam_status (exam_id, status),
    KEY idx_proctoring_disposition_handler_time (handled_by, handled_at)
);
