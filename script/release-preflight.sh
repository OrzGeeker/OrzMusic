#!/bin/bash
# release-preflight — 发布前置检查
#
# 验证生产升级/回滚所需的前置条件是否满足。
# 失败时不修改任何状态。
#
# 用法: ./script/release-preflight.sh
#
# 环境变量:
#   COMPOSE_FILE     docker-compose 配置文件（默认 -f docker-compose.yml -f docker-compose.production.yml）

set -uo pipefail

COMPOSE_BASE="${COMPOSE_FILE:--f docker-compose.yml -f docker-compose.production.yml}"
COMPOSE="${DOCKER_COMPOSE:-docker compose}"

echo "=== Preflight Checks ==="
PASS=true

# 1. Docker 可用
if command -v docker >/dev/null 2>&1; then
    echo "  [PASS] docker CLI available"
else
    echo "  [FAIL] docker CLI not found"
    PASS=false
fi

# 2. docker compose 可用
if $COMPOSE version >/dev/null 2>&1; then
    echo "  [PASS] docker compose available"
else
    echo "  [FAIL] docker compose not available"
    PASS=false
fi

# 3. IMAGE_REF 已设置（仅当作为发布步骤运行时需要）
if [ -n "${IMAGE_REF:-}" ]; then
    echo "  [PASS] IMAGE_REF=$IMAGE_REF"
else
    echo "  [SKIP] IMAGE_REF not set (only needed for upgrade)"
fi

# 4. 数据库可达
# shellcheck disable=SC2086  # COMPOSE_BASE 是空格分隔的 -f 列表，故意不分词保护
if $COMPOSE $COMPOSE_BASE exec -T db pg_isready -U "${DATABASE_USERNAME:-vapor_username}" -d "${DATABASE_NAME:-vapor_database}" -h localhost >/dev/null 2>&1; then
    echo "  [PASS] Database is ready"
else
    echo "  [FAIL] Database is not reachable"
    PASS=false
fi

# 5. 备份目录可写
BACKUP_DIR="${BACKUP_DIR:-./backups}"
if mkdir -p "$BACKUP_DIR" 2>/dev/null && [ -w "$BACKUP_DIR" ]; then
    echo "  [PASS] Backup directory writable: $BACKUP_DIR"
else
    echo "  [FAIL] Backup directory not writable: $BACKUP_DIR"
    PASS=false
fi

# 6. 无静默覆盖风险
if [ -n "${IMAGE_REF:-}" ]; then
    # 检查当前的 image 标签，确保不会回滚到相同版本
    echo "  [SKIP] Override check: IMAGE_REF validation deferred to upgrade script"
fi

# 7. JSON 解析器（release-smoke.sh 验收依赖）
json_parser=""
if command -v jq >/dev/null 2>&1 && printf '{}' | jq -e . >/dev/null 2>&1; then
    json_parser="jq"
elif command -v python3 >/dev/null 2>&1 && printf '{}' | python3 -c 'import json,sys; json.load(sys.stdin)' >/dev/null 2>&1; then
    json_parser="python3"
elif command -v python >/dev/null 2>&1 && printf '{}' | python -c 'import json,sys; json.load(sys.stdin)' >/dev/null 2>&1; then
    json_parser="python"
fi
if [ -n "$json_parser" ]; then
    echo "  [PASS] JSON parser available: $json_parser"
else
    echo "  [FAIL] jq or python/python3 is required by release-smoke.sh"
    PASS=false
fi

echo ""
if [ "$PASS" = true ]; then
    echo "Preflight: ALL CHECKS PASSED"
    exit 0
else
    echo "Preflight: ONE OR MORE CHECKS FAILED — aborting"
    exit 1
fi
