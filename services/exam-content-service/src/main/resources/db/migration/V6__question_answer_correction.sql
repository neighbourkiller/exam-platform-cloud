CREATE TABLE question_answer_correction (
 operation_id VARCHAR(64) PRIMARY KEY, question_id BIGINT NOT NULL, operator_id BIGINT NOT NULL,
 request_hash VARCHAR(64) NOT NULL, previous_answer TEXT, corrected_answer TEXT NOT NULL,
 status VARCHAR(24) NOT NULL, message VARCHAR(255) NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
