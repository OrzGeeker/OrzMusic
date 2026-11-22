#!/usr/bin/env bash
#
# Android 客户端发版 —— 版本号从提交信息推导,CHANGELOG 自动生成,打 android-v* tag
#
# 为什么单独一条 tag 线
#   根目录的 vX.Y.Z 是**服务端**版本(由 VERSION 文件 + release.yml 校验),
#   客户端独立推进。两者混用一个 tag 会互相卡住。
#
# main 是受保护的(必须走 PR),所以发版分两步:
#   1) 准备:写 version.properties + CHANGELOG,提交到 release/android-x.y.z 分支并推送
#      → 开 PR,等门禁绿了合并(rebase merge,保留提交信息)
#   2) 打 tag:合并后回到 main 拉一次,再执行 --tag
#      → 推 android-vx.y.z,CI 构建签名 APK/AAB 并建 Release
#
# 段位怎么推(只看 clients/android/ 下的提交)
#   feat: …                    → MINOR
#   fix: … / perf: …           → PATCH
#   feat!: … / BREAKING CHANGE → 0.x 阶段按 MINOR
#   docs/chore/refactor/test/ci/build → 不发版
#
# 用法
#   bash scripts/android-release.sh                  # 准备发版分支(推导段位)
#   bash scripts/android-release.sh --version 0.7.0  # 指定版本
#   bash scripts/android-release.sh --as minor       # 强制段位
#   bash scripts/android-release.sh --dry-run        # 只显示会做什么
#   bash scripts/android-release.sh --check          # 只校验
#   bash scripts/android-release.sh --tag            # 合并后在 main 上打 tag 并发 Release
#   bash scripts/android-release.sh --tag --version 0.7.0   # 顺带校验版本一致
#   bash scripts/android-release.sh --yes            # 不交互确认
#
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLIENT="$(cd "$HERE/.." && pwd)"
REPO="$(cd "$CLIENT/../.." && pwd)"
VF="$CLIENT/version.properties"
CLE="$CLIENT/CHANGELOG.md"
SLUG="OrzGeeker/OrzMusic"

DRY=0; CHECK=0; YES=0; AS=""; WANT=""; DO_TAG=0
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY=1 ;;
    --check)   CHECK=1 ;;
    --yes|-y)  YES=1 ;;
    --tag)     DO_TAG=1 ;;
    --as)      shift; AS="${1:-}" ;;
    --version) shift; WANT="${1:-}" ;;
    -h|--help) sed -n '3,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
  shift
done

die(){ echo "!! $*" >&2; exit 1; }
ok(){  printf '  ✅ %s\n' "$1"; }
bad(){ printf '  ❌ %s\n' "$1"; }
is_semver(){ printf '%s' "$1" | grep -qE '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'; }
read_version(){ sed -n 's/^version[[:space:]]*=[[:space:]]*//p' "$VF" 2>/dev/null | tr -d '\r' | head -1; }

# 通用前置:工作区干净 / main / 与远端同步
preflight(){
  echo "════════ 校验 ════════"
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
      bad "有未推送的提交(先 git pull —— tag 必须打在已推送的提交上)"
      [ "$DRY" = 1 ] || exit 1
    else
      ok "已与 origin/main 同步"
    fi
  fi
}

# ── 模式二:合并之后打 tag ────────────────────────────────────────────────
if [ "$DO_TAG" = 1 ]; then
  preflight
  VER="$(read_version)"
  is_semver "$VER" || die "version.properties 里的 version='$VER' 不是严格 SemVer"
  ok "main 上的客户端版本 = $VER"
  [ -z "$WANT" ] || [ "$WANT" = "$VER" ] || die "--version $WANT 与 version.properties($VER)不一致"
  TAG="android-v$VER"
  git -C "$REPO" rev-parse -q --verify "refs/tags/$TAG" >/dev/null && die "tag $TAG 已存在"
  echo
  echo "  将要打 tag $TAG → $(git -C "$REPO" rev-parse --short HEAD)"
  echo
  if [ "$CHECK" = 1 ]; then echo "(--check:校验通过,没有打 tag)"; exit 0; fi
  if [ "$DRY" = 1 ]; then echo "(--dry-run:没有打 tag)"; exit 0; fi
  if [ "$YES" != 1 ]; then
    printf '  确认打 tag %s? [y/N] ' "$TAG"
    read -r ans </dev/tty 2>/dev/null || ans=""
    case "$ans" in [yY]*) ;; *) echo "  已取消。"; exit 0 ;; esac
  fi
  git -C "$REPO" tag -a "$TAG" -m "$VER

OrzMusic Android 客户端。
版本号来源: clients/android/version.properties" || die "打 tag 失败"
  git -C "$REPO" push origin "refs/tags/$TAG" || die "推送 tag 失败"
  ok "已推送 $TAG"
  echo
  echo "CI 会构建签名 APK/AAB 并建 Release: https://github.com/$SLUG/actions"
  exit 0
fi

