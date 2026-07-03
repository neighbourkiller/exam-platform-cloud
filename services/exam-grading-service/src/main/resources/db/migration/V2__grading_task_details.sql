ALTER TABLE grading_submission ADD COLUMN exam_name VARCHAR(128) AFTER exam_id,
 ADD COLUMN pass_score INT NOT NULL DEFAULT 0 AFTER exam_name;
ALTER TABLE grading_task ADD COLUMN exam_id BIGINT NOT NULL AFTER grading_submission_id,
 ADD COLUMN student_id BIGINT NOT NULL AFTER exam_id, ADD COLUMN exam_name VARCHAR(128) AFTER student_id,
 ADD COLUMN question_content TEXT AFTER question_id, ADD COLUMN reference_answer TEXT AFTER question_content,
 ADD COLUMN analysis TEXT AFTER reference_answer, ADD COLUMN answer_text TEXT AFTER analysis,
 ADD COLUMN submitted_at DATETIME(3) AFTER answer_text, ADD COLUMN sort_order INT AFTER submitted_at;
