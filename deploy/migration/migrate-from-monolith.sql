-- Execute after all new-service Flyway migrations have completed and the legacy writer is stopped.
-- The statements are idempotent by primary/unique key and can be rerun before traffic is opened.
SET FOREIGN_KEY_CHECKS = 0;

REPLACE INTO exam_iam.sys_user
    (id,username,password,real_name,enabled,token_version,create_time,update_time,create_by,update_by)
SELECT id,username,password,real_name,enabled,token_version,create_time,update_time,create_by,update_by
FROM exam_mvp.sys_user;
REPLACE INTO exam_iam.sys_role
    (id,code,name,create_time,update_time,create_by,update_by)
SELECT id,code,name,create_time,update_time,create_by,update_by FROM exam_mvp.sys_role;
REPLACE INTO exam_iam.sys_user_role
    (id,user_id,role_id,create_time,update_time,create_by,update_by)
SELECT id,user_id,role_id,create_time,update_time,create_by,update_by FROM exam_mvp.sys_user_role;

REPLACE INTO exam_academic.subject
    (id,name,description,create_time,update_time,create_by,update_by)
SELECT id,name,description,create_time,update_time,create_by,update_by FROM exam_mvp.subject;
REPLACE INTO exam_academic.student_profile
    (id,user_id,student_no,enrollment_year,status,create_time,update_time,create_by,update_by)
SELECT id,user_id,student_no,enrollment_year,status,create_time,update_time,create_by,update_by
FROM exam_mvp.student_profile;
REPLACE INTO exam_academic.teacher_profile
    (id,user_id,teacher_no,title,status,create_time,update_time,create_by,update_by)
SELECT id,user_id,teacher_no,title,status,create_time,update_time,create_by,update_by
FROM exam_mvp.teacher_profile;
INSERT INTO exam_academic.teaching_class
    (id,name,subject_id,teacher_id,term,status,capacity,roster_version,create_time,update_time,create_by,update_by)
SELECT id,name,subject_id,teacher_id,term,status,capacity,0,create_time,update_time,create_by,update_by
FROM exam_mvp.teaching_class
ON DUPLICATE KEY UPDATE name=VALUES(name),subject_id=VALUES(subject_id),teacher_id=VALUES(teacher_id),
    term=VALUES(term),status=VALUES(status),capacity=VALUES(capacity),update_time=VALUES(update_time);
REPLACE INTO exam_academic.student_teaching_class
    (id,student_id,subject_id,teaching_class_id,enroll_status,enrolled_at,dropped_at,
     create_time,update_time,create_by,update_by)
SELECT id,student_id,subject_id,teaching_class_id,enroll_status,enrolled_at,dropped_at,
       create_time,update_time,create_by,update_by
FROM exam_mvp.student_teaching_class;

REPLACE INTO exam_content.question
    (id,subject_id,type,difficulty,content,options_json,answer,analysis,default_score,creator_id,
     create_time,update_time,create_by,update_by)
SELECT id,subject_id,type,difficulty,content,options_json,answer,analysis,default_score,creator_id,
       create_time,update_time,create_by,update_by
FROM exam_mvp.question;
REPLACE INTO exam_content.question_asset
    (id,question_id,uploader_id,file_type,url,object_key,original_name,content_type,size,
     create_time,update_time,create_by,update_by)
SELECT id,question_id,uploader_id,file_type,url,object_key,original_name,content_type,size,
       create_time,update_time,create_by,update_by
FROM exam_mvp.question_asset;
INSERT INTO exam_content.paper
    (id,name,subject_id,description,total_score,teacher_id,version,create_time,update_time,create_by,update_by)
SELECT id,name,subject_id,description,total_score,teacher_id,0,create_time,update_time,create_by,update_by
FROM exam_mvp.paper
ON DUPLICATE KEY UPDATE name=VALUES(name),subject_id=VALUES(subject_id),description=VALUES(description),
    total_score=VALUES(total_score),teacher_id=VALUES(teacher_id),update_time=VALUES(update_time);
REPLACE INTO exam_content.paper_question
    (id,paper_id,question_id,score,sort_order,create_time,update_time,create_by,update_by)
SELECT id,paper_id,question_id,score,sort_order,create_time,update_time,create_by,update_by
FROM exam_mvp.paper_question;

