#!/bin/bash
# release-smoke 聚焦测试
#
# 在本地启动物 mock HTTP 服务，验证 smoke 脚本的成功和失败路径。
# 不需要 Docker 或运行中的服务。
#
# 用法: bash Tests/AppTests/release-smoke-test.sh

set -uo pipefail

SCRIPT="$PWD/script/release-smoke.sh"
PASS=0
FAIL=0
MOCK_PID=""

green() { printf '\033[32m%s\033[0m\n' "$1"; }
red()   { printf '\033[31m%s\033[0m\n' "$1"; }

cleanup() {
    if [ -n "$MOCK_PID" ] && kill -0 "$MOCK_PID" 2>/dev/null; then
        kill "$MOCK_PID" 2>/dev/null || true
        wait "$MOCK_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT

start_mock() {
    local port="$1"
    # 启动 Python mock HTTP 服务器
    python3 -c "
import json, http.server, socket, os

class MockHandler(http.server.BaseHTTPRequestHandler):
    def send_body(self, status, content_type, body, headers=None):
        self.send_response(status)
        self.send_header('Content-Type', content_type)
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == '/api/health':
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps({
                'status': os.environ.get('MOCK_HEALTH_STATUS', 'ready'),
                'version': '1.0.0',
                'commit': 'abc123',
                'database': 'healthy',
                'cas': 'healthy',
                'adminApi': os.environ.get('MOCK_ADMIN_API', 'enabled')
            }).encode())
        elif self.path == '/api/songs/formats':
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps({'total': 42, 'formats': [{'format': 'mp3', 'count': 10}]}).encode())
        elif self.path.startswith('/api/songs/search'):
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps([{'id': 'mock-id', 'title': 'Mock Song'}]).encode())
        elif self.path == '/':
            self.send_body(200, 'text/html',
                b'<html><head><title>OrzMusic</title></head><body>OrzMusic Player</body></html>',
                {'Cache-Control': 'no-cache'})
        elif self.path == '/vendor/alpinejs/alpine-3.15.12.min.js':
            self.send_body(200, 'application/javascript', b'compressed-mock',
                {'Cache-Control': 'public, max-age=31536000, immutable',
                 'Content-Encoding': 'gzip'})
        elif self.path == '/audio/orz_audio_builtin.wasm?v=20260717-controls-seek-v1':
            self.send_body(200, 'application/wasm', b'wasm-mock',
                {'Cache-Control': 'public, max-age=31536000, immutable'})
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, format, *args):
        pass  # suppress logs

server = http.server.HTTPServer(('127.0.0.1', $port), MockHandler)
server.serve_forever()
" &
    MOCK_PID=$!
    # 等待服务器就绪
    for i in $(seq 1 20); do
        if curl -fsS "http://127.0.0.1:$port/api/health" >/dev/null 2>&1; then
            return 0
        fi
        sleep 0.2
    done
    return 1
}

# 查找可用端口
find_port() {
    python3 -c "
import socket
s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.bind(('127.0.0.1', 0))
print(s.getsockname()[1])
s.close()
"
}

# Test 1: 模拟完全正常 — 冒烟通过
echo "=== Test 1: All endpoints healthy ==="
PORT=$(find_port)
if ! start_mock "$PORT"; then
    red "FAIL: Could not start mock server"
    FAIL=$((FAIL + 1))
else
    OUTPUT=$(SERVICE_URL="http://127.0.0.1:$PORT" bash "$SCRIPT" 2>&1) || true
    if echo "$OUTPUT" | grep -qi "SMOKE CHECK PASSED"; then
        green "PASS: Smoke check passed"
        PASS=$((PASS + 1))
    else
        red "FAIL: $(echo "$OUTPUT" | tail -5)"
        FAIL=$((FAIL + 1))
    fi
    kill "$MOCK_PID" 2>/dev/null || true
    wait "$MOCK_PID" 2>/dev/null || true
    MOCK_PID=""
fi