# ── 模式一:准备发版提交 ────────────────────────────────────────────────
preflight
CUR="$(read_version)"
is_semver "$CUR" || die "version.properties 里的 version='$CUR' 不是严格 SemVer"
ok "当前版本 $CUR"

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
    *!:*)                                   has_break=1 ;;
    feat|feat\(*|feat:*)                    has_feat=1 ;;
    fix|fix\(*|fix:*|perf|perf\(*|perf:*)   has_fix=1 ;;
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
BRANCH="release/android-$NEW"

echo
echo "  段位      $BUMP"
echo "  新版本    $CUR → $NEW"
echo "  分支      $BRANCH"
echo "  变更      ${#LOG[@]} 条(仅 clients/android/ 下的提交)"
echo
if [ "$CHECK" = 1 ]; then echo "(--check:校验通过,没有改任何东西)"; exit 0; fi
if [ "$DRY" = 1 ]; then echo "(--dry-run:上面就是会做的事,没有改任何东西)"; exit 0; fi
if [ "$YES" != 1 ]; then
  printf '  确认准备 %s 的发版提交? [y/N] ' "$NEW"
  read -r ans </dev/tty 2>/dev/null || ans=""
  case "$ans" in [yY]*) ;; *) echo "  已取消,什么都没改。"; exit 0 ;; esac
fi

git -C "$REPO" checkout -q -b "$BRANCH" || die "建分支 $BRANCH 失败"

TODAY="$(date +%F)"
# version.properties:插一行历史(放在 version= 上方的分隔注释之前)
python3 - "$VF" "$NEW" "$TODAY" <<'PY' || die "改 version.properties 失败"
import re, sys
vf, ver, today = sys.argv[1:4]
src = open(vf, encoding='utf-8').read().split('\n')
vi = next((i for i, l in enumerate(src) if re.match(r'^version\s*=', l)), None)
if vi is None:
    sys.exit('version.properties 里没有 version= 行')
sep = max((i for i in range(vi) if re.match(r'^# ─+$', src[i])), default=vi)
src.insert(sep, '#   %s  %s  %s' % (ver, today, '见 CHANGELOG.md'))
src[vi + 1] = 'version=' + ver
open(vf, 'w', encoding='utf-8').write('\n'.join(src))
PY
grep -q "^version=$NEW\$" "$VF" || die "内部错误:version.properties 没写对"
ok "version.properties → $NEW"

# CHANGELOG:按 Conventional Commits 分组插到最上面
#
# ⚠️ 提交列表要**写进文件**再传给 python,不能 `printf | python3 - <<'PY'`:
#    用 `python3 -` 时程序本身来自 stdin,管道会被 heredoc 覆盖 ——
#    结果就是条目只有标题没有内容(实测 0.7.0 第一次生成时踩到)。
CMFILE="$(mktemp)"
printf '%s\n' "${LOG[@]}" > "$CMFILE"
python3 - "$CMFILE" "$CLE" "$NEW" "$TODAY" "${LAST:-}" "$TAG" "$SLUG" <<'PY' || die "写 CHANGELOG 失败"
import sys
cmfile, cle, ver, today, last, tag, slug = sys.argv[1:8]
commit_lines = [l.strip() for l in open(cmfile, encoding='utf-8') if l.strip()]

GROUPS = [('breaking', '不兼容变更'), ('feat', '新增'), ('fix', '修复'), ('other', '其他')]
def kind(s):
    if '!:' in s:
        return 'breaking'
    head = s.split(':', 1)[0]
    if head.startswith('feat'):
        return 'feat'
    if head.startswith(('fix', 'perf')):
        return 'fix'
    return 'other'

bucket = {k: [] for k, _ in GROUPS}
for line in commit_lines:
    sha, _, subj = line.partition(' ')
    if subj.startswith(('Merge ', 'Revert ', 'release:')):
        continue
    bucket[kind(subj)].append('- %s ([%s](https://github.com/%s/commit/%s))' % (subj, sha, slug, sha))

# 首次发版没有上一个 tag,不拼 compare 链接(否则指向不存在的 android-v0.0.0)
lines = ['## [%s](https://github.com/%s/compare/%s...%s) - %s' % (ver, slug, last, tag, today)
         if last else '## [%s] - %s' % (ver, today), '']
for key, label in GROUPS:
    if bucket[key]:
        lines += ['### %s' % label, ''] + bucket[key] + ['']
# 条目末尾必须留在两个换行:插入点是 old 里 '## [' 前那个 '\n',
# 只有一个换行时新条目的最后一行会和上一个版本标题贴在一起(0.8.13 踩过)。
entry = '\n'.join(lines) + '\n'

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
rm -f "$CMFILE"
ok "CHANGELOG.md 已插入 $NEW"

git -C "$REPO" add "$VF" "$CLE" || die "git add 失败"
git -C "$REPO" commit -q -m "release: prepare $NEW" || die "git commit 失败"

if ! push_out="$(git -C "$REPO" push -u origin "$BRANCH" 2>&1)"; then
  printf '%s\n' "$push_out" | tail -5 | sed 's/^/     /'
  die "推送分支失败(本次改动已提交在本地 $BRANCH)"
fi
ok "已推送分支 $BRANCH"

echo
echo "════════ 下一步 ════════"
echo "  1) 开 PR 并合并(rebase merge,保留提交信息):"
echo "       gh pr create --repo $SLUG --base main --head $BRANCH \\"
echo "          --title 'release: prepare $NEW' --body 'Android 客户端 $NEW'"
echo "  2) 合并后回到 main 拉一次,再打 tag:"
echo "       git checkout main && git pull"
echo "       bash clients/android/scripts/android-release.sh --tag"
