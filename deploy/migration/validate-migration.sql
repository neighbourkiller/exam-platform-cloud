-- Every query must return zero mismatches (or equal source/target values) before cutover.
SELECT 'sys_user' check_name,(SELECT COUNT(*) FROM exam_mvp.sys_user) source_value,
       (SELECT COUNT(*) FROM exam_iam.sys_user) target_value;
SELECT 'subject' check_name,(SELECT COUNT(*) FROM exam_mvp.subject) source_value,
       (SELECT COUNT(*) FROM exam_academic.subject) target_value;
SELECT 'question' check_name,(SELECT COUNT(*) FROM exam_mvp.question) source_value,
       (SELECT COUNT(*) FROM exam_content.question) target_value;
SELECT 'paper' check_name,(SELECT COUNT(*) FROM exam_mvp.paper) source_value,
       (SELECT COUNT(*) FROM exam_content.paper) target_value;
SELECT 'exam' check_name,(SELECT COUNT(*) FROM exam_mvp.exam) source_value,
       (SELECT COUNT(*) FROM exam_management.exam) target_value;
SELECT 'submission' check_name,(SELECT COUNT(*) FROM exam_mvp.submission) source_value,
       (SELECT COUNT(*) FROM exam_runtime.submission) target_value;
SELECT 'submission_answer' check_name,(SELECT COUNT(*) FROM exam_mvp.submission_answer) source_value,
       (SELECT COUNT(*) FROM exam_runtime.submission_answer) target_value;

SELECT 'user_pk_missing' check_name,COUNT(*) mismatch_count FROM (
    SELECT id FROM (SELECT id FROM exam_mvp.sys_user UNION ALL SELECT id FROM exam_iam.sys_user) valueset
    GROUP BY id HAVING COUNT(*)<>2
) mismatches;
SELECT 'exam_pk_missing' check_name,COUNT(*) mismatch_count FROM (
    SELECT id FROM (SELECT id FROM exam_mvp.exam UNION ALL SELECT id FROM exam_management.exam) valueset
    GROUP BY id HAVING COUNT(*)<>2
) mismatches;
SELECT 'submission_pk_missing' check_name,COUNT(*) mismatch_count FROM (
    SELECT id FROM (SELECT id FROM exam_mvp.submission UNION ALL SELECT id FROM exam_runtime.submission) valueset
    GROUP BY id HAVING COUNT(*)<>2
) mismatches;

SELECT 'grade_result_missing' check_name,COUNT(*) mismatch_count
FROM exam_mvp.submission s
LEFT JOIN exam_grading.grade_result g ON g.runtime_submission_id=s.id
WHERE s.status<>'IN_PROGRESS' AND g.id IS NULL;
SELECT 'grading_submission_missing' check_name,COUNT(*) mismatch_count
FROM exam_mvp.submission s
LEFT JOIN exam_grading.grading_submission g ON g.runtime_submission_id=s.id
WHERE s.status<>'IN_PROGRESS' AND g.id IS NULL;
SELECT 'score_total_difference' check_name,
       COALESCE((SELECT SUM(total_score) FROM exam_mvp.submission WHERE status<>'IN_PROGRESS'),0) source_value,
       COALESCE((SELECT SUM(total_score) FROM exam_grading.grade_result),0) target_value;
SELECT 'objective_score_difference' check_name,
       COALESCE((SELECT SUM(objective_score) FROM exam_mvp.submission WHERE status<>'IN_PROGRESS'),0) source_value,
       COALESCE((SELECT SUM(objective_score) FROM exam_grading.grade_result),0) target_value;
SELECT 'subjective_score_difference' check_name,
       COALESCE((SELECT SUM(subjective_score) FROM exam_mvp.submission WHERE status<>'IN_PROGRESS'),0) source_value,
       COALESCE((SELECT SUM(subjective_score) FROM exam_grading.grade_result),0) target_value;

SELECT 'published_exam_snapshot_missing' check_name,COUNT(*) mismatch_count
FROM exam_mvp.exam e
LEFT JOIN exam_management.exam_paper_ref r ON r.exam_id=e.id
LEFT JOIN exam_content.paper_snapshot p ON p.id=r.paper_snapshot_id
WHERE e.status<>'DRAFT' AND p.id IS NULL;
SELECT 'published_exam_candidate_difference' check_name,e.id exam_id,
       COUNT(DISTINCT stc.student_id) source_value,COUNT(DISTINCT c.student_id) target_value
FROM exam_mvp.exam e
JOIN exam_mvp.exam_target_class etc ON etc.exam_id=e.id
JOIN exam_mvp.student_teaching_class stc ON stc.teaching_class_id=etc.class_id AND stc.enroll_status='ACTIVE'
LEFT JOIN exam_management.exam_candidate c ON c.exam_id=e.id AND c.student_id=stc.student_id
WHERE e.status<>'DRAFT'
GROUP BY e.id HAVING source_value<>target_value;

SELECT 'orphan_runtime_answer' check_name,COUNT(*) mismatch_count
FROM exam_runtime.submission_answer a LEFT JOIN exam_runtime.submission s ON s.id=a.submission_id WHERE s.id IS NULL;
SELECT 'orphan_grading_result' check_name,COUNT(*) mismatch_count
FROM exam_grading.grade_result g LEFT JOIN exam_runtime.submission s ON s.id=g.runtime_submission_id WHERE s.id IS NULL;
SELECT 'orphan_exam_candidate' check_name,COUNT(*) mismatch_count
FROM exam_management.exam_candidate c LEFT JOIN exam_management.exam e ON e.id=c.exam_id WHERE e.id IS NULL;
SELECT 'reporting_score_missing' check_name,COUNT(*) mismatch_count
FROM exam_grading.grade_result g LEFT JOIN exam_reporting.rpt_student_score r ON r.submission_id=g.runtime_submission_id
WHERE r.submission_id IS NULL;
