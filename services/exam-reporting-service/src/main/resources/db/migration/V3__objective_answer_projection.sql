CREATE TABLE IF NOT EXISTS rpt_objective_answer (
    submission_id BIGINT NOT NULL,
    exam_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    question_content TEXT,
    correct_flag TINYINT NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY(submission_id, question_id),
    KEY idx_rpt_answer_exam_question(exam_id, question_id)
);
