#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-$PROJECT_ROOT/.env.microservices}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-exam-platform-cloud-mysql-1}"
TEST_DATABASE="${TIMEOUT_TEST_DATABASE:-timeout_test}"

if [[ ! "$TEST_DATABASE" =~ ^[A-Za-z0-9_]*test[A-Za-z0-9_]*$ ]]; then
    echo "测试数据库名称只能包含字母、数字和下划线，并且必须包含 test：$TEST_DATABASE" >&2
    exit 1
fi
if [[ ! -f "$ENV_FILE" ]]; then
    echo "未找到环境文件：$ENV_FILE" >&2
    exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

docker exec "$MYSQL_CONTAINER" sh -c '
mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
CREATE DATABASE IF NOT EXISTS '$TEST_DATABASE'
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
GRANT ALL PRIVILEGES ON '$TEST_DATABASE'.* TO '\''exam_runtime'\''@'\''%'\'';
FLUSH PRIVILEGES;
"
'

export TIMEOUT_TEST_JDBC_URL="jdbc:mysql://127.0.0.1:23306/$TEST_DATABASE?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
export TIMEOUT_TEST_USERNAME="exam_runtime"
export TIMEOUT_TEST_PASSWORD="$EXAM_DB_PASSWORD"

cd "$PROJECT_ROOT"
mvn -pl services/exam-runtime-service -am \
    -Dtest=TimeoutSubmissionV2MySqlTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    test
