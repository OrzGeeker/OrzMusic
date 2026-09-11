#!/bin/bash
# package-deploy — 生成生产部署轻量包
#
# 用法:
#   ./script/package-deploy.sh
#   VERSION=0.0.2 IMAGE_REF=ghcr.io/orzgeeker/orzmusic@sha256:... ./script/package-deploy.sh
#
# 输出:
#   dist/orzmusic-deploy-<version>.tar.gz

set -euo pipefail

VERSION_VALUE="${VERSION:-$(tr -d '[:space:]' < VERSION)}"
IMAGE_REF_VALUE="${IMAGE_REF:-}"
OUTPUT_DIR="${OUTPUT_DIR:-dist}"
PACKAGE_ROOT="orzmusic-deploy-${VERSION_VALUE}"
PACKAGE_NAME="${PACKAGE_ROOT}.tar.gz"

if [ -z "$VERSION_VALUE" ]; then
    echo "ERROR: VERSION is empty"
    exit 1
fi

mkdir -p "$OUTPUT_DIR"
STAGING_DIR="$(mktemp -d)"
cleanup() {
    rm -rf "$STAGING_DIR"
}
trap cleanup EXIT

mkdir -p "$STAGING_DIR/$PACKAGE_ROOT/script" "$STAGING_DIR/$PACKAGE_ROOT/Docs"

copy_file() {
    local source="$1"
    local target="$2"
    if [ ! -f "$source" ]; then
        echo "ERROR: required file missing: $source"
        exit 1
    fi
    cp "$source" "$STAGING_DIR/$PACKAGE_ROOT/$target"
}

copy_file docker-compose.yml docker-compose.yml
copy_file docker-compose.production.yml docker-compose.production.yml
copy_file VERSION VERSION
copy_file CHANGELOG.md CHANGELOG.md
copy_file README.md README.md
copy_file Docs/deployment.md Docs/deployment.md
copy_file Docs/migration.md Docs/migration.md
copy_file script/db-backup.sh script/db-backup.sh
copy_file script/release-preflight.sh script/release-preflight.sh
copy_file script/release-upgrade.sh script/release-upgrade.sh
copy_file script/release-rollback.sh script/release-rollback.sh
copy_file script/release-smoke.sh script/release-smoke.sh
copy_file script/release-scan.sh script/release-scan.sh
copy_file script/generate-admin-token.sh script/generate-admin-token.sh

chmod +x "$STAGING_DIR/$PACKAGE_ROOT"/script/*.sh

cat > "$STAGING_DIR/$PACKAGE_ROOT/Makefile" <<'EOF'
SHELL := /bin/bash

.DEFAULT_GOAL := help

.PHONY: help
help:
	@echo "OrzMusic production deployment commands"
	@echo ""
	@echo "  make release-preflight  Preflight checks for production release"
	@echo "  make release-upgrade    Production upgrade (IMAGE_REF=..., ADMIN_API_TOKEN=...)"
	@echo "  make release-rollback   Rollback to previous version (IMAGE_REF=...)"
	@echo "  make release-smoke      Run smoke check after upgrade (SERVICE_URL=http://...)"
	@echo "  make db-backup          Database backup (VERSION=X.Y.Z)"
	@echo "  make scan               Scan configured music directory (ADMIN_API_TOKEN=...)"
	@echo "  make generate-admin-token Generate a secure ADMIN_API_TOKEN"

.PHONY: release-preflight
release-preflight:
	./script/release-preflight.sh

.PHONY: release-upgrade
release-upgrade:
	./script/release-upgrade.sh

.PHONY: release-rollback
release-rollback:
	./script/release-rollback.sh

.PHONY: release-smoke
release-smoke:
	./script/release-smoke.sh

.PHONY: db-backup
db-backup:
	./script/db-backup.sh

.PHONY: scan
scan:
	./script/release-scan.sh

.PHONY: generate-admin-token
generate-admin-token:
	@./script/generate-admin-token.sh
EOF

cat > "$STAGING_DIR/$PACKAGE_ROOT/DEPLOYMENT.txt" <<EOF
OrzMusic deployment package

Version: ${VERSION_VALUE}
Image: ${IMAGE_REF_VALUE:-Set IMAGE_REF before upgrade}

Typical production upgrade:

  tar -xzf ${PACKAGE_NAME}
  cd ${PACKAGE_ROOT}
  export IMAGE_REF=${IMAGE_REF_VALUE:-ghcr.io/orzgeeker/orzmusic:${VERSION_VALUE}}
  export ADMIN_API_TOKEN=<a-long-random-secret>
  make release-preflight
  make release-upgrade
  EXPECTED_VERSION=${VERSION_VALUE} make release-smoke

Windows (git-bash / MSYS) 提示:
  - BACKUP_DIR 使用宿主绝对路径，例如 E:/deploy/backups；
  - 容器内备份路径由 db-backup.sh 通过 sh -c 传入，不受 MSYS 参数路径转换影响；
  - release-smoke / release-preflight 需要可用的 JSON 解析器（jq 优先，
    其次 python3 / python）；缺失时会直接报错，不会误报成接口异常。

项目名由 docker-compose.yml 钉死为 orzmusic（name: 字段），按版本目录切换
不会新建空数据库；多实例隔离可用 COMPOSE_PROJECT_NAME 覆盖。

See Docs/deployment.md for details.
EOF

tar -C "$STAGING_DIR" -czf "$OUTPUT_DIR/$PACKAGE_NAME" "$PACKAGE_ROOT"

echo "$OUTPUT_DIR/$PACKAGE_NAME"
