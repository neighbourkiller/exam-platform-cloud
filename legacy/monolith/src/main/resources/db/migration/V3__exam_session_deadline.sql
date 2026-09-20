ALTER TABLE exam_session
    ADD COLUMN deadline_time DATETIME NULL AFTER start_time;

CREATE INDEX idx_exam_session_status_deadline
    ON exam_session (status, deadline_time);
