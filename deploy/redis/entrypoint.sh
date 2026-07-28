#!/bin/sh
set -eu

DATA_DIR="/data"
CONFIG_FILE="/usr/local/etc/redis/redis.conf"
RDB_FILE="$DATA_DIR/dump.rdb"
AOF_DIR="$DATA_DIR/appendonlydir"
AOF_MANIFEST="$AOF_DIR/appendonly.aof.manifest"
LEGACY_AOF_FILE="$DATA_DIR/appendonly.aof"
MIGRATION_MARKER="$DATA_DIR/.exam-rdb-to-aof-migration"
MIGRATION_SOCKET="/tmp/exam-redis-rdb-to-aof.sock"
MIGRATION_TIMEOUT_SECONDS="${REDIS_AOF_MIGRATION_TIMEOUT_SECONDS:-600}"

case "$MIGRATION_TIMEOUT_SECONDS" in
    ''|*[!0-9]*)
        echo "REDIS_AOF_MIGRATION_TIMEOUT_SECONDS 必须是正整数。" >&2
        exit 1
        ;;
    0)
        echo "REDIS_AOF_MIGRATION_TIMEOUT_SECONDS 必须大于 0。" >&2
        exit 1
        ;;
esac

if [ -f "$MIGRATION_MARKER" ]; then
    if [ ! -f "$RDB_FILE" ]; then
        echo "检测到未完成的 RDB 到 AOF 迁移，但原始 RDB 不存在，拒绝启动。" >&2
        exit 1
    fi
    echo "检测到上次 RDB 到 AOF 迁移被中断，将保留原始 RDB 并重新迁移。"
    rm -rf "$AOF_DIR"
    rm -f "$MIGRATION_MARKER"
fi

has_aof=false
if [ -f "$AOF_MANIFEST" ] || [ -f "$LEGACY_AOF_FILE" ]; then
    has_aof=true
elif [ -d "$AOF_DIR" ] && [ -n "$(find "$AOF_DIR" -mindepth 1 -print -quit 2>/dev/null)" ]; then
    echo "检测到缺少 manifest 的 AOF 目录，拒绝自动覆盖：$AOF_DIR" >&2
    echo "请先备份数据卷并检查 AOF 文件完整性。" >&2
    exit 1
fi

migration_pid=""
migration_active=false
cleanup_migration() {
    if [ -n "$migration_pid" ] && kill -0 "$migration_pid" 2>/dev/null; then
        redis-cli -s "$MIGRATION_SOCKET" SHUTDOWN NOSAVE >/dev/null 2>&1 || true
        kill "$migration_pid" >/dev/null 2>&1 || true
        wait "$migration_pid" >/dev/null 2>&1 || true
    fi
    rm -f "$MIGRATION_SOCKET"
    if [ "$migration_active" = true ]; then
        rm -rf "$AOF_DIR"
        rm -f "$MIGRATION_MARKER"
    fi
}

trap cleanup_migration EXIT
trap 'exit 1' HUP INT TERM

if [ -f "$RDB_FILE" ] && [ "$has_aof" = false ]; then
    echo "检测到仅含 RDB 的旧 Redis 数据卷，开始生成 AOF。"
    rm -rf "$AOF_DIR"
    rm -f "$MIGRATION_SOCKET"
    touch "$MIGRATION_MARKER"
    migration_active=true

    /usr/local/bin/docker-entrypoint.sh redis-server "$CONFIG_FILE" \
        --appendonly no \
        --save "" \
        --port 0 \
        --unixsocket "$MIGRATION_SOCKET" \
        --unixsocketperm 700 &
    migration_pid=$!

    ready_attempt=0
    until [ "$(redis-cli --raw -s "$MIGRATION_SOCKET" PING 2>/dev/null || true)" = "PONG" ]; do
        if ! kill -0 "$migration_pid" 2>/dev/null; then
            wait "$migration_pid" || true
            echo "临时 Redis 未能加载 $RDB_FILE，AOF 迁移已中止。" >&2
            exit 1
        fi
        ready_attempt=$((ready_attempt + 1))
        if [ "$ready_attempt" -ge "$MIGRATION_TIMEOUT_SECONDS" ]; then
            echo "等待临时 Redis 加载 RDB 超时，AOF 迁移已中止。" >&2
            exit 1
        fi
        sleep 1
    done

    if [ "$(redis-cli --raw -s "$MIGRATION_SOCKET" CONFIG SET appendonly yes)" != "OK" ]; then
        echo "无法为临时 Redis 启用 AOF，迁移已中止。" >&2
        exit 1
    fi

    elapsed=0
    while :; do
        persistence_info="$(redis-cli --raw -s "$MIGRATION_SOCKET" INFO persistence | tr -d '\r')"
        if printf '%s\n' "$persistence_info" | grep -qx 'aof_last_bgrewrite_status:err'; then
            echo "AOF rewrite 失败，原始 RDB 保持不变，迁移已中止。" >&2
            exit 1
        fi
        if printf '%s\n' "$persistence_info" | grep -qx 'aof_enabled:1' \
            && printf '%s\n' "$persistence_info" | grep -qx 'aof_rewrite_in_progress:0' \
            && printf '%s\n' "$persistence_info" | grep -qx 'aof_rewrite_scheduled:0' \
            && printf '%s\n' "$persistence_info" | grep -qx 'aof_last_bgrewrite_status:ok' \
            && [ -f "$AOF_MANIFEST" ]; then
            break
        fi
        elapsed=$((elapsed + 1))
        if [ "$elapsed" -ge "$MIGRATION_TIMEOUT_SECONDS" ]; then
            echo "等待 AOF rewrite 完成超时，原始 RDB 保持不变，迁移已中止。" >&2
            exit 1
        fi
        sleep 1
    done

    redis-cli -s "$MIGRATION_SOCKET" SHUTDOWN NOSAVE >/dev/null
    wait "$migration_pid"
    migration_pid=""
    rm -f "$MIGRATION_SOCKET"
    rm -f "$MIGRATION_MARKER"
    migration_active=false
    echo "旧 RDB 已成功转换为 AOF，准备启动正式 Redis。"
fi

trap - EXIT HUP INT TERM
exec /usr/local/bin/docker-entrypoint.sh "$@"
