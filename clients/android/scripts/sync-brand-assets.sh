#!/usr/bin/env bash
#
# 把服务端仓库的品牌制品同步到客户端 —— 品牌只有一个来源
#
# 单一来源:
#   Resources/Public/brand/orz-logo.svg      可编辑母版(设计变更改这里)
#   Resources/Public/brand/orz-logo-512.png  商店 512 图标
#
# 本脚本做的事:
#   1. 拷贝 orz-logo-512.png → clients/android/store/assets/icon-512.png(校验尺寸)
#   2. 用 store/assets/gen-store-assets.java 重出带文字的两张图:
#        · store/assets/feature-graphic-1024x500.png
#        · store/assets/banner-320x180.png
#        · app/src/main/res/drawable/banner.png(TV 启动器用的同一张)
#   3. 提醒同步自适应图标(app/src/main/res/drawable/ic_launcher_foreground.xml)
#      —— 它是矢量的,改品牌时要按 orz-logo.svg 的几何手改,脚本不自动转换。
#
# 用法(仓库根):
#   bash clients/android/scripts/sync-brand-assets.sh
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLIENT="$(cd "$HERE/.." && pwd)"
REPO="$(cd "$CLIENT/../.." && pwd)"
BRAND="$REPO/Resources/Public/brand"
ASSETS="$CLIENT/store/assets"

die(){ echo "!! $*" >&2; exit 1; }

[ -f "$BRAND/orz-logo-512.png" ] || die "找不到 $BRAND/orz-logo-512.png(品牌唯一来源在服务端 Resources/Public/brand/)"
[ -f "$ASSETS/gen-store-assets.java" ] || die "找不到 $ASSETS/gen-store-assets.java"

echo "==> 同步 512 图标(来自 Resources/Public/brand/orz-logo-512.png)"
python3 - "$BRAND/orz-logo-512.png" "$ASSETS/icon-512.png" <<'PY'
import shutil, struct, sys
src, dst = sys.argv[1:3]
d = open(src, 'rb').read()
if d[:8] != b'\x89PNG\r\n\x1a\n':
    sys.exit('源文件不是 PNG: ' + src)
w, h = struct.unpack('>II', d[16:24])
if (w, h) != (512, 512):
    sys.exit('品牌 512 图尺寸不对: %dx%d' % (w, h))
shutil.copyfile(src, dst)
print('   %s (%dx%d, %d bytes)' % (dst, w, h, len(d)))
PY

echo "==> 重出特色图片与 TV banner(JDK 单文件,不依赖 ImageMagick)"
java -Djava.awt.headless=true "$ASSETS/gen-store-assets.java" \
     "$ASSETS" \
     "$CLIENT/app/src/main/res/drawable/banner.png"

echo
echo "完成。改过品牌设计时还要手改这两处(矢量,脚本不自动转):"
echo "  · app/src/main/res/drawable/ic_launcher_foreground.xml  (圆环 + 五柱)"
echo "  · app/src/main/res/values/colors.xml                     (ic_launcher_background)"
