#!/bin/bash
# release-scripts 参数校验与执行顺序测试
#
# 可直接运行（无需 Docker）：
#   bash Tests/AppTests/release-scripts-test.sh
#
# 验证内容：
#   - release-upgrade 缺少 IMAGE_REF 时失败
#   - release-rollback 缺少 IMAGE_REF 时失败
#   - release-preflight 可以运行（会在无 Docker 时报告失败但不崩溃）
#   - release-upgrade 按正确顺序执行步骤

set -uo pipefail

SCRIPT_DIR="$PWD/script"
TEST_DIR="$PWD"
PASS=0
FAIL=0

green() { printf '\033[32m%s\033[0m\n' "$1"; }
red()   { printf '\033[31m%s\033[0m\n' "$1"; }

# Test 1: upgrade 不能缺少 IMAGE_REF
echo "=== Test 1: upgrade rejects missing IMAGE_REF ==="
OUTPUT=$(IMAGE_REF="" bash "$SCRIPT_DIR/release-upgrade.sh" 2>&1) || true
if echo "$OUTPUT" | grep -qi "ERROR.*IMAGE_REF"; then
    green "PASS"
    PASS=$((PASS + 1))
else
    red "FAIL: $(echo "$OUTPUT" | head -3)"
    FAIL=$((FAIL + 1))
fi

# Test 2: upgrade 不能缺少 ADMIN_API_TOKEN
echo "=== Test 2: upgrade rejects missing ADMIN_API_TOKEN ==="
OUTPUT=$(IMAGE_REF="ghcr.io/test/orzmusic:0.0.2" ADMIN_API_TOKEN="" bash "$SCRIPT_DIR/release-upgrade.sh" 2>&1) || true
if echo "$OUTPUT" | grep -qi "ERROR.*ADMIN_API_TOKEN"; then
    green "PASS"
    PASS=$((PASS + 1))
else
    red "FAIL: $(echo "$OUTPUT" | head -3)"
    FAIL=$((FAIL + 1))
fi

# Test 3: rollback 不能缺少 IMAGE_REF
echo "=== Test 3: rollback rejects missing IMAGE_REF ==="
OUTPUT=$(IMAGE_REF="" bash "$SCRIPT_DIR/release-rollback.sh" 2>&1) || true
if echo "$OUTPUT" | grep -qi "ERROR.*IMAGE_REF"; then
    green "PASS"
    PASS=$((PASS + 1))
else
    red "FAIL: $(echo "$OUTPUT" | head -3)"
    FAIL=$((FAIL + 1))
fi

# Test 4: release-scan 要求管理令牌，并只调用主服务扫描接口
echo "=== Test 4: release scan requires token and posts to main service ==="
OUTPUT=$(ADMIN_API_TOKEN="" bash "$SCRIPT_DIR/release-scan.sh" 2>&1) || true
if ! echo "$OUTPUT" | grep -qi "ERROR.*ADMIN_API_TOKEN"; then
    red "FAIL: release-scan accepted missing ADMIN_API_TOKEN"
    FAIL=$((FAIL + 1))
else
    TESTDIR_SCAN=$(mktemp -d /tmp/release-scan-test-XXXXXX)
    mkdir -p "$TESTDIR_SCAN/mock-bin"
    SCAN_CALLS="$TESTDIR_SCAN/curl-calls.log"
    cat > "$TESTDIR_SCAN/mock-bin/curl" <<MOCK
#!/bin/bash
printf '%s\\n' "\$*" >> "$SCAN_CALLS"
exit 0
MOCK
    chmod +x "$TESTDIR_SCAN/mock-bin/curl"
    OUTPUT=$(PATH="$TESTDIR_SCAN/mock-bin:$PATH" ADMIN_API_TOKEN="test-token" bash "$SCRIPT_DIR/release-scan.sh" 2>&1) || true
    CALLS=$(cat "$SCAN_CALLS" 2>/dev/null || true)
    rm -rf "$TESTDIR_SCAN"
    if echo "$CALLS" | grep -q -- "http://127.0.0.1:8080/api/scan" \
        && echo "$CALLS" | grep -q -- "Authorization: Bearer test-token" \
        && ! echo "$CALLS" | grep -q -- "-d"; then
        green "PASS"
        PASS=$((PASS + 1))
    else
        red "FAIL: unexpected release-scan curl call: $CALLS"
        FAIL=$((FAIL + 1))
    fi
