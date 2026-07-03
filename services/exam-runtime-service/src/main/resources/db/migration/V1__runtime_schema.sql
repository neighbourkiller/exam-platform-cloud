CREATE TABLE IF NOT EXISTS exam_session (
    id BIGINT PRIMARY KEY,
    exam_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    start_time DATETIME(3),
    deadline_time DATETIME(3),
    end_time DATETIME(3),
    last_snapshot_time DATETIME(3),
    claim_time DATETIME(3),
    create_time DATETIME(3), update_time DATETIME(3), create_by BIGINT, update_by BIGINT,
    UNIQUE KEY uk_exam_student_session(exam_id, student_id),
    KEY idx_session_timeout(status, deadline_time, claim_time)
);

CREATE TABLE IF NOT EXISTS submission (
    id BIGINT PRIMARY KEY,
    exam_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    paper_snapshot_id BIGINT,
    submitted_at DATETIME(3),
    timeout_submit TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME(3), update_time DATETIME(3), create_by BIGINT, update_by BIGINT,
    UNIQUE KEY uk_exam_student_submission(exam_id, student_id)
);

CREATE TABLE IF NOT EXISTS submission_answer (
    id BIGINT PRIMARY KEY, submission_id BIGINT NOT NULL, question_id BIGINT NOT NULL,
    answer_text TEXT, final_answer TINYINT, source VARCHAR(32),
    create_time DATETIME(3), update_time DATETIME(3), create_by BIGINT, update_by BIGINT,
    KEY idx_submission_answer_submission(submission_id)
);

CREATE TABLE IF NOT EXISTS anti_cheat_event (
    id BIGINT PRIMARY KEY, exam_id BIGINT NOT NULL, student_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL, event_time DATETIME(3) NOT NULL, duration_ms BIGINT,
    payload TEXT, evidence_json TEXT,
    create_time DATETIME(3), update_time DATETIME(3), create_by BIGINT, update_by BIGINT,
    KEY idx_anti_cheat_exam_student_time(exam_id, student_id, event_time)
);

CREATE TABLE IF NOT EXISTS proctoring_disposition (
    id BIGINT PRIMARY KEY, exam_id BIGINT NOT NULL, student_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL, remark VARCHAR(500), handled_by BIGINT, handled_at DATETIME(3),
    create_time DATETIME(3), update_time DATETIME(3), create_by BIGINT, update_by BIGINT,
    UNIQUE KEY uk_proctoring_exam_student(exam_id, student_id)
);
