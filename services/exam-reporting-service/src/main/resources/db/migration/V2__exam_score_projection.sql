CREATE TABLE IF NOT EXISTS rpt_exam (
    exam_id BIGINT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    subject_id BIGINT,
    subject_name VARCHAR(128),
    start_time DATETIME(3) NOT NULL,
    end_time DATETIME(3) NOT NULL,
    duration_minutes INT NOT NULL,
    pass_score INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    publisher_id BIGINT,
    updated_at DATETIME(3) NOT NULL
);

CREATE TABLE IF NOT EXISTS rpt_exam_candidate (
    exam_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    class_id BIGINT,
    class_name VARCHAR(128),
    student_no VARCHAR(64),
    username VARCHAR(64),
    student_name VARCHAR(64),
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY(exam_id, student_id),
    KEY idx_candidate_student(student_id, exam_id)
);
