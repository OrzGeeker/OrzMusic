#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=native-common.sh
. "$SCRIPT_DIR/native-common.sh"
native_load_env
native_require_commands curl
native_require_config

SERVICE_BINARY="$NATIVE_PROJECT_ROOT/.build/release/OrzMusicService"
if [ ! -x "$SERVICE_BINARY" ]; then
    echo "ERROR: release binary not found; run make native-install first" >&2
    exit 1
fi

if native_pid_running; then
    echo "Native OrzMusic is already running (PID $(cat "$NATIVE_PID_FILE"))"
    exit 0
fi

health_url="http://127.0.0.1:$APP_PORT/api/health"
if curl -s --max-time 2 -o "$NATIVE_NULL_DEVICE" "$health_url"; then
    echo "ERROR: port $APP_PORT is already serving HTTP, but it is not the managed Native OrzMusic process" >&2
    echo "Stop the process or Docker service using the port before starting Native OrzMusic." >&2
    exit 1
fi

mkdir -p "$(dirname "$NATIVE_PID_FILE")"
rm -f "$NATIVE_PID_FILE"
cd "$NATIVE_PROJECT_ROOT"
nohup "$SERVICE_BINARY" serve --env production --hostname 0.0.0.0 --port "$APP_PORT" \
    >>"$NATIVE_LOG_FILE" 2>&1 < /dev/null &
echo $! > "$NATIVE_PID_FILE"

for _ in $(seq 1 30); do
    if ! native_pid_running; then
        rm -f "$NATIVE_PID_FILE"
        echo "ERROR: Native OrzMusic exited before becoming ready; see $NATIVE_LOG_FILE" >&2
        exit 1
    fi
    if curl -fsS --max-time 2 "$health_url" \
        | grep -Eq '"status"[[:space:]]*:[[:space:]]*"ready"'; then
        echo "Native OrzMusic is ready on http://127.0.0.1:$APP_PORT"
        exit 0
    fi
    sleep 1
done

echo "ERROR: Native OrzMusic did not become ready; see $NATIVE_LOG_FILE" >&2
"$SCRIPT_DIR/native-down.sh" || true
exit 1
