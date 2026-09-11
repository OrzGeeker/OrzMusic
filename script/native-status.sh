#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=native-common.sh
. "$SCRIPT_DIR/native-common.sh"
native_load_env

if ! native_pid_running; then
    echo "Native OrzMusic: stopped"
    if command -v curl >/dev/null 2>&1 && curl -s --max-time 2 -o "$NATIVE_NULL_DEVICE" \
        "http://127.0.0.1:$APP_PORT/api/health"; then
        echo "Port $APP_PORT is serving an unmanaged HTTP process (possibly Docker)."
        exit 1
    fi
    echo "Health: unavailable"
    exit 0
fi

echo "Native OrzMusic: running (PID $(cat "$NATIVE_PID_FILE"))"
if command -v curl >/dev/null 2>&1 && curl -fsS --max-time 2 \
    "http://127.0.0.1:$APP_PORT/api/health"; then
    echo
    exit 0
fi

echo "Health: unavailable"
exit 1
