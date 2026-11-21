#!/usr/bin/env bash
#
# 用 NDK 交叉编译 OrzAudioCore + libopenmpt 的 Android 解码库
#
# 产物:
#   clients/android/audio-kit/src/main/jniLibs/arm64-v8a/liborzaudio.so
#   clients/android/audio-kit/src/main/jniLibs/armeabi-v7a/liborzaudio.so
#
# 这两个 .so 已经在仓库里;普通 `./gradlew assembleDebug` **不需要** NDK。
# 只有改了 audio-kit/native/cpp/ 或升级 libopenmpt / OrzAudioCore 时才需要重跑本脚本。
#
# 依赖:
#   · Android NDK 27.0.12077973
#   · SDK cmake 3.22.1(或系统 cmake)+ ninja
#   安装:
#     sdkmanager "ndk;27.0.12077973" "cmake;3.22.1"
#
# 用法:
#   bash clients/android/audio-kit/scripts/build-android.sh            # 两个 ABI 都编
#   bash clients/android/audio-kit/scripts/build-android.sh --abi arm64-v8a
#   bash clients/android/audio-kit/scripts/build-android.sh --clean    # 删除 audio-kit/native/.build
#
# ⚠️ 请在 WSL2 / Linux / macOS 上跑。Windows 原生 Git Bash 下 NDK 的
#    autotools + CMake 路径转换很容易出问题;Windows 直接用仓库里提交好的 .so。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 本脚本**不依赖任何外部仓库的公共库** —— 客户端单独 clone 下来就要能编 native。
# 只内联需要的那几行(SDK 探测 + 版本比较)。
ANDROID_SDK_DIR="${ANDROID_SDK_DIR:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}}"
if [ -z "$ANDROID_SDK_DIR" ] || [ ! -d "$ANDROID_SDK_DIR" ]; then
  # 常见位置兜底,省掉「明明装了却报找不到」的一轮排查
  for _c in /opt/android-sdk "$HOME/Android/Sdk" "${LOCALAPPDATA:-}/Android/Sdk"; do
    [ -d "$_c" ] && { ANDROID_SDK_DIR="$_c"; break; }
  done
fi
[ -d "${ANDROID_SDK_DIR:-/nonexistent}" ] || {
  echo "!! 找不到 Android SDK:设置 ANDROID_SDK_DIR 或 ANDROID_HOME" >&2; exit 1; }
IS_WINDOWS=0
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) IS_WINDOWS=1 ;; esac

# 版本比较(纯 bash):Windows 自带的 sort 不认 -V,不能拿它挑 NDK。
_ver_ge(){
  local i x y
  local -a a b
  IFS=. read -r -a a <<< "$1"
  IFS=. read -r -a b <<< "$2"
  for i in 0 1 2 3; do
    x="${a[i]:-0}"; y="${b[i]:-0}"
    [ "$x" -gt "$y" ] && return 0
    [ "$x" -lt "$y" ] && return 1
  done
  return 0
}

APP="$(cd "$HERE/../.." && pwd)"
NATIVE="$APP/audio-kit/native"
JNILIBS="$APP/audio-kit/src/main/jniLibs"
BUILD="$NATIVE/.build"

LIBOPENMPT_VERSION="0.8.0"
LIBOPENMPT_URL="https://lib.openmpt.org/files/libopenmpt/src/libopenmpt-${LIBOPENMPT_VERSION}+release.autotools.tar.gz"
NDK_PIN="27.0.12077973"
API=29

die(){ echo "!! $*" >&2; exit 1; }
step(){ printf '\n════════ %s ════════\n' "$1"; }

ONLY_ABI=""
CLEAN=0
while [ $# -gt 0 ]; do
  case "$1" in
    --abi)   shift; ONLY_ABI="${1:-}" ;;
    --clean) CLEAN=1 ;;
    -h|--help) sed -n '3,26p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "未知参数: $1" ;;
  esac
  shift
done

if [ "${IS_WINDOWS:-0}" = 1 ]; then
  die "请在 WSL2 / Linux / macOS 上运行。Windows 原生构建路径转换不可靠;
   仓库里已有编译好的 .so,普通构建不需要跑这个脚本。"
fi

if [ "$CLEAN" = 1 ]; then
  rm -rf "$BUILD"
  echo "已删除 $BUILD"
  exit 0