-- Snapshot id is the legacy paper id for the one-time historical snapshot (version 1).
INSERT INTO exam_content.paper_snapshot(id,paper_id,version,name,subject_id,total_score,created_at)
SELECT p.id,p.id,1,p.name,p.subject_id,p.total_score,COALESCE(p.update_time,p.create_time,CURRENT_TIMESTAMP(3))
FROM exam_mvp.paper p
JOIN (SELECT DISTINCT paper_id FROM exam_mvp.exam WHERE status<>'DRAFT') used ON used.paper_id=p.id
ON DUPLICATE KEY UPDATE name=VALUES(name),subject_id=VALUES(subject_id),total_score=VALUES(total_score);
INSERT INTO exam_content.paper_snapshot_question
    (id,snapshot_id,question_id,type,difficulty,content,options_json,answer,analysis,score,sort_order)
SELECT pq.id,pq.paper_id,q.id,q.type,q.difficulty,q.content,q.options_json,q.answer,q.analysis,pq.score,pq.sort_order
FROM exam_mvp.paper_question pq
JOIN exam_mvp.question q ON q.id=pq.question_id
JOIN exam_content.paper_snapshot ps ON ps.id=pq.paper_id
ON DUPLICATE KEY UPDATE content=VALUES(content),options_json=VALUES(options_json),answer=VALUES(answer),
    analysis=VALUES(analysis),score=VALUES(score),sort_order=VALUES(sort_order);
INSERT INTO exam_content.paper_snapshot_asset
    (id,snapshot_id,question_id,source_asset_id,file_type,url,object_key,original_name,content_type,size)
SELECT CAST(CONV(SUBSTRING(SHA2(CONCAT(pq.paper_id,':',qa.id),256),1,15),16,10) AS UNSIGNED),
       pq.paper_id,qa.question_id,qa.id,qa.file_type,qa.url,qa.object_key,qa.original_name,qa.content_type,qa.size
FROM exam_mvp.question_asset qa
JOIN exam_mvp.paper_question pq ON pq.question_id=qa.question_id
JOIN exam_content.paper_snapshot ps ON ps.id=pq.paper_id
ON DUPLICATE KEY UPDATE url=VALUES(url),object_key=VALUES(object_key),original_name=VALUES(original_name),size=VALUES(size);

REPLACE INTO exam_management.exam
    (id,name,paper_id,start_time,end_time,duration_minutes,pass_score,status,publisher_id,
     proctoring_level,proctoring_config_json,create_time,update_time,create_by,update_by)
SELECT id,name,paper_id,start_time,end_time,duration_minutes,pass_score,status,publisher_id,
       proctoring_level,proctoring_config_json,create_time,update_time,create_by,update_by
FROM exam_mvp.exam;
REPLACE INTO exam_management.exam_target_class
    (id,exam_id,class_id,create_time,update_time,create_by,update_by)
SELECT id,exam_id,class_id,create_time,update_time,create_by,update_by FROM exam_mvp.exam_target_class;
INSERT INTO exam_management.exam_paper_ref(id,exam_id,paper_snapshot_id,snapshot_version,created_at)
SELECT e.id,e.id,e.paper_id,1,COALESCE(e.update_time,e.create_time,CURRENT_TIMESTAMP(3))
FROM exam_mvp.exam e WHERE e.status<>'DRAFT'
ON DUPLICATE KEY UPDATE paper_snapshot_id=VALUES(paper_snapshot_id),snapshot_version=VALUES(snapshot_version);
INSERT INTO exam_management.exam_candidate(id,exam_id,student_id,class_id,roster_version,created_at)
SELECT CAST(CONV(SUBSTRING(SHA2(CONCAT(e.id,':',stc.student_id),256),1,15),16,10) AS UNSIGNED),
       e.id,stc.student_id,etc.class_id,0,COALESCE(e.update_time,e.create_time,CURRENT_TIMESTAMP(3))
FROM exam_mvp.exam e
JOIN exam_mvp.exam_target_class etc ON etc.exam_id=e.id
JOIN exam_mvp.student_teaching_class stc ON stc.teaching_class_id=etc.class_id AND stc.enroll_status='ACTIVE'
WHERE e.status<>'DRAFT'
ON DUPLICATE KEY UPDATE class_id=VALUES(class_id),roster_version=VALUES(roster_version);

INSERT INTO exam_runtime.exam_session
    (id,exam_id,student_id,status,start_time,deadline_time,end_time,last_snapshot_time,claim_time,create_time,update_time,create_by,update_by)
SELECT id,exam_id,student_id,status,start_time,deadline_time,end_time,last_snapshot_time,NULL,
       create_time,update_time,create_by,update_by
