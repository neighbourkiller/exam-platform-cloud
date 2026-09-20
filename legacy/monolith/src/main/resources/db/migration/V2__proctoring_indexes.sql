CREATE INDEX idx_anti_cheat_event_exam_time
    ON anti_cheat_event (exam_id, event_time);

CREATE INDEX idx_anti_cheat_event_exam_student_time
    ON anti_cheat_event (exam_id, student_id, event_time);
