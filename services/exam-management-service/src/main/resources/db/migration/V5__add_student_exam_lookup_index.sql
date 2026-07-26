CREATE INDEX idx_exam_candidate_student_exam
    ON exam_candidate(student_id, exam_id);
