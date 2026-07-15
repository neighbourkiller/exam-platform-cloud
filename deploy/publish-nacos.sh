#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$PROJECT_ROOT/.env.microservices}"
SERVER="${NACOS_SERVER:-http://127.0.0.1:18081}"
NAMESPACE="${NACOS_NAMESPACE:-dev}"
GROUP="${NACOS_GROUP:-EXAM_GROUP}"
WAIT_SECONDS="${NACOS_WAIT_SECONDS:-120}"

for command in curl python3; do
    if ! command -v "$command" >/dev/null 2>&1; then
        echo "未找到 $command，请先安装后重试。" >&2
        exit 1
    fi
done

if [[ ! -f "$ENV_FILE" ]]; then
    echo "未找到 Compose 环境文件：$ENV_FILE" >&2
    exit 1
fi

read_env_value() {
    local name="$1" value
    value="$(sed -n -E "s/^${name}=(.*)$/\\1/p" "$ENV_FILE" | tail -n 1)"
    value="${value%$'\r'}"
    if [[ "$value" =~ ^\"(.*)\"$ || "$value" =~ ^\'(.*)\'$ ]]; then
        value="${BASH_REMATCH[1]}"
    fi
    printf '%s' "$value"
}

username="$(read_env_value NACOS_USERNAME)"
password="$(read_env_value NACOS_PASSWORD)"
if [[ -z "$username" || -z "$password" ]]; then
    echo "NACOS_USERNAME 或 NACOS_PASSWORD 未配置。" >&2
    exit 1
fi

extract_token() {
    python3 -c 'import json, sys; payload = json.load(sys.stdin); print(payload.get("accessToken") or payload.get("data", {}).get("accessToken") or "")'
}

check_response() {
    python3 -c 'import json, sys; payload = json.load(sys.stdin); sys.exit(0 if payload.get("code") == 0 else 1)'
}

deadline=$((SECONDS + WAIT_SECONDS))
token=""
admin_initialization_attempted=false
while (( SECONDS < deadline )); do
    if response="$(curl --silent --show-error --fail --max-time 5 -X POST "$SERVER/v3/auth/user/login" --data-urlencode "username=$username" --data-urlencode "password=$password" 2>/dev/null)"; then
        token="$(printf '%s' "$response" | extract_token)"
        [[ -n "$token" ]] && break
    fi
    if [[ "$admin_initialization_attempted" == false ]]; then
        if admin_response="$(curl --silent --show-error --fail --max-time 5 -X POST "$SERVER/v3/auth/user/admin" --data-urlencode "username=$username" --data-urlencode "password=$password" 2>/dev/null)" && printf '%s' "$admin_response" | check_response; then
            echo "已初始化 Nacos 管理员：$username"
        fi
        admin_initialization_attempted=true
    fi
    sleep 2
done

if [[ -z "$token" ]]; then
    echo "Nacos 在 ${WAIT_SECONDS} 秒内未就绪，或登录凭据与已有 MySQL 数据卷不一致。" >&2
    exit 1
fi

namespace_response="$(curl --silent --show-error --fail --get "$SERVER/v3/console/core/namespace/list" \
    --data-urlencode "accessToken=$token")"
namespace_exists="$(printf '%s' "$namespace_response" | python3 -c 'import json, sys; namespace = sys.argv[1]; payload = json.load(sys.stdin); print(str(any(item.get("namespace") == namespace for item in payload.get("data", []))).lower())' "$NAMESPACE")"
if [[ "$namespace_exists" != "true" ]]; then
    curl --silent --show-error --fail -X POST "$SERVER/v3/console/core/namespace" \
        --data-urlencode "accessToken=$token" \
        --data-urlencode "customNamespaceId=$NAMESPACE" \
        --data-urlencode "namespaceName=$NAMESPACE" \
        --data-urlencode "namespaceDesc=Exam $NAMESPACE environment" | check_response
    echo "已创建 Nacos 命名空间：$NAMESPACE"
fi

shopt -s nullglob
config_files=("$PROJECT_ROOT"/deploy/nacos-config/*.yml)
if (( ${#config_files[@]} == 0 )); then
    echo "未找到 Nacos 配置文件。" >&2
    exit 1
fi

for config_file in "${config_files[@]}"; do
    curl --silent --show-error --fail -X POST "$SERVER/v3/console/cs/config" \
        --data-urlencode "accessToken=$token" \
        --data-urlencode "dataId=$(basename "$config_file")" \
        --data-urlencode "groupName=$GROUP" \
        --data-urlencode "namespaceId=$NAMESPACE" \
        --data-urlencode "type=yaml" \
        --data-urlencode "content@$config_file" | check_response
    echo "已发布 Nacos 配置：$(basename "$config_file")"
done