FROM exam_mvp.exam_session
ON DUPLICATE KEY UPDATE status=VALUES(status),deadline_time=VALUES(deadline_time),end_time=VALUES(end_time),
    last_snapshot_time=VALUES(last_snapshot_time),update_time=VALUES(update_time);
INSERT INTO exam_runtime.submission
    (id,exam_id,student_id,status,paper_snapshot_id,submitted_at,timeout_submit,create_time,update_time,create_by,update_by)
SELECT s.id,s.exam_id,s.student_id,s.status,e.paper_id,s.submitted_at,0,s.create_time,s.update_time,s.create_by,s.update_by
FROM exam_mvp.submission s JOIN exam_mvp.exam e ON e.id=s.exam_id
ON DUPLICATE KEY UPDATE status=VALUES(status),paper_snapshot_id=VALUES(paper_snapshot_id),
    submitted_at=VALUES(submitted_at),update_time=VALUES(update_time);
INSERT INTO exam_runtime.submission_answer
    (id,submission_id,question_id,answer_text,final_answer,source,create_time,update_time,create_by,update_by)
SELECT id,submission_id,question_id,answer_text,final_answer,source,create_time,update_time,create_by,update_by
FROM exam_mvp.submission_answer
ON DUPLICATE KEY UPDATE answer_text=VALUES(answer_text),final_answer=VALUES(final_answer),
    source=VALUES(source),update_time=VALUES(update_time);
REPLACE INTO exam_runtime.anti_cheat_event
    (id,exam_id,student_id,event_type,event_time,duration_ms,payload,evidence_json,
     create_time,update_time,create_by,update_by)
SELECT id,exam_id,student_id,event_type,event_time,duration_ms,payload,evidence_json,
       create_time,update_time,create_by,update_by
FROM exam_mvp.anti_cheat_event;
REPLACE INTO exam_runtime.proctoring_disposition
    (id,exam_id,student_id,status,remark,handled_by,handled_at,create_time,update_time,create_by,update_by)
SELECT id,exam_id,student_id,status,remark,handled_by,handled_at,create_time,update_time,create_by,update_by
FROM exam_mvp.proctoring_disposition;

INSERT INTO exam_grading.grading_submission
    (id,runtime_submission_id,exam_id,exam_name,pass_score,student_id,paper_snapshot_id,status,submitted_at,create_time,update_time)
SELECT s.id,s.id,s.exam_id,e.name,e.pass_score,s.student_id,e.paper_id,
       CASE WHEN s.status='GRADED' THEN 'GRADED'
            WHEN EXISTS (SELECT 1 FROM exam_mvp.submission_answer a
                         JOIN exam_mvp.question q ON q.id=a.question_id
                         WHERE a.submission_id=s.id AND q.type NOT IN ('SINGLE','MULTI','JUDGE','BLANK'))
                 THEN 'PENDING_SUBJECTIVE' ELSE 'GRADED' END,
       COALESCE(s.submitted_at,s.update_time,s.create_time,CURRENT_TIMESTAMP(3)),s.create_time,s.update_time
FROM exam_mvp.submission s JOIN exam_mvp.exam e ON e.id=s.exam_id
WHERE s.status<>'IN_PROGRESS'
ON DUPLICATE KEY UPDATE status=VALUES(status),exam_name=VALUES(exam_name),pass_score=VALUES(pass_score),
    paper_snapshot_id=VALUES(paper_snapshot_id),update_time=VALUES(update_time);
INSERT INTO exam_grading.grading_task
    (id,grading_submission_id,exam_id,student_id,exam_name,question_id,question_content,reference_answer,
     analysis,answer_text,submitted_at,sort_order,answer_id,max_score,status,assigned_teacher_id,create_time,update_time)
SELECT a.id,s.id,s.exam_id,s.student_id,e.name,a.question_id,q.content,q.answer,q.analysis,a.answer_text,
       COALESCE(s.submitted_at,s.update_time),pq.sort_order,a.id,pq.score,
       CASE WHEN sg.id IS NULL THEN 'PENDING' ELSE 'GRADED' END,sg.teacher_id,a.create_time,a.update_time
