#!/usr/bin/env bash
#
# Android 客户端发版 —— 版本号从提交信息推导,CHANGELOG 自动生成,打 android-v* tag
#
# 为什么单独一条 tag 线
#   根目录的 vX.Y.Z 是**服务端**版本(由 VERSION 文件 + release.yml 校验),
#   客户端独立推进。两者混用一个 tag 会互相卡住。
#
# 段位怎么推(只看 clients/android/ 下的提交)
#   feat: …                    → MINOR
#   fix: … / perf: …           → PATCH
#   feat!: … / BREAKING CHANGE → 0.x 阶段按 MINOR
#   docs/chore/refactor/test/ci/build → 不发版
#
# 用法
#   bash scripts/android-release.sh                  # 推导 → 写文件 → 提交 → 打 tag → 推
#   bash scripts/android-release.sh --dry-run        # 只显示会做什么
#   bash scripts/android-release.sh --check          # 只校验干净/同步/tag 未占用
#   bash scripts/android-release.sh --version 0.7.0  # 指定版本
#   bash scripts/android-release.sh --as minor       # 强制段位
#   bash scripts/android-release.sh --yes            # 不交互确认
#
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLIENT="$(cd "$HERE/.." && pwd)"
REPO="$(cd "$CLIENT/../.." && pwd)"
VF="$CLIENT/version.properties"
CLE="$CLIENT/CHANGELOG.md"
SLUG="OrzGeeker/OrzMusic"

DRY=0; CHECK=0; YES=0; AS=""; WANT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY=1 ;;
    --check)   CHECK=1 ;;
    --yes|-y)  YES=1 ;;
    --as)      shift; AS="${1:-}" ;;
    --version) shift; WANT="${1:-}" ;;
    -h|--help) sed -n '3,24p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
  shift
done

die(){ echo "!! $*" >&2; exit 1; }
ok(){  printf '  ✅ %s\n' "$1"; }
bad(){ printf '  ❌ %s\n' "$1"; }
is_semver(){ printf '%s' "$1" | grep -qE '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'; }
read_version(){ sed -n 's/^version[[:space:]]*=[[:space:]]*//p' "$VF" 2>/dev/null | tr -d '\r' | head -1; }

CUR="$(read_version)"
is_semver "$CUR" || die "version.properties 里的 version='$CUR' 不是严格 SemVer"

echo "════════ 校验 ════════"
ok "当前版本 $CUR"
BR="$(git -C "$REPO" rev-parse --abbrev-ref HEAD)"
[ "$BR" = "main" ] && ok "在 main 上" || bad "当前分支是 $BR(发版应该在 main 上)"
if [ -n "$(git -C "$REPO" status --porcelain)" ]; then
  bad "工作区不干净"
  git -C "$REPO" status --short | sed 's/^/       /'
  [ "$DRY" = 1 ] || exit 1
else
  ok "工作区干净"
fi
if git -C "$REPO" rev-parse -q --verify '@{u}' >/dev/null 2>&1; then
  if [ "$(git -C "$REPO" rev-list --count '@{u}..HEAD')" != 0 ]; then
    bad "有未推送的提交(先 push —— tag 必须打在已推送的提交上)"
    [ "$DRY" = 1 ] || exit 1
  else
    ok "已与 origin/$BR 同步"
  fi
fi

LAST="$(git -C "$REPO" describe --tags --match 'android-v*' --abbrev=0 2>/dev/null || true)"
RANGE="${LAST:+$LAST..}HEAD"
[ -n "$LAST" ] && ok "上一个客户端 tag:$LAST" || echo "     (还没有 android-v* tag,这是第一次)"

# 只看客户端目录下的提交 —— 服务端 / Web 的改动不该影响客户端版本
mapfile -t LOG < <(git -C "$REPO" log --no-merges --format='%h %s' "$RANGE" -- clients/android 2>/dev/null)
if [ "${#LOG[@]}" -eq 0 ]; then
  echo "  ⏭️  clients/android 自 ${LAST:-仓库起点} 以来没有提交 —— 不发版。"
  exit 0
fi

has_feat=0; has_fix=0; has_break=0
for l in "${LOG[@]}"; do
  s="${l#* }"
  case "$s" in "Merge "*|"Revert "*|release:*) continue ;; esac
  case "$s" in
    *!:*)                          has_break=1 ;;
    feat|feat\(*|feat:*)           has_feat=1 ;;
    fix|fix\(*|fix:*|perf|perf\(*|perf:*) has_fix=1 ;;
  esac
done

if [ -n "$WANT" ]; then
  NEW="$WANT"; BUMP="手动指定"
elif [ -n "$AS" ]; then
  BUMP="$AS"
elif [ "$has_break" = 1 ] || [ "$has_feat" = 1 ]; then
  BUMP="minor"
elif [ "$has_fix" = 1 ]; then
  BUMP="patch"
else
  echo "  ⏭️  没有 feat / fix / 不兼容变更 —— 不发版(只有文档/重构/测试时不涨版本号)。"
  echo "     确实要发: bash scripts/android-release.sh --as patch"
  exit 0
fi

if [ -z "${NEW:-}" ]; then
  IFS=. read -r MA MI PA <<< "$CUR"
  case "$BUMP" in
    major) NEW="$((MA+1)).0.0" ;;
    minor) NEW="$MA.$((MI+1)).0" ;;
    patch) NEW="$MA.$MI.$((PA+1))" ;;
    *) die "--as 只接受 major | minor | patch" ;;
  esac
