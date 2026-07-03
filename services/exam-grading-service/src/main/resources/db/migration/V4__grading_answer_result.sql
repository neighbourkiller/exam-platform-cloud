CREATE TABLE IF NOT EXISTS grading_answer_result (
    id BIGINT PRIMARY KEY,
    runtime_submission_id BIGINT NOT NULL,
    exam_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    question_content TEXT,
    objective_flag TINYINT NOT NULL,
    correct_flag TINYINT,
    earned_score INT NOT NULL DEFAULT 0,
    max_score INT NOT NULL,
    create_time DATETIME(3) NOT NULL,
    update_time DATETIME(3) NOT NULL,
    UNIQUE KEY uk_grading_answer_submission_question(runtime_submission_id, question_id),
    KEY idx_grading_answer_exam_question(exam_id, question_id)
);
