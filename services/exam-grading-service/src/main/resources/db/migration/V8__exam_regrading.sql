CREATE TABLE grading_exam_key (
 exam_id BIGINT PRIMARY KEY, current_version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE grading_key_version (
 exam_id BIGINT NOT NULL, version BIGINT NOT NULL, parent_version BIGINT, restored_from_version BIGINT,
 snapshot_id BIGINT NOT NULL, operator_id BIGINT, reason VARCHAR(1000) NOT NULL,
 answers_json JSON NOT NULL, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(exam_id,version)
);
CREATE TABLE regrade_job (
 id BIGINT PRIMARY KEY, exam_id BIGINT NOT NULL, answer_version BIGINT NOT NULL,
 request_key VARCHAR(100) NOT NULL, request_hash VARCHAR(64) NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'RUNNING', upper_bound BIGINT NOT NULL,
 total BIGINT NOT NULL, scan_attempts INT NOT NULL DEFAULT 0, cursor_id BIGINT NOT NULL DEFAULT 0, scan_complete BOOLEAN NOT NULL DEFAULT FALSE,
 lease_token VARCHAR(64), lease_until DATETIME(3), last_error VARCHAR(255),
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_regrade_request(exam_id,request_key), KEY idx_regrade_work(status,lease_until)
);
CREATE TABLE regrade_item (
 job_id BIGINT NOT NULL, submission_id BIGINT NOT NULL, student_id BIGINT,
 status VARCHAR(24) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0,
 before_json JSON, after_json JSON, grade_revision BIGINT, grade_status VARCHAR(32),
 projection_synced BOOLEAN NOT NULL DEFAULT FALSE, last_error VARCHAR(255),
 PRIMARY KEY(job_id,submission_id)
);
CREATE TABLE regrade_bank_sync (
 id BIGINT PRIMARY KEY, job_id BIGINT NOT NULL, exam_id BIGINT NOT NULL, answer_version BIGINT NOT NULL,
 question_id BIGINT NOT NULL, answer TEXT NOT NULL, expected_fingerprint VARCHAR(64),
 operation_id VARCHAR(64) NOT NULL, operator_id BIGINT NOT NULL, administrator BOOLEAN NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0,
 next_retry_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), last_error VARCHAR(255),
 UNIQUE KEY uk_regrade_bank(job_id,question_id), KEY idx_bank_work(status,next_retry_at)
);
ALTER TABLE grade_result ADD COLUMN answer_version BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN grade_revision BIGINT NOT NULL DEFAULT 0;
