CREATE TABLE IF NOT EXISTS exam (id BIGINT PRIMARY KEY, name VARCHAR(128) NOT NULL, paper_id BIGINT NOT NULL,
 start_time DATETIME(3) NOT NULL, end_time DATETIME(3) NOT NULL, duration_minutes INT NOT NULL, pass_score INT NOT NULL,
 status VARCHAR(16) NOT NULL, publisher_id BIGINT, proctoring_level VARCHAR(32) NOT NULL DEFAULT 'STANDARD',
 proctoring_config_json TEXT, create_time DATETIME(3), update_time DATETIME(3), create_by BIGINT, update_by BIGINT);
CREATE TABLE IF NOT EXISTS exam_target_class (id BIGINT PRIMARY KEY, exam_id BIGINT NOT NULL, class_id BIGINT NOT NULL,
 create_time DATETIME(3), update_time DATETIME(3), create_by BIGINT, update_by BIGINT, UNIQUE KEY uk_exam_class(exam_id, class_id));
CREATE TABLE IF NOT EXISTS exam_candidate (id BIGINT PRIMARY KEY, exam_id BIGINT NOT NULL, student_id BIGINT NOT NULL,
 class_id BIGINT NOT NULL, roster_version BIGINT NOT NULL, created_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_exam_candidate(exam_id, student_id));
CREATE TABLE IF NOT EXISTS exam_paper_ref (id BIGINT PRIMARY KEY, exam_id BIGINT NOT NULL UNIQUE, paper_snapshot_id BIGINT NOT NULL,
 snapshot_version BIGINT NOT NULL, created_at DATETIME(3) NOT NULL);