fi

# ── 工具链 ──────────────────────────────────────────────────────
if [ -n "${ANDROID_NDK_HOME:-}" ]; then
  NDK="$ANDROID_NDK_HOME"
elif [ -n "${ANDROID_NDK:-}" ]; then
  NDK="$ANDROID_NDK"
else
  NDK=""
  [ -d "$ANDROID_SDK_DIR/ndk" ] || die "找不到 NDK。先装:
     sdkmanager \"ndk;$NDK_PIN\" \"cmake;3.22.1\""
  for d in "$ANDROID_SDK_DIR"/ndk/*/; do
    [ -d "$d" ] || continue
    d="${d%/}"
    if [ -z "$NDK" ] || _ver_ge "$(basename "$d")" "$(basename "$NDK")"; then
      NDK="$d"
    fi
  done
fi
[ -d "$NDK" ] || die "NDK 路径无效: $NDK"

CM="$ANDROID_SDK_DIR/cmake/3.22.1/bin/cmake"
[ -x "$CM" ] || CM="$(command -v cmake || true)"
[ -n "$CM" ] || die "找不到 cmake。装: sdkmanager \"cmake;3.22.1\""

NINJA="$ANDROID_SDK_DIR/cmake/3.22.1/bin/ninja"
[ -x "$NINJA" ] || NINJA="$(command -v ninja || true)"
[ -n "$NINJA" ] || die "找不到 ninja。装: sdkmanager \"cmake;3.22.1\" 或 apt install ninja-build"
export PATH="$(dirname "$NINJA"):$PATH"

TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
[ -d "$TC" ] || TC="$NDK/toolchains/llvm/prebuilt/darwin-x86_64"
[ -d "$TC" ] || die "找不到 NDK 工具链: $NDK/toolchains/llvm/prebuilt/*"

JOBS="${JOBS:-$(nproc 2>/dev/null || sysctl -n hw.logicalcpu 2>/dev/null || echo 4)}"
HOST_BUILD="$(uname -m)-$( [ "$(uname -s)" = Darwin ] && echo apple-darwin || echo pc-linux-gnu )"
echo "NDK   : $NDK"
echo "CMake : $CM"
echo "Ninja : $NINJA"
echo "Jobs  : $JOBS"

mkdir -p "$BUILD" "$JNILIBS"

# ── libopenmpt 源码 ─────────────────────────────────────────────
step "准备 libopenmpt $LIBOPENMPT_VERSION 源码"
OMPT_ROOT="$BUILD/libopenmpt-$LIBOPENMPT_VERSION"
if [ ! -d "$OMPT_ROOT" ]; then
  mkdir -p "$BUILD/download"
  TAR="$BUILD/download/libopenmpt.tar.gz"
  [ -f "$TAR" ] || curl -L --retry 3 --max-time 300 -o "$TAR" "$LIBOPENMPT_URL"
  tar xzf "$TAR" -C "$BUILD"
  # 解压目录带 +release.autotools 后缀
  for d in "$BUILD"/libopenmpt-"$LIBOPENMPT_VERSION"*; do
    [ -d "$d" ] || continue
    [ "$d" = "$OMPT_ROOT" ] && continue
    rm -rf "$OMPT_ROOT"
    mv "$d" "$OMPT_ROOT"
    break
  done
fi
[ -x "$OMPT_ROOT/configure" ] || die "libopenmpt 源码解压失败: $OMPT_ROOT"

# ── 编译 libopenmpt ────────────────────────────────────────────
build_openmpt() {
  local abi="$1" host="$2" cc="$3" cxx="$4"
  local bdir="$BUILD/ompt-$abi"
  step "编译 libopenmpt ($abi)"
  rm -rf "$bdir"
  mkdir -p "$bdir"

  (
    cd "$bdir"
    export CC="$TC/bin/$cc"
    export CXX="$TC/bin/$cxx"
    export AR="$TC/bin/llvm-ar"
    export RANLIB="$TC/bin/llvm-ranlib"
    export STRIP="$TC/bin/llvm-strip"
    local march=""
    [ "$abi" = "armeabi-v7a" ] && march="-march=armv7-a"
    export CFLAGS="-O2 -fPIC $march"
    export CXXFLAGS="-O2 -fPIC $march"
    "$OMPT_ROOT/configure" \
      --build="$HOST_BUILD" --host="$host" \
      --disable-shared --enable-static \
      --disable-examples --disable-tests --disable-openmpt123 \
      --without-mpg123 --without-ogg --without-vorbis --without-vorbisfile \
      --without-portaudio --without-sdl2 --without-flac --without-zlib \
      > configure.log 2>&1
    make -j"$JOBS" > build.log 2>&1
  ) || { tail -40 "$bdir/configure.log" "$bdir/build.log" 2>/dev/null; die "$abi libopenmpt 构建失败"; }

  [ -f "$bdir/.libs/libopenmpt.a" ] || die "$abi 没产出 libopenmpt.a"
  echo "  $(du -h "$bdir/.libs/libopenmpt.a" | cut -f1)  $bdir/.libs/libopenmpt.a"
}

# ── 编译 liborzaudio.so ────────────────────────────────────────
build_so() {
  local abi="$1"
  local cdir="$BUILD/cmake-$abi"
  step "编译 liborzaudio.so ($abi)"
  rm -rf "$cdir"
  # 优先用上游发布的 libOrzAudioCore.a(23 个格式),只编 JNI 胶水;
  # 没有资产时退回 vendored 源码 + 本地编的 libopenmpt(只有 13 个格式)。
  local core_args=()
  local prebuilt="$NATIVE/.sdk/android/$abi/libOrzAudioCore.a"
  if [ -f "$prebuilt" ]; then
    echo "  核心: 上游预编译 $prebuilt"
    core_args+=("-DORZ_PREBUILT_CORE=$prebuilt")
    [ -f "$NATIVE/.sdk/native/include/orz_audio_core.h" ] && \
      core_args+=("-DORZ_PREBUILT_INCLUDE=$NATIVE/.sdk/native/include")
  else
    echo "  核心: vendored 源码(先跑 script/update-audio-core-android.sh 可切到上游资产)"
    core_args+=("-DOPENMPT_LIB=$BUILD/ompt-$abi/.libs/libopenmpt.a")
  fi

  "$CM" -S "$NATIVE/cpp" -B "$cdir" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DCMAKE_BUILD_TYPE=Release \
    -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM="android-$API" \
    -DANDROID_STL=c++_static \
    "${core_args[@]}" \
    > "$cdir.configure.log" 2>&1 \
    || { tail -30 "$cdir.configure.log"; die "$abi CMake 配置失败"; }

  "$CM" --build "$cdir" -j"$JOBS" > "$cdir.build.log" 2>&1 \
    || { tail -40 "$cdir.build.log"; die "$abi 编译失败"; }

  mkdir -p "$JNILIBS/$abi"
  cp "$cdir/liborzaudio.so" "$JNILIBS/$abi/liborzaudio.so"
  "$TC/bin/llvm-strip" --strip-unneeded "$JNILIBS/$abi/liborzaudio.so"
  echo "  $(du -h "$JNILIBS/$abi/liborzaudio.so" | cut -f1)  $JNILIBS/$abi/liborzaudio.so"
}

run_abi() {
  local abi="$1"
  local prebuilt="$NATIVE/.sdk/android/$abi/libOrzAudioCore.a"
  case "$abi" in
    arm64-v8a)
      [ -f "$prebuilt" ] || build_openmpt "arm64-v8a" "aarch64-linux-android" \
        "aarch64-linux-android$API-clang" "aarch64-linux-android$API-clang++"
      build_so "arm64-v8a"
      ;;
    armeabi-v7a)
      [ -f "$prebuilt" ] || build_openmpt "armeabi-v7a" "armv7a-linux-androideabi" \
        "armv7a-linux-androideabi$API-clang" "armv7a-linux-androideabi$API-clang++"
      build_so "armeabi-v7a"
      ;;
    *) die "不支持的 ABI: $abi(可用: arm64-v8a / armeabi-v7a)" ;;
  esac
}

if [ -n "$ONLY_ABI" ]; then
  run_abi "$ONLY_ABI"
else
  run_abi "arm64-v8a"
  run_abi "armeabi-v7a"
fi

step "完成"
ls -lh "$JNILIBS"/*/liborzaudio.so
echo
echo "现在可以正常构建 APK: cd clients/android && ./gradlew assembleDebug"
