#!/usr/bin/env bash
set -euo pipefail

# 安装/更新 Android 版 OrzAudioCore SDK(资产由 OrzAudioCore 的 android job 产出)
#
# 产地:
#   OrzAudioCore/.github/workflows/release.yml 的 `android` job →
#   OrzAudioCore-<ver>-android-full.tar.gz
#
# 装到哪:
#   clients/android/audio-kit/native/.sdk/          (gitignored)
#     android/<abi>/libOrzAudioCore.{a,so}
#     native/include/orz_audio_core.h
#     metadata/{decoder-manifest.json,android.json,sbom.cdx.json}
#     licenses/*
#
# 装完再跑 clients/android/audio-kit/scripts/build-android.sh,
# `native/cpp/CMakeLists.txt` 会自动走 ORZ_PREBUILT_CORE 分支(只编 JNI 胶水)。
#
# 前置:audio-core-sdk.lock.json 里要有 androidAssets(资产发布后再写进去):
#
#   "androidAssets": {
#     "asset": "OrzAudioCore-1.2.4-android-full.tar.gz",
#     "sha256": "…",
#     "abis": ["arm64-v8a", "armeabi-v7a"]
#   }

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCK="$ROOT/audio-core-sdk.lock.json"
DEST="${ORZ_AUDIO_CORE_ANDROID_DIR:-$ROOT/clients/android/audio-kit/native/.sdk}"

read_json_string() {
  sed -n "s/.*\"$2\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" "$1" | head -n 1
}
read_lock() { read_json_string "$LOCK" "$1"; }

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

# --local <tarball>:装本地已经构建好的资产(上游还没发 tag,或本地验证时用)
LOCAL_ARCHIVE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --local) LOCAL_ARCHIVE="${2:?--local 需要一个 tar.gz 路径}"; shift 2 ;;
    -h|--help) sed -n '3,26p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数:$1" >&2; exit 2 ;;
  esac
done

repo="$(read_lock repository)"
tag="$(read_lock tag)"
version="$(read_lock version)"
asset="$(read_lock androidAssets)"
expected="$(read_lock androidAssetsSha256)"

if [[ -z "$LOCAL_ARCHIVE" && ( -z "$asset" || -z "$expected" ) ]]; then
  cat >&2 <<EOF
!! audio-core-sdk.lock.json 还没声明 Android 资产。

OrzAudioCore 发布带 android job 的 tag 后,在 lock 里补:

  "androidAssets": "$(read_lock webAsset | sed 's/orzmusic-audio-core/web-core/;s/\.tgz//')-android-full.tar.gz",
  "androidAssetsSha256": "<release 里的 .sha256>",

然后重跑本脚本。当前 lock:
  version = $version
  tag     = $tag
EOF
  exit 1
fi

work="$(mktemp -d "${TMPDIR:-/tmp}/orz-audio-core-android.XXXXXX")"
trap 'rm -rf "$work"' EXIT
archive="$work/${asset:-android-full.tar.gz}"

if [[ -n "$LOCAL_ARCHIVE" ]]; then
  [[ -f "$LOCAL_ARCHIVE" ]] || { echo "本地资产不存在:$LOCAL_ARCHIVE" >&2; exit 1; }
  cp "$LOCAL_ARCHIVE" "$archive"
  actual="$(sha256_of "$archive")"
  echo "使用本地资产:$LOCAL_ARCHIVE"
  echo "  sha256 $actual"
  if [[ -n "$expected" && "$actual" != "$expected" ]]; then
    echo "警告:本地资产的 sha256 与 lock 里的 androidAssetsSha256 不一致" >&2
  fi
else
  echo "下载 $repo/releases/download/$tag/$asset"
  curl -fsSL --retry 4 --retry-all-errors "$repo/releases/download/$tag/$asset" -o "$archive"
  actual="$(sha256_of "$archive")"
  if [[ "$actual" != "$expected" ]]; then
    echo "OrzAudioCore Android 资产校验失败:期望 $expected,实际 $actual" >&2
    exit 1
  fi
fi

mkdir -p "$work/unpacked"
tar -xzf "$archive" -C "$work/unpacked"
stage="$(find "$work/unpacked" -maxdepth 1 -mindepth 1 -type d | head -n 1)"
[[ -n "$stage" ]] || { echo "资产里没有目录" >&2; exit 1; }

manifest="$stage/metadata/decoder-manifest.json"
test -s "$manifest" || { echo "资产缺少 metadata/decoder-manifest.json" >&2; exit 1; }
manifest_version="$(read_json_string "$manifest" sdkVersion)"
if [[ "$manifest_version" != "$version" ]]; then
  echo "资产版本($manifest_version)与 lock($version)不一致" >&2
  exit 1
fi

abis=()
for dir in "$stage"/android/*/; do
  [[ -d "$dir" ]] || continue
  abi="$(basename "$dir")"
  test -s "$dir/libOrzAudioCore.a" || { echo "$abi 缺少 libOrzAudioCore.a" >&2; exit 1; }
  abis+=("$abi")
done
(( ${#abis[@]} > 0 )) || { echo "资产里没有任何 ABI 产物" >&2; exit 1; }

rm -rf "$DEST.new"
mkdir -p "$(dirname "$DEST")"
mv "$stage" "$DEST.new"
rm -rf "$DEST"
mv "$DEST.new" "$DEST"

printf '已安装 OrzAudioCore %s Android SDK(%s,sha256 %s…)\n' \
  "$version" "${abis[*]}" "${actual:0:12}"
echo
echo "下一步:"
echo "  bash clients/android/audio-kit/scripts/build-android.sh     # 只编 JNI 胶水,链接上面的 .a"
echo "  cd clients/android && ./gradlew assembleDebug"
