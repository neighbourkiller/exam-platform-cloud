#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$PROJECT_ROOT/.env.microservices}"
COMPOSE=(docker compose -p exam-platform-cloud -f "$PROJECT_ROOT/compose.yaml" --env-file "$ENV_FILE")
WAIT_SECONDS="${SEED_WAIT_SECONDS:-120}"

if [[ ! -f "$ENV_FILE" ]]; then
    echo "未找到 Compose 环境文件：$ENV_FILE" >&2
    exit 1
fi

deadline=$((SECONDS + WAIT_SECONDS))
table_count=""
while (( SECONDS < deadline )); do
    table_count="$("${COMPOSE[@]}" exec -T mysql sh -lc '
        mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -Nse "
            SELECT COUNT(*)
              FROM information_schema.tables
             WHERE (table_schema = '\''exam_iam'\'' AND table_name IN ('\''sys_role'\'', '\''sys_user'\'', '\''sys_user_role'\''))
                OR (table_schema = '\''exam_academic'\'' AND table_name IN ('\''student_profile'\'', '\''teacher_profile'\''));
        "
    ' 2>/dev/null || true)"
    [[ "$table_count" == "5" ]] && break
    sleep 2
done

if [[ "$table_count" != "5" ]]; then
    echo "等待 IAM 与 Academic 数据表就绪超时。" >&2
    exit 1
fi

"${COMPOSE[@]}" exec -T mysql sh -lc '
mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" exam_iam -e "
START TRANSACTION;
INSERT INTO sys_role(id,code,name,create_time,update_time) VALUES
  (1,'\''ADMIN'\'','\''管理员'\'',current_timestamp(3),current_timestamp(3)),
  (2,'\''TEACHER'\'','\''教师'\'',current_timestamp(3),current_timestamp(3)),
  (3,'\''STUDENT'\'','\''学生'\'',current_timestamp(3),current_timestamp(3))
ON DUPLICATE KEY UPDATE code=VALUES(code),name=VALUES(name),update_time=current_timestamp(3);
SET @seed_password_hash = '\''$APP_DEFAULT_PASSWORD_HASH'\'';
INSERT INTO sys_user(id,username,password,real_name,enabled,token_version,create_time,update_time) VALUES
  (1,'\''admin'\'',@seed_password_hash,'\''系统管理员'\'',1,0,current_timestamp(3),current_timestamp(3)),
  (2,'\''teacher01'\'',@seed_password_hash,'\''李老师'\'',1,0,current_timestamp(3),current_timestamp(3)),
  (20010001,'\''20010001'\'',@seed_password_hash,'\''张三'\'',1,0,current_timestamp(3),current_timestamp(3)),
  (20010002,'\''20010002'\'',@seed_password_hash,'\''李四'\'',1,0,current_timestamp(3),current_timestamp(3)),
  (20010003,'\''20010003'\'',@seed_password_hash,'\''王五'\'',1,0,current_timestamp(3),current_timestamp(3))
ON DUPLICATE KEY UPDATE real_name=VALUES(real_name),enabled=1,update_time=current_timestamp(3);
INSERT INTO sys_user_role(id,user_id,role_id,create_time,update_time)
SELECT 10001,u.id,r.id,current_timestamp(3),current_timestamp(3) FROM sys_user u JOIN sys_role r ON r.code='\''ADMIN'\'' WHERE u.username='\''admin'\''
ON DUPLICATE KEY UPDATE update_time=current_timestamp(3);
INSERT INTO sys_user_role(id,user_id,role_id,create_time,update_time)
SELECT 10002,u.id,r.id,current_timestamp(3),current_timestamp(3) FROM sys_user u JOIN sys_role r ON r.code='\''TEACHER'\'' WHERE u.username='\''teacher01'\''
ON DUPLICATE KEY UPDATE update_time=current_timestamp(3);
INSERT INTO sys_user_role(id,user_id,role_id,create_time,update_time)
SELECT 10003,u.id,r.id,current_timestamp(3),current_timestamp(3) FROM sys_user u JOIN sys_role r ON r.code='\''STUDENT'\'' WHERE u.username='\''20010001'\''
ON DUPLICATE KEY UPDATE update_time=current_timestamp(3);
INSERT INTO sys_user_role(id,user_id,role_id,create_time,update_time)
SELECT 10004,u.id,r.id,current_timestamp(3),current_timestamp(3) FROM sys_user u JOIN sys_role r ON r.code='\''STUDENT'\'' WHERE u.username='\''20010002'\''
ON DUPLICATE KEY UPDATE update_time=current_timestamp(3);
INSERT INTO sys_user_role(id,user_id,role_id,create_time,update_time)
SELECT 10005,u.id,r.id,current_timestamp(3),current_timestamp(3) FROM sys_user u JOIN sys_role r ON r.code='\''STUDENT'\'' WHERE u.username='\''20010003'\''
ON DUPLICATE KEY UPDATE update_time=current_timestamp(3);
COMMIT;"
mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" exam_academic -e "
START TRANSACTION;
INSERT INTO teacher_profile(id,user_id,teacher_no,title,status,create_time,update_time)
VALUES(2,2,'\''T001'\'','\''讲师'\'','\''ACTIVE'\'',current_timestamp(3),current_timestamp(3))
ON DUPLICATE KEY UPDATE teacher_no=VALUES(teacher_no),title=VALUES(title),status='\''ACTIVE'\'',update_time=current_timestamp(3);
INSERT INTO student_profile(id,user_id,student_no,enrollment_year,status,create_time,update_time) VALUES
  (20010001,20010001,'\''20010001'\'','\''2020'\'','\''ACTIVE'\'',current_timestamp(3),current_timestamp(3)),
  (20010002,20010002,'\''20010002'\'','\''2020'\'','\''ACTIVE'\'',current_timestamp(3),current_timestamp(3)),
  (20010003,20010003,'\''20010003'\'','\''2020'\'','\''ACTIVE'\'',current_timestamp(3),current_timestamp(3))
ON DUPLICATE KEY UPDATE student_no=VALUES(student_no),enrollment_year=VALUES(enrollment_year),status='\''ACTIVE'\'',update_time=current_timestamp(3);
COMMIT;"
'

echo "本地演示账号已就绪：admin、teacher01、20010001 至 20010003。"
echo "所有演示账号密码均为 .env.microservices 中的 APP_DEFAULT_PASSWORD。"
