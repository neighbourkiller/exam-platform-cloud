ALTER TABLE rpt_student_score ADD COLUMN grade_revision BIGINT NOT NULL DEFAULT -1,
 ADD COLUMN answer_version BIGINT NOT NULL DEFAULT 0;
UPDATE rpt_student_score SET grade_revision=0 WHERE status='GRADED';