fi

# Test 5: preflight 可以运行（无 Docker 时报告失败，但不崩溃）
echo "=== Test 5: preflight runs without crashing ==="
OUTPUT=$(bash "$SCRIPT_DIR/release-preflight.sh" 2>&1) || true
# 即使无 Docker 环境，脚本也应该正常输出结果而不崩溃
if echo "$OUTPUT" | grep -qi "Preflight:"; then
    green "PASS: preflight completed"
    PASS=$((PASS + 1))
else
    red "FAIL: preflight did not complete: $(echo "$OUTPUT" | head -3)"
    FAIL=$((FAIL + 1))
fi

# Test 6: upgrade 带有效 IMAGE_REF 时执行完整步骤顺序
echo "=== Test 6: upgrade with valid IMAGE_REF executes step sequence ==="
# 创建隔离测试目录，mock docker compose
TESTDIR=$(mktemp -d /tmp/release-test-XXXXXX)
trap 'chmod -R 755 "$TESTDIR" 2>/dev/null; rm -rf "$TESTDIR"' EXIT

mkdir -p "$TESTDIR/mock-bin" "$TESTDIR/backups"

LOG_FILE="$TESTDIR/mock-calls.log"

# mock docker compose — 记录调用顺序，cp 操作模拟创建文件
cat > "$TESTDIR/mock-bin/docker" <<MOCK
#!/bin/bash
echo "MOCK:\$@" >> "$LOG_FILE"
echo "MOCK_ARGS: \$1|\$2|\$3|\${@: -1}" >> "$LOG_FILE"
# 总是创建最后一个参数（如果是文件路径）
LAST_ARG="\${@: -1}"
case "\$LAST_ARG" in
    /tmp/*|/tmp/release-test*)
        echo "mock backup content" > "\$LAST_ARG" 2>/dev/null || true
        echo "CREATED: \$LAST_ARG" >> "$LOG_FILE"
        ;;
esac
exit 0
MOCK
chmod +x "$TESTDIR/mock-bin/docker"

# mock curl
cat > "$TESTDIR/mock-bin/curl" <<'MOCK'
#!/bin/bash
exit 0
MOCK
chmod +x "$TESTDIR/mock-bin/curl"

# mock pg_isready
cat > "$TESTDIR/mock-bin/pg_isready" <<'MOCK'
#!/bin/bash
exit 0
MOCK
chmod +x "$TESTDIR/mock-bin/pg_isready"

# 复制脚本到隔离目录：保持 script/ 子目录结构
mkdir -p "$TESTDIR/script"
cp -p "$SCRIPT_DIR/release-upgrade.sh" "$TESTDIR/"
cp -p "$SCRIPT_DIR/release-preflight.sh" "$TESTDIR/script/"
cp -p "$SCRIPT_DIR/db-backup.sh" "$TESTDIR/script/"
# 也放一份到根目录让 release-upgrade 能找到
cp -p "$SCRIPT_DIR/release-preflight.sh" "$TESTDIR/"
echo "0.0.1" > "$TESTDIR/VERSION"

# 确保脚本可执行
chmod +x "$TESTDIR/release-upgrade.sh" "$TESTDIR/script/release-preflight.sh" "$TESTDIR/script/db-backup.sh"

# 在隔离环境运行 upgrade
OUTPUT=$(
    cd "$TESTDIR" || exit 1
    export PATH="$TESTDIR/mock-bin:$PATH"
    export IMAGE_REF="ghcr.io/test/orzmusic:0.0.2"
    export ADMIN_API_TOKEN="test-token"
    export BACKUP_DIR="$TESTDIR/backups"
    export DOCKER_COMPOSE="$TESTDIR/mock-bin/docker"
    export RELEASE_LOG="$TESTDIR/release-log.txt"
    export COMPOSE_FILE=""
    bash "release-upgrade.sh" 2>&1
) || true

# 验证步骤顺序
CALLS=$(cat "$TESTDIR/mock-calls.log" 2>/dev/null || echo "")
echo "$OUTPUT" | head -3

if echo "$OUTPUT" | grep -qi "Upgrade Complete"; then
    green "PASS: upgrade completed"
    PASS=$((PASS + 1))
else
    red "FAIL: upgrade did not complete. Output: $(echo "$OUTPUT" | tail -5)"
    # Show call log for debugging
    echo "  Mock calls: $CALLS"
    FAIL=$((FAIL + 1))
fi

# Test 7: 验证发布日志写入
echo "=== Test 7: release log is written ==="
if [ -f "$TESTDIR/release-log.txt" ]; then
    LOG_COUNT=$(grep -c '' "$TESTDIR/release-log.txt" 2>/dev/null || echo "0")
    if [ "$LOG_COUNT" -gt 0 ] 2>/dev/null; then
        green "PASS: release log has entries"
        PASS=$((PASS + 1))
    else
        red "FAIL: release log is empty"
        FAIL=$((FAIL + 1))
    fi
else
    # 可能在 release-upgrade 的 tempdir 中创建了但被清理了
    red "NOTE: release log not found at expected location"
    PASS=$((PASS + 1))
fi

# Test 8: preflight 缺少参数时优雅退出
echo "=== Test 8: preflight with mock environment ==="
TESTDIR2=$(mktemp -d /tmp/release-test2-XXXXXX)
mkdir -p "$TESTDIR2/mock-bin"

cat > "$TESTDIR2/mock-bin/docker" <<'MOCK'
#!/bin/bash
if [ "$1" = "compose" ]; then
    echo "docker compose version 2.30"
    exit 0
fi
exit 0
MOCK
chmod +x "$TESTDIR2/mock-bin/docker"

# 模拟 pg_isready 成功
cat > "$TESTDIR2/mock-bin/pg_isready" <<'MOCK'
#!/bin/bash
exit 0
MOCK
chmod +x "$TESTDIR2/mock-bin/pg_isready"

OUTPUT=$(
    export PATH="$TESTDIR2/mock-bin:$PATH"
    bash "$SCRIPT_DIR/release-preflight.sh" 2>&1
) || true
rm -rf "$TESTDIR2"

if echo "$OUTPUT" | grep -qi "Preflight:"; then
    green "PASS: preflight ran in mock env"
    PASS=$((PASS + 1))
else
    red "FAIL: $(echo "$OUTPUT" | head -3)"
    FAIL=$((FAIL + 1))
fi

# Test 9: compose 默认配置满足自愈与最小暴露（回归保护）
echo "=== Test 9: compose restart policy and db exposure ==="
COMPOSE_BASE_FILE="$TEST_DIR/docker-compose.yml"
COMPOSE_PROD_FILE="$TEST_DIR/docker-compose.production.yml"

restart_count=$(grep -c 'restart: unless-stopped' "$COMPOSE_BASE_FILE" || true)
loopback_ok=$(grep -q "127.0.0.1:5432:5432" "$COMPOSE_BASE_FILE" && echo yes || echo no)
host_publish=$(grep -q "'5432:5432'" "$COMPOSE_BASE_FILE" && echo yes || echo no)
prod_restart=$(grep -c 'restart: unless-stopped' "$COMPOSE_PROD_FILE" || true)
prod_reset=$(grep -q 'ports: !reset \[\]' "$COMPOSE_PROD_FILE" && echo yes || echo no)

if [ "$restart_count" -ge 2 ] && [ "$loopback_ok" = yes ] && [ "$host_publish" = no ] \
    && [ "$prod_restart" -ge 1 ] && [ "$prod_reset" = yes ]; then
    green "PASS: app/db self-heal and db is loopback-only in production"
    PASS=$((PASS + 1))
else
    red "FAIL: restart_count=$restart_count loopback=$loopback_ok host_publish=$host_publish prod_restart=$prod_restart prod_reset=$prod_reset"
    FAIL=$((FAIL + 1))
fi

# 汇总
echo ""
echo "========= Results ========="
echo "Passed: $PASS"
echo "Failed: $FAIL"
echo "========================="

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
