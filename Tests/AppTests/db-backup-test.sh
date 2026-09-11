#!/bin/bash
# db-backup.sh 参数校验聚焦测试
#
# 可直接运行（无需数据库）：
#   bash Tests/AppTests/db-backup-test.sh
#
# 验证内容：
#   - 缺少 VERSION 时失败
#   - VERSION 从文件回退
#   - 防静默覆盖
#   - 备份目录不可写时失败

set -uo pipefail

SCRIPT="$PWD/script/db-backup.sh"
PASS=0
FAIL=0

cleanup() {
    local dir="$1"
    if [ -d "$dir" ]; then
        chmod -R 755 "$dir" 2>/dev/null || true
        rm -rf "$dir"
    fi
}

green() { printf '\033[32m%s\033[0m\n' "$1"; }
red()   { printf '\033[31m%s\033[0m\n' "$1"; }

# 创建隔离测试目录 + mock 工具
setup_mock_env() {
    local TESTDIR
    TESTDIR=$(mktemp -d /tmp/db-backup-test-XXXXXX)
    mkdir -p "$TESTDIR/backups" "$TESTDIR/mock-bin"

    # mock docker: pg_dump 模拟成功；cp 模拟创建非空备份文件
    cat > "$TESTDIR/mock-bin/docker" <<'MOCK'
#!/bin/bash
if [ -n "${MOCK_ARG_LOG:-}" ]; then
    printf '%s\n' "$@" >> "$MOCK_ARG_LOG"
fi
if echo "$@" | grep -q "pg_dump"; then
    exit 0
fi
if echo "$@" | grep -q "cp"; then
    dest="${@: -1}"
    # 创建非空备份文件（模拟成功的 pg_dump 输出）
    echo "mock backup content" > "$dest"
    exit 0
fi
exit 0
MOCK
    chmod +x "$TESTDIR/mock-bin/docker"

    # mock date: 固定时间戳使文件名可预测
    cat > "$TESTDIR/mock-bin/date" <<'MOCK'
#!/bin/bash
echo "20240101T000000Z"
MOCK
    chmod +x "$TESTDIR/mock-bin/date"

    echo "$TESTDIR"
}

# 在隔离环境运行脚本
run_script() {
    local TESTDIR fixture_output
    TESTDIR=$(setup_mock_env)
    # 兼容 macOS bash 3.2：set -u 下 "$@" / 空数组展开会报 unbound variable。
    local extra_env=()
    if [ "$#" -gt 0 ]; then
        extra_env=("$@")
    fi

    # 在子 shell 中运行
    set +e
    fixture_output=$(
        cd "$TESTDIR" || exit 1
        export PATH="$TESTDIR/mock-bin:$PATH"
        export BACKUP_DIR="$TESTDIR/backups"
        if [ "${#extra_env[@]}" -gt 0 ]; then
            env "${extra_env[@]}" bash "$SCRIPT" 2>&1
        else
            bash "$SCRIPT" 2>&1
        fi
    )
    local rc=$?
    set -e

    cleanup "$TESTDIR"
    echo "$fixture_output"
    return $rc
}

# Test 1: 无 VERSION 时失败
echo "=== Test 1: No VERSION set ==="
OUTPUT=$(run_script) || true
if echo "$OUTPUT" | grep -qi "ERROR.*VERSION"; then
    green "PASS: Rejected without VERSION"
    PASS=$((PASS + 1))
else
    red "FAIL: Wrong error message: $OUTPUT"
    FAIL=$((FAIL + 1))
fi

# Test 2: 空 VERSION 时失败
echo "=== Test 2: Empty VERSION ==="
OUTPUT=$(run_script VERSION="") || true
if echo "$OUTPUT" | grep -qi "ERROR.*VERSION"; then
    green "PASS: Rejected empty VERSION"
    PASS=$((PASS + 1))
else
    red "FAIL: Wrong error message: $OUTPUT"
    FAIL=$((FAIL + 1))
fi

# Test 3: 合法 VERSION 通过参数校验并生成备份
echo "=== Test 3: Valid VERSION passed ==="
OUTPUT=$(run_script VERSION="1.0.0") || true
if echo "$OUTPUT" | grep -qi "Backup saved"; then
    green "PASS: Accepted VERSION=1.0.0"
    PASS=$((PASS + 1))
else
    red "FAIL: Output: $(echo "$OUTPUT" | head -5)"
    FAIL=$((FAIL + 1))
