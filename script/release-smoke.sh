#!/bin/bash
# release-smoke — 发布冒烟检查
#
# 升级后自动验收：健康接口、版本、首页、格式统计、搜索。
# 播放链路保留人工抽测，首期不自动播放音频。
#
# 用法:
#   ./script/release-smoke.sh
#   SERVICE_URL=http://localhost:8080 EXPECTED_VERSION=0.0.1 ./script/release-smoke.sh
#
# 环境变量:
#   SERVICE_URL       服务地址（默认 http://localhost:8080）
#   EXPECTED_VERSION  期望版本（可选，不设置则只检查存在性）
#   SMOKE_TIMEOUT     每个请求超时秒数（默认 10）

set -uo pipefail

SERVICE_URL="${SERVICE_URL:-http://localhost:8080}"
SMOKE_TIMEOUT="${SMOKE_TIMEOUT:-10}"
CURL="curl -fsS --max-time $SMOKE_TIMEOUT"
PASS=true

red()    { printf '\033[31m%s\033[0m\n' "$1"; }
green()  { printf '\033[32m%s\033[0m\n' "$1"; }
yellow() { printf '\033[33m%s\033[0m\n' "$1"; }

echo "=== Release Smoke Check ==="
echo "Target: $SERVICE_URL"
if [ -n "${EXPECTED_VERSION:-}" ]; then
    echo "Expected version: $EXPECTED_VERSION"
fi
echo ""

# ---- 1. 健康接口 ----
echo "--- 1. Health Check ---"
HEALTH=$($CURL "$SERVICE_URL/api/health" 2>&1) || {
    red "FAIL: Cannot reach /api/health"
    PASS=false
}
if [ "$PASS" = true ]; then
    STATUS=$(echo "$HEALTH" | python3 -c "import sys,json; print(json.load(sys.stdin)['status'])" 2>/dev/null || echo "")
    VERSION=$(echo "$HEALTH" | python3 -c "import sys,json; print(json.load(sys.stdin)['version'])" 2>/dev/null || echo "")
    DB=$(echo "$HEALTH" | python3 -c "import sys,json; print(json.load(sys.stdin)['database'])" 2>/dev/null || echo "")
    CAS=$(echo "$HEALTH" | python3 -c "import sys,json; print(json.load(sys.stdin)['cas'])" 2>/dev/null || echo "")
    ADMIN_API=$(echo "$HEALTH" | python3 -c "import sys,json; print(json.load(sys.stdin).get('adminApi',''))" 2>/dev/null || echo "")

    if [ "$STATUS" = "ready" ]; then
        green "  [PASS] status=ready"
    else
        red "  [FAIL] status=$STATUS (expected 'ready')"
        PASS=false
    fi

    if [ -n "$VERSION" ]; then
        green "  [PASS] version=$VERSION"
    else
        red "  [FAIL] version is empty"
        PASS=false
    fi

    if [ -n "${EXPECTED_VERSION:-}" ] && [ "$VERSION" != "$EXPECTED_VERSION" ]; then
        red "  [FAIL] version mismatch: expected $EXPECTED_VERSION, got $VERSION"
        PASS=false
    fi

    if [ "$DB" = "healthy" ]; then
        green "  [PASS] database=healthy"
    else
        red "  [FAIL] database=$DB"
        PASS=false
    fi

    if [ "$CAS" = "healthy" ]; then
        green "  [PASS] cas=healthy"
    else
        red "  [FAIL] cas=$CAS"
        PASS=false
    fi

    # 管理 API 是可选能力，但“调用方提供了令牌、服务端却报告 disabled”
    # 说明令牌没有传进容器/进程，属于升级后静默缺失功能，必须失败。
    if [ "$ADMIN_API" = "enabled" ]; then
        green "  [PASS] adminApi=enabled"
    elif [ -n "${ADMIN_API_TOKEN:-}" ]; then
        red "  [FAIL] adminApi=$ADMIN_API but ADMIN_API_TOKEN was provided to the smoke check"
        PASS=false
    else
        yellow "  [WARN] adminApi=$ADMIN_API (set ADMIN_API_TOKEN to enable scan/upload/delete)"
    fi
fi
echo ""

# ---- 2. 首页 ----
echo "--- 2. Frontend Page ---"
PAGE=$($CURL "$SERVICE_URL/" 2>&1) || {
    red "FAIL: Cannot reach frontend page"
    PASS=false
}
if [ "$PASS" = true ]; then
    if echo "$PAGE" | grep -qi "OrzMusic"; then
        green "  [PASS] Page contains OrzMusic"
    else
        red "  [FAIL] Page does not contain 'OrzMusic'"
        PASS=false
    fi