# Test 2: 版本不匹配 — 冒烟失败
echo "=== Test 2: Version mismatch ==="
PORT=$(find_port)
if ! start_mock "$PORT"; then
    red "FAIL: Could not start mock server"
    FAIL=$((FAIL + 1))
else
    OUTPUT=$(SERVICE_URL="http://127.0.0.1:$PORT" EXPECTED_VERSION="9.9.9" bash "$SCRIPT" 2>&1) || true
    if echo "$OUTPUT" | grep -qi "SMOKE CHECK FAILED" && echo "$OUTPUT" | grep -qi "version mismatch"; then
        green "PASS: Version mismatch detected"
        PASS=$((PASS + 1))
    else
        red "FAIL: Did not reject version mismatch"
        FAIL=$((FAIL + 1))
    fi
    kill "$MOCK_PID" 2>/dev/null || true
    wait "$MOCK_PID" 2>/dev/null || true
    MOCK_PID=""
fi

# Test 3: 服务不可达 — 超时/失败
echo "=== Test 3: Service unreachable ==="
OUTPUT=$(SERVICE_URL="http://127.0.0.1:1" SMOKE_TIMEOUT=2 bash "$SCRIPT" 2>&1) || true
if echo "$OUTPUT" | grep -qi "SMOKE CHECK FAILED"; then
    green "PASS: Unreachable service detected"
    PASS=$((PASS + 1))
else
    red "FAIL: Did not fail on unreachable service"
    FAIL=$((FAIL + 1))
fi

# Test 4: 调用方提供了令牌但服务端报告 adminApi=disabled — 冒烟必须失败
# （回归保护：ADMIN_API_TOKEN 未传进容器时不能静默“通过”）
echo "=== Test 4: adminApi disabled while token provided ==="
PORT=$(find_port)
if ! MOCK_ADMIN_API=disabled start_mock "$PORT"; then
    red "FAIL: Could not start mock server"
    FAIL=$((FAIL + 1))
else
    OUTPUT=$(SERVICE_URL="http://127.0.0.1:$PORT" ADMIN_API_TOKEN="test-token" bash "$SCRIPT" 2>&1) || true
    if echo "$OUTPUT" | grep -qi "SMOKE CHECK FAILED" && echo "$OUTPUT" | grep -qi "adminApi=disabled"; then
        green "PASS: Admin API gap detected"
        PASS=$((PASS + 1))
    else
        red "FAIL: Did not reject adminApi=disabled with token provided"
        FAIL=$((FAIL + 1))
    fi
    kill "$MOCK_PID" 2>/dev/null || true
    wait "$MOCK_PID" 2>/dev/null || true
    MOCK_PID=""
fi

# Test 5: 未提供令牌且服务端 disabled — 允许通过（仅告警）
echo "=== Test 5: adminApi disabled without token ==="
PORT=$(find_port)
if ! MOCK_ADMIN_API=disabled start_mock "$PORT"; then
    red "FAIL: Could not start mock server"
    FAIL=$((FAIL + 1))
else
    OUTPUT=$(env -u ADMIN_API_TOKEN SERVICE_URL="http://127.0.0.1:$PORT" bash "$SCRIPT" 2>&1) || true
    if echo "$OUTPUT" | grep -qi "SMOKE CHECK PASSED" && echo "$OUTPUT" | grep -qi "adminApi=disabled"; then
        green "PASS: Disabled admin API allowed with warning"
        PASS=$((PASS + 1))
    else
        red "FAIL: Disabled admin API without token should pass with warning"
        FAIL=$((FAIL + 1))
    fi
    kill "$MOCK_PID" 2>/dev/null || true
    wait "$MOCK_PID" 2>/dev/null || true
    MOCK_PID=""
fi