FROM exam_mvp.submission_answer a
JOIN exam_mvp.submission s ON s.id=a.submission_id
JOIN exam_mvp.exam e ON e.id=s.exam_id
JOIN exam_mvp.question q ON q.id=a.question_id AND q.type NOT IN ('SINGLE','MULTI','JUDGE','BLANK')
JOIN exam_mvp.paper_question pq ON pq.paper_id=e.paper_id AND pq.question_id=a.question_id
LEFT JOIN exam_mvp.subjective_grade sg ON sg.submission_answer_id=a.id
WHERE s.status<>'IN_PROGRESS'
ON DUPLICATE KEY UPDATE answer_text=VALUES(answer_text),status=VALUES(status),
    assigned_teacher_id=VALUES(assigned_teacher_id),update_time=VALUES(update_time);
INSERT INTO exam_grading.subjective_grade
    (id,grading_task_id,teacher_id,score,comment,graded_at,create_time,update_time)
SELECT sg.id,sg.submission_answer_id,sg.teacher_id,sg.score,sg.comment,sg.graded_at,sg.create_time,sg.update_time
FROM exam_mvp.subjective_grade sg
JOIN exam_grading.grading_task gt ON gt.id=sg.submission_answer_id
ON DUPLICATE KEY UPDATE score=VALUES(score),comment=VALUES(comment),graded_at=VALUES(graded_at),update_time=VALUES(update_time);
INSERT INTO exam_grading.grade_result
    (id,runtime_submission_id,objective_score,subjective_score,total_score,pass_flag,status,completed_at,create_time,update_time)
SELECT s.id,s.id,COALESCE(s.objective_score,0),COALESCE(s.subjective_score,0),COALESCE(s.total_score,0),
       COALESCE(s.pass_flag,0),
       CASE WHEN s.status='GRADED' THEN 'GRADED' ELSE 'PENDING_SUBJECTIVE' END,
       CASE WHEN s.status='GRADED' THEN COALESCE(s.update_time,s.submitted_at) ELSE NULL END,s.create_time,s.update_time
FROM exam_mvp.submission s WHERE s.status<>'IN_PROGRESS'
ON DUPLICATE KEY UPDATE objective_score=VALUES(objective_score),subjective_score=VALUES(subjective_score),
    total_score=VALUES(total_score),pass_flag=VALUES(pass_flag),status=VALUES(status),
    completed_at=VALUES(completed_at),update_time=VALUES(update_time);
INSERT INTO exam_grading.grading_answer_result
    (id,runtime_submission_id,exam_id,student_id,question_id,question_content,objective_flag,correct_flag,
     earned_score,max_score,create_time,update_time)
SELECT a.id,a.submission_id,s.exam_id,s.student_id,a.question_id,q.content,1,
       COALESCE(a.objective_correct,0),COALESCE(a.objective_score,0),pq.score,a.create_time,a.update_time
FROM exam_mvp.submission_answer a
JOIN exam_mvp.submission s ON s.id=a.submission_id
JOIN exam_mvp.exam e ON e.id=s.exam_id
JOIN exam_mvp.question q ON q.id=a.question_id AND q.type IN ('SINGLE','MULTI','JUDGE','BLANK')
JOIN exam_mvp.paper_question pq ON pq.paper_id=e.paper_id AND pq.question_id=a.question_id
WHERE s.status<>'IN_PROGRESS'
ON DUPLICATE KEY UPDATE correct_flag=VALUES(correct_flag),earned_score=VALUES(earned_score),
    max_score=VALUES(max_score),update_time=VALUES(update_time);

INSERT INTO exam_reporting.rpt_exam
    (exam_id,name,subject_id,subject_name,start_time,end_time,duration_minutes,pass_score,status,publisher_id,updated_at)
SELECT e.id,e.name,p.subject_id,su.name,e.start_time,e.end_time,e.duration_minutes,e.pass_score,e.status,e.publisher_id,
       COALESCE(e.update_time,e.create_time,CURRENT_TIMESTAMP(3))
FROM exam_mvp.exam e JOIN exam_mvp.paper p ON p.id=e.paper_id LEFT JOIN exam_mvp.subject su ON su.id=p.subject_id
ON DUPLICATE KEY UPDATE name=VALUES(name),subject_id=VALUES(subject_id),subject_name=VALUES(subject_name),
    status=VALUES(status),updated_at=VALUES(updated_at);
INSERT INTO exam_reporting.rpt_exam_candidate
    (exam_id,student_id,class_id,class_name,student_no,username,student_name,updated_at)
