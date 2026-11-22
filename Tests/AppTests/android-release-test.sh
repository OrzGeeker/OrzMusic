#!/bin/bash
# android-release.sh 的生成结果回归测试
#
# 可直接运行（无需 Docker / Android SDK / 网络）：
#   bash Tests/AppTests/android-release-test.sh
#
# 为什么要有这个文件：
#   `clients/android/scripts/android-release.sh` 的 CHANGELOG 插入点是从旧文件里
#   `## [` 前那个换行算出来的，条目本身少一个结尾换行时，新条目的最后一行会和
#   上一个版本的标题贴在一起（0.8.13 发版时踩过一次，只能手改 CHANGELOG）。
#   这里用一个本地 bare 仓库当 origin，把真实的 release 脚本跑一遍再看产物。
#
# 覆盖：
#   - 版本号按 patch 递进（0.8.12 → 0.8.13）
#   - 新条目与上一个版本标题之间有空行
#   - version.properties 写入新版本并追加历史行
#   - 发版分支被推到 origin

set -uo pipefail

# macOS 自带 bash 3.2 没有 mapfile，脚本本身跑不起来 —— 跳过而不是假红。
if ! (exec 3</dev/null; type mapfile >/dev/null 2>&1); then
    echo "SKIP: 当前 bash 没有 mapfile（macOS bash 3.2），跳过 android-release 生成测试"
    exit 0
fi
if ! command -v python3 >/dev/null 2>&1; then
    echo "SKIP: 没有 python3，跳过 android-release 生成测试"
    exit 0
fi
if ! command -v git >/dev/null 2>&1; then
    echo "SKIP: 没有 git，跳过 android-release 生成测试"
    exit 0
fi

TEST_DIR="$PWD"
RELEASE_SH="$TEST_DIR/clients/android/scripts/android-release.sh"
PASS=0
FAIL=0

green() { printf '\033[32m%s\033[0m\n' "$1"; }
red()   { printf '\033[31m%s\033[0m\n' "$1"; }

if [ ! -f "$RELEASE_SH" ]; then
    red "FAIL: 找不到 $RELEASE_SH"
    exit 1
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/android-release-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

# ── 沙箱:bare origin + 工作副本，结构与真实仓库一致 ───────────────────
git init -q --bare "$WORK/origin.git" || exit 1
git clone -q "$WORK/origin.git" "$WORK/repo" || exit 1
REPO="$WORK/repo"
git -C "$REPO" config user.email "test@example.com"
git -C "$REPO" config user.name "android-release-test"
git -C "$REPO" checkout -q -b main

mkdir -p "$REPO/clients/android/scripts"
cp "$RELEASE_SH" "$REPO/clients/android/scripts/android-release.sh"

cat > "$REPO/clients/android/version.properties" <<'EOF'
# 版本历史
#   0.8.12  2026-11-22  见 CHANGELOG.md
# ─────────────────────────────────────────────────────────────
version=0.8.12
EOF

cat > "$REPO/clients/android/CHANGELOG.md" <<'EOF'
# 更新日志 · OrzMusic Android 客户端

## [0.8.12](https://example.invalid/compare/android-v0.8.11...android-v0.8.12) - 2026-11-22

### 修复

- fix(android): 上一条历史条目
EOF

git -C "$REPO" add -A
git -C "$REPO" commit -q -m "chore(android): 初始状态"
git -C "$REPO" tag -a android-v0.8.12 -m "0.8.12"
git -C "$REPO" push -q -u origin main --tags || exit 1

# 上一次 tag 之后的一条客户端改动 —— 这是发版要归纳的内容
printf 'noop\n' > "$REPO/clients/android/README.md"
git -C "$REPO" add -A
git -C "$REPO" commit -q -m "fix(android): 测试用的修复提交"
git -C "$REPO" push -q origin main || exit 1

# ── 跑真实的发版脚本(准备分支那一步) ────────────────────────────────
OUTPUT="$(cd "$REPO" && bash clients/android/scripts/android-release.sh --yes 2>&1)"
RC=$?

echo "=== Test 1: 脚本退出码为 0 ==="
if [ "$RC" -eq 0 ]; then
    green "PASS"
    PASS=$((PASS + 1))
else
    red "FAIL(rc=$RC): $(echo "$OUTPUT" | tail -5)"
    FAIL=$((FAIL + 1))
fi

echo "=== Test 2: version.properties 递进到 0.8.13 并追加历史行 ==="
VF_CONTENT="$(git -C "$REPO" show main:clients/android/version.properties 2>/dev/null || cat "$REPO/clients/android/version.properties")"
# 脚本把改动提交在发版分支上
VF_CONTENT="$(git -C "$REPO" show release/android-0.8.13:clients/android/version.properties 2>/dev/null || echo "$VF_CONTENT")"
if printf '%s\n' "$VF_CONTENT" | grep -qx 'version=0.8.13' \
   && printf '%s\n' "$VF_CONTENT" | grep -q '^#   0\.8\.13  '; then
    green "PASS"
    PASS=$((PASS + 1))
else
    red "FAIL: version.properties 没有按预期更新"
    printf '%s\n' "$VF_CONTENT" | sed 's/^/     /'
    FAIL=$((FAIL + 1))
fi

echo "=== Test 3: 新条目末尾与上一个版本标题之间有空行 ==="
CLE_CONTENT="$(git -C "$REPO" show release/android-0.8.13:clients/android/CHANGELOG.md 2>/dev/null || echo "")"
# 取 '## [0.8.12]' 之前的一行，必须是空行
LINE_BEFORE="$(printf '%s\n' "$CLE_CONTENT" | grep -B1 '^## \[0\.8\.12\]' | head -1)"
if printf '%s\n' "$CLE_CONTENT" | grep -q '^## \[0\.8\.13\]' \
   && [ -z "$(printf '%s' "$LINE_BEFORE" | tr -d '[:space:]')" ]; then
    green "PASS"
    PASS=$((PASS + 1))
else
    red "FAIL: CHANGELOG 条目与上一个版本标题之间缺少空行"
    printf '%s\n' "$CLE_CONTENT" | sed -n '1,20p' | sed 's/^/     /'
    FAIL=$((FAIL + 1))
fi

echo "=== Test 4: 发版分支已推到 origin ==="
if git -C "$WORK/origin.git" rev-parse -q --verify refs/heads/release/android-0.8.13 >/dev/null; then
    green "PASS"
    PASS=$((PASS + 1))
else
    red "FAIL: origin 上没有 release/android-0.8.13"
    FAIL=$((FAIL + 1))
fi

echo
echo "========= Results ========="
echo "Passed: $PASS"
echo "Failed: $FAIL"
echo "==========================="
[ "$FAIL" -eq 0 ] || exit 1