fi
echo ""

# ---- 3. 静态交付策略 ----
echo "--- 3. Static Delivery ---"
HOME_HEADERS=$($CURL -D - -o /dev/null "$SERVICE_URL/" 2>&1) || {
    red "  [FAIL] cannot read frontend headers"
    PASS=false
}
if ! echo "$HOME_HEADERS" | grep -qi '^cache-control:.*no-cache'; then
    red "  [FAIL] frontend Cache-Control is not no-cache"
    PASS=false
else
    green "  [PASS] frontend uses no-cache"
fi

ALPINE_PATH="/vendor/alpinejs/alpine-3.15.12.min.js"
ALPINE_HEADERS=$($CURL -H "Accept-Encoding: gzip" -D - -o /dev/null "$SERVICE_URL$ALPINE_PATH" 2>&1) || {
    red "  [FAIL] cannot read vendored Alpine"
    PASS=false
}
if echo "$ALPINE_HEADERS" | grep -qi '^cache-control:.*max-age=31536000.*immutable'; then
    green "  [PASS] versioned JS uses immutable cache"
else
    red "  [FAIL] versioned JS is not immutable"
    PASS=false
fi
if echo "$ALPINE_HEADERS" | grep -Eqi '^content-encoding: *(gzip|br)'; then
    green "  [PASS] versioned JS is compressed"
else
    red "  [FAIL] versioned JS is not gzip/br compressed"
    PASS=false
fi

WASM_HEADERS=$($CURL -D - -o /dev/null \
    "$SERVICE_URL/audio/orz_audio_builtin.wasm?v=20260717-controls-seek-v1" 2>&1) || {
    red "  [FAIL] cannot read builtin WASM"
    PASS=false
}
if echo "$WASM_HEADERS" | grep -qi '^content-type: *application/wasm' &&
   echo "$WASM_HEADERS" | grep -qi '^cache-control:.*immutable'; then
    green "  [PASS] WASM MIME and immutable cache are correct"
else
    red "  [FAIL] WASM MIME or cache policy is incorrect"
    PASS=false
fi
echo ""

# ---- 4. 格式统计 ----
echo "--- 4. Format Summary ---"
FORMATS=$($CURL "$SERVICE_URL/api/songs/formats" 2>&1) || {
    red "FAIL: Cannot reach /api/songs/formats"
    PASS=false
}
if [ "$PASS" = true ]; then
    TOTAL=$(echo "$FORMATS" | python3 -c "import sys,json; print(json.load(sys.stdin)['total'])" 2>/dev/null || echo "")
    # total >= 0 means the endpoint works
    if [ "$TOTAL" -ge 0 ] 2>/dev/null; then
        green "  [PASS] formats total=$TOTAL"
    else
        red "  [FAIL] could not parse format total"
        PASS=false
    fi
fi
echo ""

# ---- 5. 搜索接口 ----
echo "--- 5. Search API ---"
SEARCH=$($CURL "$SERVICE_URL/api/songs/search?q=test" 2>&1) || {
    red "FAIL: Cannot reach /api/songs/search"
    PASS=false
}
if [ "$PASS" = true ]; then
    # 搜索应该返回一个 JSON 数组（可能为空）
    if echo "$SEARCH" | python3 -c "import sys,json; data=json.load(sys.stdin); assert isinstance(data, list)" 2>/dev/null; then
        COUNT=$(echo "$SEARCH" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))" 2>/dev/null || echo "0")
        green "  [PASS] search returned $COUNT results"
    else
        red "  [FAIL] search did not return a JSON array"
        PASS=false
    fi
fi
echo ""

# ---- 汇总 ----
echo "=== Result ==="
if [ "$PASS" = true ]; then
    green "SMOKE CHECK PASSED"
    exit 0
else
    red "SMOKE CHECK FAILED"
    echo ""
    echo "Manual playback verification checklist (not automated):"
    echo "  - Play a directFile format (mp3) — should play immediately"
    echo "  - Play a wasmDecode format (xm/mod/sid) — should load WASM and play"
    echo "  - Play a serverDecode format (sc68/wav) — should transcode and play"
    echo "  - Test seek, pause, volume, next/prev"
    exit 1
fi
