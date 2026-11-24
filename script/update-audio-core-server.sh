#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCK="$ROOT/audio-core-sdk.lock.json"
DEST="${ORZ_AUDIO_CORE_SERVER_DIR:-$ROOT/.audio-core-sdk/server}"

read_json_string() {
  sed -n "s/.*\"$2\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" "$1" | head -n 1
}
read_lock() { read_json_string "$LOCK" "$1"; }

# Read name/sha256 for one schema-v1 assets[] entry. awk keeps this working in
# the Docker build image, which has no node/python.
read_lock_asset() {
  awk -v platform="$1" -v profile="$2" -v field="$3" '
    function clean(v) { gsub(/[",]/, "", v); return v }
    /"name"[[:space:]]*:/     { name = clean($2) }
    /"platform"[[:space:]]*:/ { platform_value = clean($2) }
    /"profile"[[:space:]]*:/  { profile_value = clean($2) }
    /"sha256"[[:space:]]*:/   { sha = clean($2) }
    /^[[:space:]]*}[,]*[[:space:]]*$/ {
      if (platform_value == platform && profile_value == profile) {
        print (field == "name" ? name : sha)
        exit
      }
      name = platform_value = profile_value = sha = ""
    }
  ' "$LOCK"
}
repo="$(read_lock repository)"
tag="$(read_lock tag)"
version="$(read_lock version)"

system="$(uname -s)"
machine="${ORZ_AUDIO_CORE_ARCH:-$(uname -m)}"
case "$system:$machine" in
  Linux:x86_64|Linux:amd64)
    platform="linux-x86_64"
    profile="server"
    library="libOrzAudioCore.so"
    ;;
  Linux:arm64|Linux:aarch64)
    platform="linux-arm64"
    profile="server"
    library="libOrzAudioCore.so"
    ;;
  Darwin:arm64)
    platform="macos-arm64"
    profile="full"
    library="libOrzAudioCore.dylib"
    ;;
  *)
    echo "Unsupported OrzAudioCore native platform: $system $machine" >&2
    exit 1
    ;;
esac

asset="$(read_lock_asset "$platform" "$profile" name)"
expected="$(read_lock_asset "$platform" "$profile" sha256)"
if [[ -z "$asset" || -z "$expected" ]]; then
  echo "Missing OrzAudioCore $platform $profile asset in audio-core-sdk.lock.json" >&2
  exit 1
fi
work="$(mktemp -d "${TMPDIR:-/tmp}/orz-audio-core-server.XXXXXX")"
trap 'rm -rf "$work"' EXIT
archive="$work/$asset"

curl -fsSL --retry 4 --retry-all-errors "$repo/releases/download/$tag/$asset" -o "$archive"
actual="$(shasum -a 256 "$archive" | awk '{print $1}')"
if [[ "$actual" != "$expected" ]]; then
  echo "OrzAudioCore server asset checksum mismatch: expected $expected, got $actual" >&2
  exit 1
fi

mkdir -p "$work/unpacked"
tar -xzf "$archive" -C "$work/unpacked"
stage="$work/unpacked/OrzAudioCore-$version-$platform-$profile"
test -s "$stage/native/lib/$library"
test -s "$stage/native/include/orz_audio_core.h"
test -s "$stage/metadata/decoder-manifest.json"
test "$(read_json_string "$stage/metadata/decoder-manifest.json" sdkVersion)" = "$version"

rm -rf "$DEST.new"
mkdir -p "$(dirname "$DEST")"
mv "$stage" "$DEST.new"
rm -rf "$DEST"
mv "$DEST.new" "$DEST"
printf 'Installed OrzAudioCore %s %s/%s native SDK (%s)\n' "$version" "$platform" "$profile" "$expected"
