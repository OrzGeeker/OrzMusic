#!/bin/bash
# PostgreSQL backup via Docker Compose — 发布前使用
#
# 用法:
#   VERSION=0.0.1 ./script/db-backup.sh
#   BACKUP_DIR=./my-backups VERSION=0.0.1 ./script/db-backup.sh
#
# 环境变量:
#   BACKUP_DIR     备份输出目录（默认 ./backups）
#   VERSION        目标版本号，影响文件名（默认 unknown）
#   COMPOSE_PROJECT Docker Compose 项目名（可选）
#
# 数据库连接从 docker-compose 环境变量继承，无需单独设置。
# 需要 Docker Compose 栈中的 db 服务正在运行。

set -euo pipefail

# ---- 配置 ----
BACKUP_DIR="${BACKUP_DIR:-./backups}"
VERSION="${VERSION:-unknown}"
COMPOSE_PROJECT="${COMPOSE_PROJECT:-}"

# ---- 从 VERSION 文件回退 ----
if [ "$VERSION" = "unknown" ] && [ -f VERSION ]; then
    VERSION=$(tr -d '[:space:]' < VERSION)
fi

# ---- 参数校验 ----
if [ -z "$VERSION" ] || [ "$VERSION" = "unknown" ]; then
    echo "ERROR: VERSION is not set and VERSION file not found." >&2
    echo "Usage: VERSION=X.Y.Z ./script/db-backup.sh" >&2
    exit 1
fi

# ---- 创建备份目录 ----
if ! mkdir -p "$BACKUP_DIR" 2>/dev/null; then
    echo "ERROR: Cannot create backup directory: $BACKUP_DIR" >&2
    exit 1
fi

# ---- 生成文件名 ----
TIMESTAMP=$(date -u +"%Y%m%dT%H%M%SZ")
FILENAME="orzmusic-db-${VERSION}-${TIMESTAMP}.dump"
FILEPATH="${BACKUP_DIR}/${FILENAME}"

# ---- 防静默覆盖 ----
if [ -f "$FILEPATH" ]; then
    echo "ERROR: Backup file already exists: $FILEPATH" >&2
    exit 1
fi

# ---- Docker Compose 包装 ----
# COMPOSE_BASE 继承自 release-upgrade.sh 的 -f 参数列表；直接运行时可为空。
# 注意：不要向本脚本传入 docker compose 原生语义的 COMPOSE_FILE（冒号分隔路径列表），
# 这里统一使用 COMPOSE_BASE（空格分隔的 -f 参数）。
compose() {
    # COMPOSE_BASE 是空格分隔的 -f 参数列表，故意不加引号以触发分词。
    # 这里不先存进数组：macOS 自带 bash 3.2 在 set -u 下展开空数组
    # "${args[@]}" 会报 unbound variable，而 ${COMPOSE_BASE:-} 展开为空时会
    # 自动消失。
    # shellcheck disable=SC2086
    if [ -n "$COMPOSE_PROJECT" ]; then
        docker compose ${COMPOSE_BASE:-} --project-name "$COMPOSE_PROJECT" "$@"
    else
        docker compose ${COMPOSE_BASE:-} "$@"
    fi
}

# ---- 执行备份 ----
echo "Creating database backup: $FILEPATH"

# 通过 docker compose exec 在 db 容器中执行 pg_dump，
# 避免暴露数据库端口或密码在命令行参数中。
# 密码通过 PGPASSWORD 环境变量传递，不打印日志。
PGPASSWORD="${DATABASE_PASSWORD:-vapor_password}" \
    compose exec -T db \
    pg_dump \
    -U "${DATABASE_USERNAME:-vapor_username}" \
    -d "${DATABASE_NAME:-vapor_database}" \
    -h localhost \
    -Fc \
    -f "/tmp/${FILENAME}" \
    2>&1

# 从容器中复制备份到宿主机
compose cp \
    "db:/tmp/${FILENAME}" \
    "$FILEPATH" \
    2>&1

# 清理容器内临时文件（忽略失败）
compose exec -T db \
    rm -f "/tmp/${FILENAME}" \
    2>/dev/null || true

# ---- 验证备份文件 ----
if [ ! -f "$FILEPATH" ]; then
    echo "ERROR: Backup file was not created: $FILEPATH" >&2
    exit 1
fi

if [ ! -s "$FILEPATH" ]; then
    rm -f "$FILEPATH"
    echo "ERROR: Backup file is empty (deleted): $FILEPATH" >&2
    exit 1
fi

echo "Backup saved: $FILEPATH ($(du -h "$FILEPATH" | cut -f1))"
echo "To verify: pg_restore --list \"$FILEPATH\" | head -20"
