# Android 界面与 Web 播放器对齐

参考实现是 `OrzGeeker/OrzMusic` 仓库里的 Web 播放器：

| Web | 作用 |
|---|---|
| `Resources/Views/player.leaf` | 页面结构：侧栏 / 顶栏 / 歌曲表 / 播放坞 / 队列抽屉 |
| `Resources/Public/audio/app.css` | 深色配色、间距、响应式断点 |
| `Resources/Public/audio/app.js` | 队列、搜索、歌单、定位、快捷键、格式分组 |
| `Resources/Public/audio/visualizer.js` | 声场可视化 |

Android 端不是逐像素照搬，而是把同一套信息架构翻译成 Compose，
并补上 TV D-pad / Pico 2D 面板需要的焦点与返回键行为。

## 组件映射

| Web | Android | 说明 |
|---|---|---|
| `.sidebar` + `.format-group` | `AppRoot.Sidebar` + `FormatCatalog` | 按模块/复古/合成/常规/其他分组，显示数量 |
| `.topbar` 搜索 | `TopBar` + `SearchField` | 全局搜索，防抖 300ms；`/` 聚焦 |
| `.queue-trigger` | 顶栏「队列 N」 | 打开右侧队列抽屉 |
| `.song-table` | `LibraryContent` + `SongRow` | 宽屏显示专辑/大小，窄屏自动减列 |
| `.song-row .row-play` | `SongRow` 左侧播放态 | 当前曲目显示薄荷色波形柱 |
| `.player-dock` | `PlayerDock` | 顶部进度 + 当前曲目 + 圆形播放控制 + 队列/音量 |
| `.seek-wrap` | `SeekBar` | 点击/拖动；获得焦点后 D-pad 左右 ±5s |
| `.queue-panel` | `QueueDrawer` | 队列、移除、保存当前队列、加载/删除歌单 |
| `player.js` 的队列 | `PlaybackQueue`（core） | UI 不拥有队列语义 |
| Media Session | `PlaybackService`（MediaSessionService） | 后台播放 / 通知栏 / 锁屏 / 耳机控制 |
| `<audio>` 输出 | ExoPlayer（service 内） | Activity 只通过 `MediaController` 控制 |
| `visualizer.js` | `VisualizerPanel` + `VisualizerBars` + `VisualizerAudioProcessor` | 与 Web `.viz-panel` 一样固定在播放坞上方；PCM 做 1024 点 FFT，48 根对数频段柱 |
| `L` 定位 | 「正在播放」页「定位」/ `L` | 调 `/api/songs/{id}/location` 后滚动到该行 |
| `? / H` | 顶栏「?」 | 键盘 / 遥控快捷键说明 |
| 手柄/指针 | `MainActivity` 输入映射 + `tapGesture` | 触摸/扳机=落点 hover + `onClick`、B=返回、摇杆=焦点；沿用 Pico 原生光标 |

## 视觉系统

- **背景**：`BgTop → Bg → BgBottom` 的纵向渐变，叠加 TV 端薄荷焦点色，避免纯黑平板感。
- **图标**：`ui/OrzIcons.kt` 自绘播放 / 暂停 / 上一首 / 下一首 / 搜索 / 队列 / 音量 / 返回；
  不再依赖 `|◀`、`II`、`▶|`、`🔊` 这类几何字符或 emoji。
- **焦点**：列表 Card 的焦点环统一为 1.5dp 薄荷描边（`orzCardBorder`），
  `CardDefaults.scale` 固定为 1（不再放大），焦点也不额外铺背景；Button 仍是
  薄荷填充 + 深色文字。
- **字号**：顶栏标题 24sp、页内标题 22-24sp、歌曲标题 14sp、表格头 9.5sp、
  播放坞标题 13sp；优先保证 Pico 2D 面板 / TV 三米外可读。
