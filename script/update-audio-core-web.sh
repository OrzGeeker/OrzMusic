#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCK="$ROOT/audio-core-sdk.lock.json"
OUT="$ROOT/Resources/Public/audio"

read_lock() { node -p "require('$LOCK').$1"; }
read_asset() {
  node -p "const a = require('$LOCK').assets.find(a => a.platform === '$1' && a.profile === '$2'); a ? a.$3 : ''"
}
repo="$(read_lock repository)"
tag="$(read_lock tag)"
version="$(read_lock version)"
asset="$(read_asset web full name)"
expected="$(read_asset web full sha256)"
if [[ -z "$asset" || -z "$expected" ]]; then
  echo "Missing OrzAudioCore web/full asset in audio-core-sdk.lock.json" >&2
  exit 1
fi
work="$(mktemp -d "${TMPDIR:-/tmp}/orz-audio-core.XXXXXX")"
archive="$work/$asset"

curl -fsSL "$repo/releases/download/$tag/$asset" -o "$archive"
actual="$(shasum -a 256 "$archive" | awk '{print $1}')"
if [[ "$actual" != "$expected" ]]; then
  echo "OrzAudioCore asset checksum mismatch: expected $expected, got $actual" >&2
  exit 1
fi

tar -xzf "$archive" -C "$work"
manifest_version="$(node -p "require('$work/package/metadata/decoder-manifest.json').sdkVersion")"
test "$manifest_version" = "$version"
test -s "$work/package/wasm/orz_audio_builtin.js"
test -s "$work/package/wasm/orz_audio_builtin.wasm"
test -s "$work/package/wasm/orz_audio.js"
test -s "$work/package/wasm/orz_audio.wasm"
cp "$work/package/wasm/orz_audio_builtin.js" "$OUT/orz_audio_builtin.js"
cp "$work/package/wasm/orz_audio_builtin.wasm" "$OUT/orz_audio_builtin.wasm"
cp "$work/package/wasm/orz_audio.js" "$OUT/orz_audio.js"
cp "$work/package/wasm/orz_audio.wasm" "$OUT/orz_audio.wasm"

echo "Installed OrzAudioCore $version web assets ($expected)"
