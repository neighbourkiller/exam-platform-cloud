#!/bin/sh
set -e

if [ -z "${XXL_JOB_ADMIN_PASSWORD:-}" ]; then
  echo "XXL_JOB_ADMIN_PASSWORD is required" >&2
  exit 1
fi

escaped_password=$(printf '%s' "$XXL_JOB_ADMIN_PASSWORD" | sed "s/'/''/g")

mysql --protocol=socket -uroot -p"$MYSQL_ROOT_PASSWORD" xxl_job <<SQL
UPDATE xxl_job_user
   SET password=LOWER(SHA2('${escaped_password}',256))
 WHERE username='admin';

INSERT INTO xxl_job_group(app_name,title,address_type,address_list,update_time)
SELECT 'exam-runtime-executor','在线考试 Runtime 执行器',0,NULL,NOW()
WHERE NOT EXISTS (SELECT 1 FROM xxl_job_group WHERE app_name='exam-runtime-executor');

SET @exam_job_group=(SELECT id FROM xxl_job_group WHERE app_name='exam-runtime-executor' LIMIT 1);

INSERT INTO xxl_job_info(job_group,job_desc,add_time,update_time,author,alarm_email,
    schedule_type,schedule_conf,misfire_strategy,executor_route_strategy,executor_handler,executor_param,
    executor_block_strategy,executor_timeout,executor_fail_retry_count,glue_type,glue_source,glue_remark,
    glue_updatetime,child_jobid,trigger_status,trigger_last_time,trigger_next_time)
SELECT @exam_job_group,'考试超时自动交卷',NOW(),NOW(),'exam-platform','',
       'CRON','0/2 * * * * ?','DO_NOTHING','SHARDING_BROADCAST','examTimeoutSubmitJob','',
       'SERIAL_EXECUTION',40,0,'BEAN','','GLUE代码初始化',NOW(),'',1,0,0
WHERE NOT EXISTS (
    SELECT 1 FROM xxl_job_info
     WHERE job_group=@exam_job_group AND executor_handler='examTimeoutSubmitJob'
);
SQL
