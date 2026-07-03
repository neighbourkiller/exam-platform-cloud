CREATE TABLE IF NOT EXISTS rpt_student_score (submission_id BIGINT PRIMARY KEY, exam_id BIGINT NOT NULL, student_id BIGINT NOT NULL,
 student_name VARCHAR(64), class_names_json JSON, status VARCHAR(32) NOT NULL, objective_score INT, subjective_score INT,
 total_score INT, pass_flag TINYINT, submitted_at DATETIME(3), updated_at DATETIME(3) NOT NULL,
 KEY idx_rpt_exam_score(exam_id, total_score));
CREATE TABLE IF NOT EXISTS rpt_proctoring_student (id BIGINT PRIMARY KEY, exam_id BIGINT NOT NULL, student_id BIGINT NOT NULL,
 student_name VARCHAR(64), class_names_json JSON, session_status VARCHAR(32), submission_status VARCHAR(32),
 event_count INT NOT NULL DEFAULT 0, last_event_time DATETIME(3), updated_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_rpt_exam_student(exam_id, student_id));
CREATE TABLE IF NOT EXISTS operation_audit_log (id BIGINT PRIMARY KEY, operator_id BIGINT, operator_username VARCHAR(128),
 operator_roles VARCHAR(255), action VARCHAR(64) NOT NULL, target_type VARCHAR(64) NOT NULL, target_id VARCHAR(128),
 request_method VARCHAR(16), request_path VARCHAR(255), request_ip VARCHAR(64), detail TEXT, status VARCHAR(16) NOT NULL,
 error_message VARCHAR(500), operate_time DATETIME(3) NOT NULL, create_time DATETIME(3), update_time DATETIME(3),
 create_by BIGINT, update_by BIGINT, KEY idx_audit_operator_time(operator_id,operate_time),
 KEY idx_audit_action_time(action,operate_time));
CREATE TABLE IF NOT EXISTS inbox_event (event_id VARCHAR(36) NOT NULL, consumer_name VARCHAR(64) NOT NULL,
 processed_at DATETIME(3) NOT NULL, PRIMARY KEY(event_id, consumer_name));
