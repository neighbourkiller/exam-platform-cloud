CREATE TABLE IF NOT EXISTS grading_submission (id BIGINT PRIMARY KEY, runtime_submission_id BIGINT NOT NULL UNIQUE,
 exam_id BIGINT NOT NULL, student_id BIGINT NOT NULL, paper_snapshot_id BIGINT NOT NULL, status VARCHAR(32) NOT NULL,
 submitted_at DATETIME(3) NOT NULL, create_time DATETIME(3), update_time DATETIME(3));
CREATE TABLE IF NOT EXISTS grading_task (id BIGINT PRIMARY KEY, grading_submission_id BIGINT NOT NULL, question_id BIGINT NOT NULL,
 answer_id BIGINT NOT NULL, max_score INT NOT NULL, status VARCHAR(32) NOT NULL, assigned_teacher_id BIGINT,
 create_time DATETIME(3), update_time DATETIME(3), UNIQUE KEY uk_grading_answer(answer_id));
CREATE TABLE IF NOT EXISTS subjective_grade (id BIGINT PRIMARY KEY, grading_task_id BIGINT NOT NULL, teacher_id BIGINT NOT NULL,
 score INT NOT NULL, comment VARCHAR(255), graded_at DATETIME(3) NOT NULL, create_time DATETIME(3), update_time DATETIME(3));
CREATE TABLE IF NOT EXISTS grade_result (id BIGINT PRIMARY KEY, runtime_submission_id BIGINT NOT NULL UNIQUE,
 objective_score INT NOT NULL DEFAULT 0, subjective_score INT NOT NULL DEFAULT 0, total_score INT NOT NULL DEFAULT 0,
 pass_flag TINYINT NOT NULL DEFAULT 0, status VARCHAR(32) NOT NULL, completed_at DATETIME(3), create_time DATETIME(3), update_time DATETIME(3));
