#!/usr/bin/env bash
#
# 上架前静态自检 —— 不联网、不上传,只回答「产物和资料够不够交上去」
#
# 查的都是**机器能提前查出来**的拒收理由:targetSdk 太低、包名用了 com.example、
# AAB 里没有 64 位、APK 还是 debuggable、签名和 signing-manifest 对不上、
# versionName/versionCode 和 version.properties 不一致、store/*.md 里还留着待填项。
#
# 用法
#   bash scripts/store-check.sh                  # 默认看 Google Play
#   bash scripts/store-check.sh --target play
#   bash scripts/store-check.sh --target cn      # 国内商店
#   bash scripts/store-check.sh --version 0.7.0  # 覆盖版本号(默认读 version.properties)
#   bash scripts/store-check.sh --apk x.apk --aab y.aab
#                                                # 检查**已发布的产物**(而不是本地
#                                                # app/build/outputs/ 下刚构建的那份)
#
# 依赖:Android SDK build-tools(aapt2 / apksigner)、jarsigner、unzip。
# 平台:Linux / macOS / WSL2。Windows 原生 Git Bash 下 apksigner 是 .bat,
#       还需要把 JAVA_HOME 换成 Windows 形式 —— 直接用 WSL 跑。
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLIENT="$(cd "$HERE/.." && pwd)"

die(){ echo "!! $*" >&2; exit 1; }
PASS=0; FAIL=0; WARN=0
ok(){   printf '  ✅ %s\n' "$1"; PASS=$((PASS+1)); }
bad(){  printf '  ❌ %s\n' "$1"; FAIL=$((FAIL+1)); }
warn(){ printf '  ⚠️  %s\n' "$1"; WARN=$((WARN+1)); }

TARGET="play"; VER=""; APK_OVERRIDE=""; AAB_OVERRIDE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --target) shift; TARGET="${1:-}";;
    --version) shift; VER="${1:-}";;
    --apk) shift; APK_OVERRIDE="${1:-}";;
    --aab) shift; AAB_OVERRIDE="${1:-}";;
    -h|--help) sed -n '3,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0;;
    *) die "未知参数: $1";
  esac
  shift
done
case "$TARGET" in play|cn) ;; *) die "--target 只接受 play | cn";; esac

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) die "Windows 原生 Git Bash 下 apksigner 是 .bat,请在 WSL2 里跑本脚本";;
esac

# ── 版本 ────────────────────────────────────────────────────────
[ -n "$VER" ] || VER="$(sed -n 's/^version[[:space:]]*=[[:space:]]*//p' "$CLIENT/version.properties" | tr -d '\r' | head -1)"
printf '%s' "$VER" | grep -qE '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$' \
  || die "版本号 '$VER' 不是严格 SemVer(见 version.properties)"
IFS=. read -r MA MI PA <<< "$VER"
WANT_CODE=$(( MA * 10000 + MI * 100 + PA ))

# ── SDK / 工具链 ────────────────────────────────────────────────
ANDROID_SDK_DIR="${ANDROID_SDK_DIR:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}}"
if [ -z "$ANDROID_SDK_DIR" ] || [ ! -d "$ANDROID_SDK_DIR" ]; then
  for _c in /opt/android-sdk "$HOME/Android/Sdk" "${LOCALAPPDATA:-}/Android/Sdk"; do
    [ -d "$_c" ] && { ANDROID_SDK_DIR="$_c"; break; }
  done
fi
[ -d "${ANDROID_SDK_DIR:-/nonexistent}/build-tools" ] || die "找不到 Android build-tools:设置 ANDROID_SDK_DIR"

