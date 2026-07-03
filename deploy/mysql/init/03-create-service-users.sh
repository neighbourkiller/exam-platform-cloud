#!/bin/sh
set -eu

if [ -z "${EXAM_DB_PASSWORD:-}" ]; then
  echo "EXAM_DB_PASSWORD is required" >&2
  exit 1
fi
if [ -z "${NACOS_PASSWORD_HASH:-}" ]; then
  echo "NACOS_PASSWORD_HASH is required (BCrypt hash for NACOS_PASSWORD)" >&2
  exit 1
fi

escaped_password=$(printf '%s' "$EXAM_DB_PASSWORD" | sed "s/'/''/g")
escaped_nacos_hash=$(printf '%s' "$NACOS_PASSWORD_HASH" | sed "s/'/''/g")

mysql --protocol=socket -uroot -p"$MYSQL_ROOT_PASSWORD" <<SQL
CREATE USER IF NOT EXISTS 'exam_iam'@'%' IDENTIFIED BY '${escaped_password}';
CREATE USER IF NOT EXISTS 'exam_academic'@'%' IDENTIFIED BY '${escaped_password}';
CREATE USER IF NOT EXISTS 'exam_content'@'%' IDENTIFIED BY '${escaped_password}';
CREATE USER IF NOT EXISTS 'exam_management'@'%' IDENTIFIED BY '${escaped_password}';
CREATE USER IF NOT EXISTS 'exam_runtime'@'%' IDENTIFIED BY '${escaped_password}';
CREATE USER IF NOT EXISTS 'exam_grading'@'%' IDENTIFIED BY '${escaped_password}';
CREATE USER IF NOT EXISTS 'exam_reporting'@'%' IDENTIFIED BY '${escaped_password}';
CREATE USER IF NOT EXISTS 'exam_nacos'@'%' IDENTIFIED BY '${escaped_password}';
CREATE USER IF NOT EXISTS 'exam_xxl'@'%' IDENTIFIED BY '${escaped_password}';
GRANT ALL PRIVILEGES ON exam_iam.* TO 'exam_iam'@'%';
GRANT ALL PRIVILEGES ON exam_academic.* TO 'exam_academic'@'%';
GRANT ALL PRIVILEGES ON exam_content.* TO 'exam_content'@'%';
GRANT ALL PRIVILEGES ON exam_management.* TO 'exam_management'@'%';
GRANT ALL PRIVILEGES ON exam_runtime.* TO 'exam_runtime'@'%';
GRANT ALL PRIVILEGES ON exam_grading.* TO 'exam_grading'@'%';
GRANT ALL PRIVILEGES ON exam_reporting.* TO 'exam_reporting'@'%';
GRANT ALL PRIVILEGES ON nacos_config.* TO 'exam_nacos'@'%';
GRANT ALL PRIVILEGES ON xxl_job.* TO 'exam_xxl'@'%';
FLUSH PRIVILEGES;
UPDATE nacos_config.users SET password='${escaped_nacos_hash}', enabled=TRUE WHERE username='nacos';
SQL