SELECT ec.exam_id,ec.student_id,ec.class_id,tc.name,sp.student_no,u.username,u.real_name,CURRENT_TIMESTAMP(3)
FROM exam_management.exam_candidate ec
LEFT JOIN exam_mvp.teaching_class tc ON tc.id=ec.class_id
LEFT JOIN exam_mvp.student_profile sp ON sp.user_id=ec.student_id
LEFT JOIN exam_mvp.sys_user u ON u.id=ec.student_id
ON DUPLICATE KEY UPDATE class_id=VALUES(class_id),class_name=VALUES(class_name),student_no=VALUES(student_no),
    username=VALUES(username),student_name=VALUES(student_name),updated_at=VALUES(updated_at);
INSERT INTO exam_reporting.rpt_student_score
    (submission_id,exam_id,student_id,student_name,class_names_json,status,objective_score,subjective_score,
     total_score,pass_flag,submitted_at,updated_at)
SELECT s.id,s.exam_id,s.student_id,u.real_name,NULL,s.status,s.objective_score,s.subjective_score,s.total_score,
       s.pass_flag,s.submitted_at,COALESCE(s.update_time,s.create_time,CURRENT_TIMESTAMP(3))
FROM exam_mvp.submission s LEFT JOIN exam_mvp.sys_user u ON u.id=s.student_id
WHERE s.status<>'IN_PROGRESS'
ON DUPLICATE KEY UPDATE status=VALUES(status),objective_score=VALUES(objective_score),
    subjective_score=VALUES(subjective_score),total_score=VALUES(total_score),pass_flag=VALUES(pass_flag),
    submitted_at=VALUES(submitted_at),updated_at=VALUES(updated_at);
INSERT INTO exam_reporting.rpt_objective_answer
    (submission_id,exam_id,student_id,question_id,question_content,correct_flag,updated_at)
SELECT a.submission_id,s.exam_id,s.student_id,a.question_id,q.content,COALESCE(a.objective_correct,0),
       COALESCE(a.update_time,a.create_time,CURRENT_TIMESTAMP(3))
FROM exam_mvp.submission_answer a
JOIN exam_mvp.submission s ON s.id=a.submission_id
JOIN exam_mvp.question q ON q.id=a.question_id
WHERE a.objective_correct IS NOT NULL
ON DUPLICATE KEY UPDATE question_content=VALUES(question_content),correct_flag=VALUES(correct_flag),updated_at=VALUES(updated_at);
INSERT INTO exam_reporting.rpt_proctoring_student
    (id,exam_id,student_id,student_name,class_names_json,session_status,submission_status,event_count,
     last_event_time,latest_event_type,updated_at)
SELECT CAST(CONV(SUBSTRING(SHA2(CONCAT(c.exam_id,':',c.student_id),256),1,15),16,10) AS UNSIGNED),
       c.exam_id,c.student_id,u.real_name,json_array(tc.name),es.status,s.status,
       COUNT(ace.id),MAX(ace.event_time),
       SUBSTRING_INDEX(GROUP_CONCAT(ace.event_type ORDER BY ace.event_time DESC,ace.id DESC),',',1),CURRENT_TIMESTAMP(3)
FROM exam_management.exam_candidate c
LEFT JOIN exam_mvp.sys_user u ON u.id=c.student_id
LEFT JOIN exam_mvp.teaching_class tc ON tc.id=c.class_id
LEFT JOIN exam_mvp.exam_session es ON es.exam_id=c.exam_id AND es.student_id=c.student_id
LEFT JOIN exam_mvp.submission s ON s.exam_id=c.exam_id AND s.student_id=c.student_id
LEFT JOIN exam_mvp.anti_cheat_event ace ON ace.exam_id=c.exam_id AND ace.student_id=c.student_id
GROUP BY c.exam_id,c.student_id,u.real_name,tc.name,es.status,s.status
ON DUPLICATE KEY UPDATE student_name=VALUES(student_name),class_names_json=VALUES(class_names_json),
    session_status=VALUES(session_status),submission_status=VALUES(submission_status),event_count=VALUES(event_count),
    last_event_time=VALUES(last_event_time),latest_event_type=VALUES(latest_event_type),updated_at=VALUES(updated_at);
REPLACE INTO exam_reporting.operation_audit_log
    (id,operator_id,operator_username,operator_roles,action,target_type,target_id,request_method,
     request_path,request_ip,detail,status,error_message,operate_time,create_time,update_time,create_by,update_by)
SELECT id,operator_id,operator_username,operator_roles,action,target_type,target_id,request_method,
       request_path,request_ip,detail,status,error_message,operate_time,create_time,update_time,create_by,update_by
FROM exam_mvp.operation_audit_log;

SET FOREIGN_KEY_CHECKS = 1;