_ver_ge(){  # $1 >= $2 ?(纯 bash,不依赖 sort -V)
  local i x y; local -a a b
  IFS=. read -r -a a <<< "$1"; IFS=. read -r -a b <<< "$2"
  for i in 0 1 2 3; do
    x="${a[i]:-0}"; y="${b[i]:-0}"
    [ "$x" -gt "$y" ] && return 0
    [ "$x" -lt "$y" ] && return 1
  done
  return 0
}
BT=""
for d in "$ANDROID_SDK_DIR"/build-tools/*/; do
  [ -d "$d" ] || continue
  d="${d%/}"
  if [ -z "$BT" ] || _ver_ge "$(basename "$d")" "$(basename "$BT")"; then BT="$d"; fi
done
AAPT2="$BT/aapt2"; APKSIGNER="$BT/apksigner"
[ -f "$AAPT2" ] || die "找不到 aapt2($BT)"
[ -f "$APKSIGNER" ] || die "找不到 apksigner($BT)"
command -v unzip >/dev/null 2>&1 || die "找不到 unzip(读 APK/AAB 内容需要它)"
JARSIGNER="$(command -v jarsigner || true)"

APK="${APK_OVERRIDE:-$CLIENT/app/build/outputs/apk/release/app-release.apk}"
[ -n "$APK_OVERRIDE" ] || { [ -f "$APK" ] || APK="$CLIENT/app/build/outputs/apk/release/app-release-signed.apk"; }
AAB="${AAB_OVERRIDE:-$CLIENT/app/build/outputs/bundle/release/app-release.aab}"

echo "════════ OrzMusic Android · $VER (versionCode $WANT_CODE)· target=$TARGET ════════"

if [ ! -f "$APK" ]; then
  bad "缺少 $APK —— 先跑: ./gradlew :app:assembleRelease"
else
  badging="$("$AAPT2" dump badging "$APK" 2>/dev/null)"
  pkg_line="$(printf '%s\n' "$badging" | grep -m1 '^package:')"
  pkg="$(printf '%s' "$pkg_line" | tr ' ' '\n' | awk -F"'" '/^name=/{print $2; exit}')"
  got_ver="$(printf '%s' "$pkg_line" | tr ' ' '\n' | awk -F"'" '/^versionName=/{print $2; exit}')"
  got_code="$(printf '%s' "$pkg_line" | tr ' ' '\n' | awk -F"'" '/^versionCode=/{print $2; exit}')"
  target_sdk="$(printf '%s\n' "$badging" | awk -F"'" '/^targetSdkVersion:/{print $2; exit}')"

  case "$pkg" in
    com.example.*) bad "包名是 $pkg —— 商店一律拒收 com.example.*";;
    '')            bad "取不到包名(aapt2 dump badging 输出异常)";;
    *)             ok "包名 $pkg";;
  esac
  if [ "$got_ver" = "$VER" ] && [ "$got_code" = "$WANT_CODE" ]; then
    ok "versionName=$got_ver versionCode=$got_code"
  else
    bad "版本不符: versionName=$got_ver versionCode=$got_code(期望 $VER / $WANT_CODE)"
  fi

  if printf '%s' "$badging" | grep -q 'application-debuggable'; then
    bad "APK 是 debuggable 的 —— 这是 debug 包"
  else
    ok "非 debuggable"
  fi
  if "$AAPT2" dump xmltree --file AndroidManifest.xml "$APK" 2>/dev/null | grep -q 'UiDumpReceiver'; then
    bad "APK 里混进了 debug 自截图钩子(UiDumpReceiver)"
  else
    ok "无 debug 钩子"
  fi

  fp="$("$APKSIGNER" verify --print-certs "$APK" 2>/dev/null | awk -F': *' '/SHA-256 digest/{print $NF; exit}')"
  if [ -n "$fp" ]; then
    ok "已签名(${fp:0:16}…)"
    if grep -qF "$fp" "$CLIENT/signing-manifest.txt" 2>/dev/null; then
      ok "指纹与 signing-manifest.txt 一致"
    else
      bad "指纹不在 signing-manifest.txt 里 —— 这不是发布用的那把密钥"
    fi
  else
    bad "apksigner 取不到证书指纹(没签名?)"
  fi

  abis="$(unzip -l "$APK" 2>/dev/null | awk -F'lib/' '/lib\//{split($2,a,"/"); print a[1]}' | sort -u | tr '\n' ' ')"
  echo "     ABI: ${abis:-<none>}"
  case "$TARGET" in
    play)
      case " $abis " in *" arm64-v8a "*) ok "含 64 位(arm64-v8a)";; *) bad "缺 arm64-v8a —— Play 64 位要求";; esac
      if [ -n "$target_sdk" ] && [ "$target_sdk" -ge 36 ] 2>/dev/null; then
        ok "targetSdk=$target_sdk ≥ 36"
      else
        bad "targetSdk=${target_sdk:-?} < 36 —— Play 新应用要求"
      fi ;;
    cn)
      case " $abis " in *" armeabi-v7a "*) ok "含 armeabi-v7a(TV 可装)";; *) bad "缺 armeabi-v7a —— TCL 等 32 位电视装不上";; esac
      if [ -n "$target_sdk" ] && [ "$target_sdk" -ge 30 ] 2>/dev/null; then
        ok "targetSdk=$target_sdk ≥ 30"
      else
        bad "targetSdk=${target_sdk:-?} < 30 —— 国内商店常见门槛"
      fi
      sz="$(wc -c < "$APK" | tr -d ' ')"
      if [ "$sz" -le $((100*1024*1024)) ]; then ok "APK $((sz/1024/1024)) MB ≤ 100 MB"; else bad "APK $((sz/1024/1024)) MB > 100 MB"; fi ;;
  esac
fi

# ── AAB(Google Play)─────────────────────────────────────────────
if [ "$TARGET" = play ]; then
  if [ ! -f "$AAB" ]; then
    bad "缺少 $AAB —— 先跑: ./gradlew :app:bundleRelease"
  else
    entries="$(unzip -l "$AAB" 2>/dev/null)"
    miss=""
    for e in BundleConfig.pb base/manifest/AndroidManifest.xml base/dex/classes.dex; do
      case "$entries" in *"$e"*) ;; *) miss="$miss $e";; esac
    done
    # ⚠️ 用 case 而不是 `printf | grep -q`:脚本开了 pipefail,grep -q 提前退出会让
    #    写端吃 EPIPE,整条 pipeline 返非 0 —— 随机把「有」判成「没有」。
    if [ -z "$miss" ]; then ok "AAB 结构完整(BundleConfig / manifest / dex)"; else bad "AAB 缺少:$miss"; fi
    case "$entries" in *base/lib/arm64-v8a/*) ok "AAB 含 arm64-v8a";; *) bad "AAB 缺 base/lib/arm64-v8a";; esac
    if [ -n "$JARSIGNER" ] && "$JARSIGNER" -verify "$AAB" >/dev/null 2>&1; then
      ok "AAB 已签名(jarsigner -verify)"
    else
      warn "AAB 未通过 jarsigner 校验(没有 jarsigner 就跳过)"
    fi
  fi
fi

# ── GPLv3 合规(全 target 共用)────────────────────────────────
# 为什么在这里查:`clients/android/` 是 GPL-3.0-or-later(见 clients/android/LICENSE),
# 而 "GPL 作品里塞防篡改"/"漏声明" 都是机器能提前发现的拒收/违规理由。
LIC_FILE="$CLIENT/LICENSE"
NOTICES="$CLIENT/THIRD_PARTY_NOTICES.md"

if [ -f "$LIC_FILE" ] && grep -q 'GNU GENERAL PUBLIC LICENSE' "$LIC_FILE" && grep -q 'Version 3' "$LIC_FILE"; then
  ok "LICENSE 是 GPLv3 全文"
else
  bad "缺少 GPLv3 全文:$LIC_FILE"
fi

if [ -f "$NOTICES" ]; then
  ok "THIRD_PARTY_NOTICES.md 存在"
else
  bad "缺少 $NOTICES(GPLv3 §6 要求随二进制提供声明)"
fi

# profile 里每个 decoder 都必须在声明里出现,否则就是"未声明的第三方组件"。
if command -v python3 >/dev/null 2>&1; then
  missing="$(python3 - "$CLIENT" <<'PY' 2>/dev/null
import json, sys, pathlib
client = pathlib.Path(sys.argv[1])
profile = json.loads((client / "audio-kit/native/decoder-profile.json").read_text(encoding="utf-8"))
manifest = json.loads((client / "audio-kit/native/decoder-manifest.json").read_text(encoding="utf-8"))
notices = (client / "THIRD_PARTY_NOTICES.md").read_text(encoding="utf-8").lower()
by_id = {f["id"]: f for f in manifest.get("formats", [])}
decoders = []
for fmt in profile.get("formats", []):
    d = by_id.get(fmt["id"], {}).get("decoder")
    if d and d not in decoders:
        decoders.append(d)
print(" ".join(d for d in decoders if d.lower() not in notices))
PY
)"
  if [ -z "$(printf '%s' "$missing" | tr -d '[:space:]')" ]; then
    ok "profile 里所有 decoder 都在声明中列出"
  else
    bad "声明遗漏的 decoder:$missing"
  fi
else
  warn "没有 python3,跳过『profile ↔ 声明』一致性检查"
fi

# GPLv3 §6 反锁定:不能用技术手段阻止安装修改版。
hits="$(grep -rEl 'PlayIntegrity|playIntegrity|integrityManager|GET_SIGNATURES|apkContentsSigners|signingInfo' \
  "$CLIENT/app/src" "$CLIENT/core/src" "$CLIENT/audio-kit/src" 2>/dev/null || true)"
if [ -z "$hits" ]; then
  ok "未发现签名校验 / Play Integrity(GPLv3 §6)"
else
  bad "发现防篡改代码,违反 GPLv3 §6:\n$hits"
fi

# 商店文案必须给出对应源码地址。
for f in "$CLIENT/store/play.md" "$CLIENT/store/cn.md"; do
  [ -f "$f" ] || continue
  if grep -q 'github.com/OrzGeeker/OrzMusic' "$f"; then
    ok "store/$(basename "$f") 含源码地址"
  else
    bad "store/$(basename "$f") 缺少对应源码地址(GPLv3 §6)"
  fi
done

# ── 人工清单是否还有待填项 ──────────────────────────────────────
for f in "$CLIENT/store/play.md" "$CLIENT/store/cn.md"; do
  [ -f "$f" ] || continue
  n="$(grep -c 'TODO' "$f" 2>/dev/null || true)"
  if [ "${n:-0}" -gt 0 ]; then
    warn "store/$(basename "$f") 还有 $n 处 TODO(隐私政策 URL / 软著 / 备案等人工项)"
  else
    ok "store/$(basename "$f") 没有 TODO"
  fi
done

echo
echo "════════════════ 汇总 ════════════════"
printf '  通过 %d  失败 %d  警告 %d\n' "$PASS" "$FAIL" "$WARN"
if [ "$FAIL" -gt 0 ]; then
  echo "有硬阻塞,别提交。"
  exit 1
fi
echo "技术项通过。人工项(隐私政策 / 软著 / 备案 / 截图)见 store/*.md。"
