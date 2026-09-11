#!/bin/bash

set -uo pipefail

PROJECT_ROOT="$PWD"
PASS=0
FAIL=0

pass() { echo "PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "FAIL: $1"; FAIL=$((FAIL + 1)); }

make_fixture() {
    local fixture_root="$1"
    mkdir -p "$fixture_root/script" "$fixture_root/.build/release" "$fixture_root/mock-bin" "$fixture_root/music"
    cp "$PROJECT_ROOT/script/native-up.sh" "$fixture_root/script/"
    cp "$PROJECT_ROOT/script/native-common.sh" "$fixture_root/script/"
    cp "$PROJECT_ROOT/script/native-status.sh" "$fixture_root/script/"
    chmod +x "$fixture_root/script/native-up.sh" "$fixture_root/script/native-status.sh"
    printf '%s\n' \
        'DATABASE_PASSWORD=test-password' \
        'ADMIN_API_TOKEN=test-token' \
        "SCAN_ROOT=$fixture_root/music" \
        'APP_PORT=18080' > "$fixture_root/.env.native"
}

TEST_ROOT="$(mktemp -d /tmp/orzmusic-native-script-test-XXXXXX)"
trap 'rm -rf "$TEST_ROOT"' EXIT

occupied_root="$TEST_ROOT/occupied"
make_fixture "$occupied_root"
printf '%s\n' '#!/bin/bash' 'exit 0' > "$occupied_root/mock-bin/curl"
printf '%s\n' '#!/bin/bash' "touch '$occupied_root/service-started'" 'sleep 5' > "$occupied_root/.build/release/OrzMusicService"
chmod +x "$occupied_root/mock-bin/curl" "$occupied_root/.build/release/OrzMusicService"

occupied_output="$(PATH="$occupied_root/mock-bin:$PATH" bash "$occupied_root/script/native-up.sh" 2>&1)"
occupied_status=$?
if [ "$occupied_status" -ne 0 ] \
    && echo "$occupied_output" | grep -q 'already serving HTTP' \
    && [ ! -e "$occupied_root/service-started" ]; then
    pass "native-up rejects an HTTP service already using APP_PORT"
else
    fail "native-up did not reject occupied port: $occupied_output"
fi

exit_root="$TEST_ROOT/exits"
make_fixture "$exit_root"
printf '%s\n' '#!/bin/bash' 'exit 7' > "$exit_root/mock-bin/curl"
printf '%s\n' '#!/bin/bash' 'exit 1' > "$exit_root/.build/release/OrzMusicService"
chmod +x "$exit_root/mock-bin/curl" "$exit_root/.build/release/OrzMusicService"

exit_output="$(PATH="$exit_root/mock-bin:$PATH" bash "$exit_root/script/native-up.sh" 2>&1)"
exit_status=$?
if [ "$exit_status" -ne 0 ] \
    && echo "$exit_output" | grep -q 'exited before becoming ready' \
    && [ ! -e "$exit_root/.orzmusic/native.pid" ]; then
    pass "native-up rejects a child process that exits during startup"
else
    fail "native-up accepted an exited child: $exit_output"
fi

status_root="$TEST_ROOT/status"
make_fixture "$status_root"
printf '%s\n' '#!/bin/bash' 'exit 0' > "$status_root/mock-bin/curl"
chmod +x "$status_root/mock-bin/curl"

status_output="$(PATH="$status_root/mock-bin:$PATH" bash "$status_root/script/native-status.sh" 2>&1)"
status_code=$?
if [ "$status_code" -ne 0 ] \
    && echo "$status_output" | grep -q 'Native OrzMusic: stopped' \
    && echo "$status_output" | grep -q 'unmanaged HTTP process'; then
    pass "native-status distinguishes an unmanaged HTTP service from Native OrzMusic"
else
    fail "native-status misidentified an unmanaged service: $status_output"
fi

# Test: MSYS/MINGW 下 native 脚本的 curl 丢弃 body 必须用 NUL（#10）
nul_root="$TEST_ROOT/nul"
make_fixture "$nul_root"
curl_log="$nul_root/curl-args.log"
cat > "$nul_root/mock-bin/uname" <<'MOCK'
#!/bin/bash
echo "MINGW64_NT-10.0-19045"
MOCK
cat > "$nul_root/mock-bin/curl" <<MOCK
#!/bin/bash
printf '%s\n' "\$*" >> "$curl_log"
exit 0
MOCK
chmod +x "$nul_root/mock-bin/uname" "$nul_root/mock-bin/curl"
PATH="$nul_root/mock-bin:$PATH" bash "$nul_root/script/native-up.sh" >/dev/null 2>&1 || true
PATH="$nul_root/mock-bin:$PATH" bash "$nul_root/script/native-status.sh" >/dev/null 2>&1 || true
if grep -q -- "-o NUL" "$curl_log" 2>/dev/null && ! grep -q -- "-o /dev/null" "$curl_log" 2>/dev/null; then
    pass "native scripts use a portable null device under MSYS"
else
    fail "native scripts used /dev/null under MSYS: $(cat "$curl_log" 2>/dev/null)"
fi

echo "Passed: $PASS"
echo "Failed: $FAIL"
[ "$FAIL" -eq 0 ]