#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${1:-$PROJECT_ROOT/.env.microservices}"
LEGACY_ENV_FILE="$PROJECT_ROOT/.env"
SECRET_DIRECTORY="$PROJECT_ROOT/deploy/secrets"
PRIVATE_KEY_FILE="$SECRET_DIRECTORY/jwt-private.pem"
PUBLIC_KEY_FILE="$SECRET_DIRECTORY/jwt-public.pem"

for command in openssl python3; do
    if ! command -v "$command" >/dev/null 2>&1; then
        echo "未找到 $command，请先安装后重试。" >&2
        exit 1
    fi
done

if ! python3 -c 'import bcrypt' >/dev/null 2>&1; then
    echo "缺少 Python bcrypt 模块，请执行：sudo apt install python3-bcrypt" >&2
    exit 1
fi

declare -A existing_values=()

read_env_file() {
    local file="$1" line key value
    [[ -f "$file" ]] || return 0
    while IFS= read -r line || [[ -n "$line" ]]; do
        [[ "$line" =~ ^([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]] || continue
        key="${BASH_REMATCH[1]}"
        value="${BASH_REMATCH[2]%$'\r'}"
        if [[ "$value" =~ ^\"(.*)\"$ || "$value" =~ ^\'(.*)\'$ ]]; then
            value="${BASH_REMATCH[1]}"
        fi
        existing_values["$key"]="$value"
    done < "$file"
}

read_env_file "$LEGACY_ENV_FILE"
read_env_file "$ENV_FILE"

random_secret() {
    openssl rand -base64 "${1:-32}" | tr '+/' '-_' | tr -d '=\n'
}

random_base64() {
    openssl rand -base64 "${1:-32}" | tr -d '\n'
}

is_valid_nacos_auth_token() {
    python3 - "$1" <<'PY'
import base64
import sys

try:
    token = base64.b64decode(sys.argv[1], validate=True)
except Exception:
    sys.exit(1)
sys.exit(0 if len(token) >= 32 else 1)
PY
}

existing_or_new() {
    local name="$1" bytes="${2:-32}"
    if [[ -n "${existing_values[$name]:-}" ]]; then
        printf '%s' "${existing_values[$name]}"
    else
        random_secret "$bytes"
    fi
}

mkdir -p "$SECRET_DIRECTORY"
if [[ ! -f "$PRIVATE_KEY_FILE" ]]; then
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$PRIVATE_KEY_FILE" >/dev/null 2>&1
fi
if [[ ! -f "$PUBLIC_KEY_FILE" ]]; then
    openssl pkey -in "$PRIVATE_KEY_FILE" -pubout -out "$PUBLIC_KEY_FILE" >/dev/null 2>&1
fi
chmod 600 "$PRIVATE_KEY_FILE"
chmod 644 "$PUBLIC_KEY_FILE"

nacos_password="$(existing_or_new NACOS_PASSWORD 24)"
nacos_password_hash="$(python3 - "$nacos_password" <<'PY'
import bcrypt
import sys
print(bcrypt.hashpw(sys.argv[1].encode(), bcrypt.gensalt()).decode())
PY
)"

if [[ "${existing_values[DB_USERNAME]:-}" == "root" && -n "${existing_values[DB_PASSWORD]:-}" ]]; then
    mysql_root_password="${existing_values[DB_PASSWORD]}"
else
    mysql_root_password="$(existing_or_new MYSQL_ROOT_PASSWORD)"
fi

minio_access_key="${existing_values[MINIO_ACCESS_KEY]:-admin}"
minio_secret_key="${existing_values[MINIO_SECRET_KEY]:-password123}"
app_default_password="${existing_values[APP_DEFAULT_PASSWORD]:-$(existing_or_new APP_DEFAULT_PASSWORD 12)}"
app_default_password_hash="{bcrypt}$(python3 - "$app_default_password" <<'PY'
import bcrypt
import sys
print(bcrypt.hashpw(sys.argv[1].encode(), bcrypt.gensalt()).decode())
PY
)"
rabbitmq_username="${existing_values[RABBITMQ_USERNAME]:-exam}"
rabbitmq_password="${existing_values[RABBITMQ_PASSWORD]:-}"
if [[ "$rabbitmq_username" == "guest" ]]; then
    rabbitmq_username="exam"
    rabbitmq_password=""
fi
if [[ -z "$rabbitmq_password" ]]; then
    rabbitmq_password="$(random_secret)"
fi
nacos_auth_token="${existing_values[NACOS_AUTH_TOKEN]:-}"
if ! is_valid_nacos_auth_token "$nacos_auth_token"; then
    nacos_auth_token="$(random_base64)"
fi

write_value() {
    local name="$1" value="$2"
    if [[ "$value" == *'$'* || "$value" == *' '* || "$value" == *'#'* ]]; then
        printf "%s='%s'\n" "$name" "$value"
    else
        printf '%s=%s\n' "$name" "$value"
    fi
}

{
    write_value MYSQL_ROOT_PASSWORD "$mysql_root_password"
    write_value EXAM_DB_PASSWORD "$(existing_or_new EXAM_DB_PASSWORD)"
    write_value RABBITMQ_USERNAME "$rabbitmq_username"
    write_value RABBITMQ_PASSWORD "$rabbitmq_password"
    write_value MINIO_ACCESS_KEY "$minio_access_key"
    write_value MINIO_SECRET_KEY "$minio_secret_key"
    write_value NACOS_USERNAME "${existing_values[NACOS_USERNAME]:-nacos}"
    write_value NACOS_PASSWORD "$nacos_password"
    write_value NACOS_PASSWORD_HASH "$nacos_password_hash"
    write_value NACOS_AUTH_IDENTITY_KEY "$(existing_or_new NACOS_AUTH_IDENTITY_KEY 18)"
    write_value NACOS_AUTH_IDENTITY_VALUE "$(existing_or_new NACOS_AUTH_IDENTITY_VALUE 24)"
    write_value NACOS_AUTH_TOKEN "$nacos_auth_token"
    write_value XXL_JOB_ACCESS_TOKEN "$(existing_or_new XXL_JOB_ACCESS_TOKEN)"
    write_value XXL_JOB_ADMIN_PASSWORD "$(existing_or_new XXL_JOB_ADMIN_PASSWORD 18)"
    write_value SERVICE_CLIENT_SECRET "$(existing_or_new SERVICE_CLIENT_SECRET)"
    write_value APP_DEFAULT_PASSWORD "$app_default_password"
    write_value APP_DEFAULT_PASSWORD_HASH "$app_default_password_hash"
} > "$ENV_FILE"

chmod 600 "$ENV_FILE"
echo "本地部署环境文件已准备：$ENV_FILE"
echo "JWT Docker secrets 已准备：$SECRET_DIRECTORY"
