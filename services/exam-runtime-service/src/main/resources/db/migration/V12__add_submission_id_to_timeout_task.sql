ALTER TABLE submission_timeout_task
    ADD COLUMN submission_id BIGINT NULL COMMENT '关联考试提交记录 ID';

UPDATE submission_timeout_task t
JOIN submission s
  ON s.exam_id = t.exam_id
 AND s.student_id = t.student_id
SET t.submission_id = s.id
WHERE t.submission_id IS NULL;