# Test 6: 缺少 JSON 解析器时明确失败，而不是降级成字段缺失
# （issue #7 回归保护：Windows 上只有 python、无 python3）
echo "=== Test 6: missing JSON parser fails fast ==="
NOPARSER_BIN=$(mktemp -d /tmp/no-parser-XXXXXX)
for tool in jq python3 python; do
    printf '#!/bin/bash\nexit 1\n' > "$NOPARSER_BIN/$tool"
    chmod +x "$NOPARSER_BIN/$tool"
done
OUTPUT=$(PATH="$NOPARSER_BIN:$PATH" SERVICE_URL="http://127.0.0.1:1" bash "$SCRIPT" 2>&1) || true
rm -rf "$NOPARSER_BIN"
if echo "$OUTPUT" | grep -qi "required to parse JSON"; then
    green "PASS: missing parser reported clearly"
    PASS=$((PASS + 1))
else
    red "FAIL: missing parser was not reported: $(echo "$OUTPUT" | head -3)"
    FAIL=$((FAIL + 1))
fi

# Test 7: MSYS/MINGW 下丢弃 body 必须用 NUL，避免 curl -o /dev/null 退出 23（#10）
echo "=== Test 7: MSYS uses NUL for discarded bodies ==="
TESTDIR_NUL=$(mktemp -d /tmp/release-smoke-nul-XXXXXX)
mkdir -p "$TESTDIR_NUL/mock-bin"
CURL_LOG="$TESTDIR_NUL/curl.log"
cat > "$TESTDIR_NUL/mock-bin/uname" <<'MOCK'
#!/bin/bash
echo "MINGW64_NT-10.0-19045"
MOCK
cat > "$TESTDIR_NUL/mock-bin/curl" <<MOCK
#!/bin/bash
printf '%s\n' "\$*" >> "$CURL_LOG"
printf 'HTTP/1.1 200 OK\r\nCache-Control: public, max-age=31536000, immutable\r\nContent-Encoding: gzip\r\n\r\n'
exit 0
MOCK
chmod +x "$TESTDIR_NUL/mock-bin/uname" "$TESTDIR_NUL/mock-bin/curl"
OUTPUT=$(PATH="$TESTDIR_NUL/mock-bin:$PATH" SERVICE_URL="http://127.0.0.1:1" bash "$SCRIPT" 2>&1) || true
USED_NUL=$(grep -c -- "-o NUL" "$CURL_LOG" 2>/dev/null || true)
USED_DEVNULL=$(grep -c -- "-o /dev/null" "$CURL_LOG" 2>/dev/null || true)
rm -rf "$TESTDIR_NUL"
if [ "$USED_NUL" -ge 3 ] && [ "$USED_DEVNULL" -eq 0 ]; then
    green "PASS: discarded bodies go to NUL under MSYS"
    PASS=$((PASS + 1))
else
    red "FAIL: expected NUL device (NUL=$USED_NUL, /dev/null=$USED_DEVNULL)"
    FAIL=$((FAIL + 1))
fi

# Test 8: 前段失败时 Format/Search 仍执行并打印结果（不再静默跳过，#10）
echo "=== Test 8: format/search still run after earlier failure ==="
PORT=$(find_port)
if ! MOCK_HEALTH_STATUS=degraded start_mock "$PORT"; then
    red "FAIL: Could not start mock server"
    FAIL=$((FAIL + 1))
else
    OUTPUT=$(SERVICE_URL="http://127.0.0.1:$PORT" bash "$SCRIPT" 2>&1) || true
    if echo "$OUTPUT" | grep -q "SMOKE CHECK FAILED" \
        && echo "$OUTPUT" | grep -q "formats total=42" \
        && echo "$OUTPUT" | grep -q "search returned 1 results"; then
        green "PASS: later sections still report results"
        PASS=$((PASS + 1))
    else
        red "FAIL: later sections were silently skipped"
        echo "$OUTPUT" | grep -E 'Format Summary|formats total|Search API|search returned' | sed 's/^/  /'
        FAIL=$((FAIL + 1))
    fi
    kill "$MOCK_PID" 2>/dev/null || true
    wait "$MOCK_PID" 2>/dev/null || true
    MOCK_PID=""
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