- **正在播放**：宽屏左右两栏（大封面 + 右侧信息/控制），窄屏回到单栏。
- **声场**：从播放坞里的小条移到独立面板，位置对齐 Web `.viz-panel`；播放坞的进度条
  改为单个 Canvas 绘制（轨道 / 填充 / 圆形拇指），避免拇指和填充条各自布局导致错位。
- **播放坞**：宽屏三等分（左曲目 / 中播放控制 / 右队列+音量），播放控制与时长作为
  一组居中；音量百分比、时间文本固定宽度，避免拖动音量时布局抖动。

## 自适应规则

- `< 560dp`：手机单栏列表，隐藏专辑/大小列。
- `>= 560dp`：显示歌曲表头和艺术家列（手机横屏、平板竖屏也适用）。
- `< 840dp`：顶部品牌 + 搜索 + 标签行，无侧栏；平板竖屏使用这一档。
- `>= 840dp`：236dp 左侧栏 + 主内容 + 底部播放坞；平板横屏/TV/Pico 使用这一档。
- `>= 800dp`：歌曲表额外显示专辑、大小列。
- 当前曲目存在时，播放坞上方显示声场面板：宽屏 132dp / 48 根柱，窄屏 96dp / 32 根柱；
  面板可收起为右下角「▸ 声场」按钮，再点击恢复。
- 队列抽屉在窄屏接近全宽；在宽屏是 410dp 右侧浮层。
- 播放坞在 `< 760dp` 时收起时间/音量，只保留当前曲目 + 三个播放键。
- 正在播放页：`< 900dp` 单栏（平板竖屏封面 180dp），`>= 900dp` 左右双栏。

## TV 遥控器

- **方向键**：只在可聚焦节点间移动；列表焦点不缩放，只显示薄荷描边。
- **搜索框**：浏览态是可聚焦的展示卡，方向键经过时不会弹 IME；按 **OK** 才进入
  编辑态并拉起系统 TV 输入法，**BACK** 先关输入法，再按 BACK 退出。
- **BACK**：TV 交给系统 / `OnBackPressedDispatcher`；只有 Pico 在
  `MainActivity.dispatchKeyEvent` 里提前消费，避免 VRSHELL 抢键。
- **播放控制**：进度条/音量条聚焦后左右键 seek / 调音量；OK 激活当前焦点控件。

## 平台差异 / 有意没搬的部分

| Web 功能 | Android 处理 | 原因 |
|---|---|---|
| 管理员目录导入 | 未移植 | 浏览器 `webkitdirectory` + 管理令牌是 Web 管理端能力，不是播放器必需功能 |
| 无限滚动 | 「加载更多」按钮 | TV 上比滚动触发更可控，也避免 D-pad 滚到底时误触发 |
| 键盘方向键调节音量 | 音量条获得焦点后 D-pad 上下调；`M` 静音 | 避免全局拦截方向键破坏 TV 焦点导航 |
| 系统 IME（Pico） | 直接使用系统输入法 | 不再自建应用内键盘；系统 IME 的焦点回收由 `WindowFocusEvents` 兜底，见 [playback.md](playback.md) |

## 验收

```bash
cd clients/android
./gradlew :core:test
./gradlew :app:testDebugUnitTest
# → app/build/outputs/roborazzi/orzmusic-{tv,phone,tablet,tablet-landscape,queue,
#    nowplaying,nowplaying-phone,nowplaying-tablet}.png

# Pico（自截图）—— 设备脚本在 EasyAndroid 仓库,不在本仓库
EASYANDROID=/path/to/EasyAndroid
bash "$EASYANDROID/tools/pico-panel.sh" com.jokerhub.orzmusic.android awake
bash "$EASYANDROID/tools/ui-dump.sh" com.jokerhub.orzmusic.android "$PICO_ADDR" --launch
```

TV 用 D-pad 验收：进入曲库 → 上下走格式/歌曲 → OK 播放 → 顶栏「正在播放」→
进度条获得焦点后左右 seek；BACK 关闭覆盖层。