fi

# Test 4: VERSION 从文件回退（无 VERSION 环境变量，但有 VERSION 文件）
echo "=== Test 4: VERSION from file fallback ==="
TESTDIR=$(setup_mock_env)
echo "1.2.3" > "$TESTDIR/VERSION"
set +e
OUTPUT=$(
    cd "$TESTDIR" || exit 1
    export PATH="$TESTDIR/mock-bin:$PATH"
    export BACKUP_DIR="$TESTDIR/backups"
    bash "$SCRIPT" 2>&1
)
set -e
cleanup "$TESTDIR"

if echo "$OUTPUT" | grep -qi "Backup saved" && echo "$OUTPUT" | grep -q "1.2.3"; then
    green "PASS: Version read from VERSION file"
    PASS=$((PASS + 1))
else
    red "FAIL: $(echo "$OUTPUT" | head -5)"
    FAIL=$((FAIL + 1))
fi

# Test 5: 防静默覆盖
echo "=== Test 5: Prevent silent overwrite ==="
TESTDIR=$(setup_mock_env)
# 预创建同名备份文件（匹配 mock date 的输出 + VERSION=1.0.0）
touch "$TESTDIR/backups/orzmusic-db-1.0.0-20240101T000000Z.dump"
set +e
OUTPUT=$(
    cd "$TESTDIR" || exit 1
    export PATH="$TESTDIR/mock-bin:$PATH"
    export BACKUP_DIR="$TESTDIR/backups"
    VERSION="1.0.0" bash "$SCRIPT" 2>&1
)
set -e
cleanup "$TESTDIR"
if echo "$OUTPUT" | grep -qi "already exists"; then
    green "PASS: Rejected silent overwrite"
    PASS=$((PASS + 1))
else
    red "FAIL: Did not reject overwrite: $(echo "$OUTPUT" | head -3)"
    FAIL=$((FAIL + 1))
fi

# Test 6: 不可写备份目录时失败（验证目录检测）
echo "=== Test 6: Unwritable backup directory ==="
TESTDIR=$(setup_mock_env)
mkdir -p "$TESTDIR/readonly"
chmod 555 "$TESTDIR/readonly"
set +e
OUTPUT=$(
    cd "$TESTDIR" || exit 1
    export PATH="$TESTDIR/mock-bin:$PATH"
    export BACKUP_DIR="$TESTDIR/readonly"
    VERSION="1.0.0" bash "$SCRIPT" 2>&1
)
RC=$?
set -e
chmod -R 755 "$TESTDIR"
cleanup "$TESTDIR"
# 目录不可写时脚本必须非 0 退出或明确报 ERROR，不能静默声称成功
if [ "$RC" -ne 0 ] || echo "$OUTPUT" | grep -qi "ERROR"; then
    green "PASS: Rejected unwritable directory"
    PASS=$((PASS + 1))
else
    red "FAIL: unwritable directory was not rejected: $(echo "$OUTPUT" | head -3)"
    FAIL=$((FAIL + 1))
fi

# Test 7: 容器内 /tmp 路径不露出为独立参数（MSYS 路径转换回归保护，issue #8）
echo "=== Test 7: container /tmp path stays inside sh -c ==="
TESTDIR=$(setup_mock_env)
ARG_LOG="$TESTDIR/docker-args.log"
set +e
OUTPUT=$(
    cd "$TESTDIR" || exit 1
    export PATH="$TESTDIR/mock-bin:$PATH"
    export BACKUP_DIR="$TESTDIR/backups"
    export MOCK_ARG_LOG="$ARG_LOG"
    VERSION="1.0.0" bash "$SCRIPT" 2>&1
)
set -e
RESULT=no
if echo "$OUTPUT" | grep -qi "Backup saved" && [ -f "$ARG_LOG" ] \
    && grep -q 'pg_dump.*-f "/tmp/' "$ARG_LOG" \
    && ! grep -qE '^/tmp/orzmusic-db-[^/]*\.dump$' "$ARG_LOG"; then
    RESULT=yes
fi
ARG_COUNT=$(grep -c . "$ARG_LOG" 2>/dev/null || true)
cleanup "$TESTDIR"
if [ "$RESULT" = yes ]; then
    green "PASS: /tmp path reaches the container shell, not native docker.exe args"
    PASS=$((PASS + 1))
else
    red "FAIL: container /tmp path leaked as standalone arg (logged args: $ARG_COUNT)"
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
