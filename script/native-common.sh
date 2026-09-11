#!/bin/bash

set -euo pipefail

NATIVE_PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NATIVE_ENV_FILE="${NATIVE_ENV_FILE:-$NATIVE_PROJECT_ROOT/.env.native}"
NATIVE_PID_FILE="${NATIVE_PID_FILE:-$NATIVE_PROJECT_ROOT/.orzmusic/native.pid}"
NATIVE_LOG_FILE="${NATIVE_LOG_FILE:-$NATIVE_PROJECT_ROOT/.orzmusic/native.log}"

# curl 丢弃响应体的目标。Windows/MSYS（git-bash、MSYS2）不会把 /dev/null 转成 NUL，
# mingw 版 curl 会写失败并以 23 退出，从而误判端口/健康状态（#10）。
NATIVE_NULL_DEVICE="/dev/null"
case "$(uname -s 2>/dev/null)" in
    MINGW*|MSYS*|CYGWIN*) NATIVE_NULL_DEVICE="NUL" ;;
esac

native_load_env() {
    if [ -f "$NATIVE_ENV_FILE" ]; then
        set -a
        # shellcheck disable=SC1090
        . "$NATIVE_ENV_FILE"
        set +a
    fi

    : "${DATABASE_HOST:=localhost}"
    : "${DATABASE_PORT:=5432}"
    : "${DATABASE_NAME:=vapor_database}"
    : "${DATABASE_USERNAME:=vapor_username}"
    : "${CAS_ROOT:=$NATIVE_PROJECT_ROOT/data/music}"
    : "${SCAN_ROOT:=$NATIVE_PROJECT_ROOT/keygenmusic}"
    : "${APP_PORT:=8080}"
    export DATABASE_HOST DATABASE_PORT DATABASE_NAME DATABASE_USERNAME
    export CAS_ROOT SCAN_ROOT APP_PORT
}

native_require_commands() {
    local missing=()
    for command_name in "$@"; do
        command -v "$command_name" >/dev/null 2>&1 || missing+=("$command_name")
    done
    if [ "${#missing[@]}" -gt 0 ]; then
        echo "ERROR: missing command(s): ${missing[*]}" >&2
        return 1
    fi
}

native_require_config() {
    if [ -z "${DATABASE_PASSWORD:-}" ]; then
        echo "ERROR: DATABASE_PASSWORD is required" >&2
        return 1
    fi
    if [ -z "${ADMIN_API_TOKEN:-}" ]; then
        echo "ERROR: ADMIN_API_TOKEN is required" >&2
        return 1
    fi
    if [ ! -d "$SCAN_ROOT" ] || [ ! -r "$SCAN_ROOT" ]; then
        echo "ERROR: SCAN_ROOT is not a readable directory: $SCAN_ROOT" >&2
        return 1
    fi
}

native_pid_running() {
    [ -s "$NATIVE_PID_FILE" ] || return 1
    local pid
    pid="$(cat "$NATIVE_PID_FILE")"
    [[ "$pid" =~ ^[0-9]+$ ]] || return 1
    kill -0 "$pid" 2>/dev/null || return 1
    ps -p "$pid" -o command= 2>/dev/null | grep -Eq 'OrzMusicService|\.build/.*/Run' || return 1
}