fi
is_semver "$NEW" || die "新版本 '$NEW' 不是严格 SemVer"
[ "$NEW" != "$CUR" ] || die "新版本和当前版本相同($CUR)"
TAG="android-v$NEW"
git -C "$REPO" rev-parse -q --verify "refs/tags/$TAG" >/dev/null && die "tag $TAG 已存在"

echo
echo "  段位      $BUMP"
echo "  新版本    $CUR → $NEW"
echo "  客户端 tag $TAG"
echo "  变更      ${#LOG[@]} 条(仅 clients/android/ 下的提交)"
echo

if [ "$CHECK" = 1 ]; then echo "(--check:校验通过,没有改任何东西)"; exit 0; fi
if [ "$DRY" = 1 ]; then echo "(--dry-run:上面就是会做的事,没有改任何东西)"; exit 0; fi
if [ "$YES" != 1 ]; then
  printf '  确认发版 %s? [y/N] ' "$NEW"
  read -r ans </dev/tty 2>/dev/null || ans=""
  case "$ans" in [yY]*) ;; *) echo "  已取消,什么都没改。"; exit 0 ;; esac
fi

TODAY="$(date +%F)"

# ── version.properties:插一行历史(放在 version= 上方的分隔注释之前)────────────
python3 - "$VF" "$NEW" "$TODAY" <<'PY' || die "改 version.properties 失败"
import re, sys
vf, ver, today = sys.argv[1:4]
src = open(vf, encoding='utf-8').read().split('\n')
vi = next((i for i, l in enumerate(src) if re.match(r'^version\s*=', l)), None)
if vi is None:
    sys.exit('version.properties 里没有 version= 行')
sep = max((i for i in range(vi) if re.match(r'^# ─+$', src[i])), default=vi)
title = ''
for l in open(vf, encoding='utf-8'):
    pass
src.insert(sep, '#   %s  %s  %s' % (ver, today, '见 CHANGELOG.md'))
src[vi + 1] = 'version=' + ver
open(vf, 'w', encoding='utf-8').write('\n'.join(src))
PY
grep -q "^version=$NEW\$" "$VF" || die "内部错误:version.properties 没写对"
ok "version.properties → $NEW"

# ── CHANGELOG:按 Conventional Commits 分组插到最上面 ────────────────────────
printf '%s\n' "${LOG[@]}" | python3 - "$CLE" "$NEW" "$TODAY" "${LAST:-}" "$TAG" "$SLUG" <<'PY' || die "写 CHANGELOG 失败"
import sys
cle, ver, today, last, tag, slug = sys.argv[1:7]
commit_lines = [l.strip() for l in sys.stdin if l.strip()]

GROUPS = [('breaking', '不兼容变更'), ('feat', '新增'), ('fix', '修复'), ('other', '其他')]
def kind(subj):
    s = subj.split(' ', 1)[1] if ' ' in subj else ''
    if '!:' in s:
        return 'breaking'
    head = s.split(':', 1)[0]
    if head.startswith('feat'):
        return 'feat'
    if head.startswith(('fix', 'perf')):
        return 'fix'
    return 'other'

import re
bucket = {k: [] for k, _ in GROUPS}
for line in commit_lines:
    sha, _, subj = line.partition(' ')
    if subj.startswith(('Merge ', 'Revert ', 'release:')):
        continue
    bucket[kind(subj)].append('- %s ([%s](https://github.com/%s/commit/%s))' % (subj, sha, slug, sha))

base = last or 'android-v0.0.0'
lines = ['## [%s](https://github.com/%s/compare/%s...%s) - %s' % (ver, slug, base, tag, today), '']
for key, label in GROUPS:
    if bucket[key]:
        lines += ['### %s' % label, ''] + bucket[key] + ['']
entry = '\n'.join(lines)

try:
    old = open(cle, encoding='utf-8').read()
except FileNotFoundError:
    old = ''

if not old:
    head = ('# 更新日志 · OrzMusic Android 客户端\n\n'
            '客户端版本独立于服务端(根目录 `VERSION`)。发版入口:\n'
            '`bash clients/android/scripts/android-release.sh`。\n\n'
            '格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。\n\n')
    out = head + entry
else:
    i = old.find('\n## [')
    out = (old.rstrip() + '\n\n' + entry) if i < 0 else (old[:i + 1] + entry + old[i + 1:])
open(cle, 'w', encoding='utf-8').write(out)
PY
ok "CHANGELOG.md 已插入 $NEW"

# ── 提交 + tag + 推 ──────────────────────────────────────────────────────
git -C "$REPO" add "$VF" "$CLE" || die "git add 失败"
git -C "$REPO" commit -q -m "release: prepare $NEW" || die "git commit 失败"
ok "已提交 release: prepare $NEW"
git -C "$REPO" push 2>&1 | tail -2 | sed 's/^/     /'

git -C "$REPO" tag -a "$TAG" -m "$NEW

OrzMusic Android 客户端。
版本号来源: clients/android/version.properties" || die "打 tag 失败"
git -C "$REPO" push origin "refs/tags/$TAG" || die "推送 tag 失败"
ok "已推送 $TAG"

echo
echo "════════ 完成 ════════"
echo "  $CUR → $NEW"
echo "  CI 会构建签名 APK/AAB 并建 Release: https://github.com/$SLUG/actions"
