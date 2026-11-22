# 更新日志 · OrzMusic Android 客户端

客户端版本独立于服务端(根目录 `VERSION`)。发版入口:
`bash clients/android/scripts/android-release.sh`。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [0.8.11](https://github.com/OrzGeeker/OrzMusic/compare/android-v0.8.10...android-v0.8.11) - 2026-11-22

### 修复

- fix(android): 升级 OrzAudioCore 1.3.1（adplug 可解）+ 切歌进度彻底归零 ([53cc258](https://github.com/OrzGeeker/OrzMusic/commit/53cc258))
- chore(deps): Swift 侧 DecoderManifest 同步到 1.3.1 ([9c33f69](https://github.com/OrzGeeker/OrzMusic/commit/9c33f69))

**解码核心升到 OrzAudioCore 1.3.1**：`amd` / `d00` / `hsc` / `rad` 四个 adplug
格式之前在 Android 上直接报「格式不支持或数据损坏」——adplug 包装依赖 `/tmp`
（Android 没有）。真机复查 22 个原生格式各一首真歌，现在**全部能播**。

**切歌不再带上一首进度**：进度条的拖动预览按歌曲 id 重置，位置轮询只认
「播放器当前条目 == 界面歌曲」，`MEDIA_NEXT` 实测 `6009ms → 1ms`。

## [0.8.10](https://github.com/OrzGeeker/OrzMusic/compare/android-v0.8.9...android-v0.8.10) - 2026-11-22

**左右键调进度整条下线。** 这条线试了三版（0.8.4 走 Compose 焦点、0.8.8 走
Activity 层命中判定、0.8.9 覆盖摇杆轴合成）都没能在真机上稳定生效，还把 0.8.6
已经验收的丝滑拖动带坏了。

进度条回到 0.8.6 的形态，只保留三种交互：**点击轨道**、**拖动（本地预览 +
松手 seek 一次）**、**射线悬停 + 扳机跳到落点**。要 ±5s/±15s 请用正在播放页的
四个按钮（它们走 `vm.seekBy`，一直可用）。

保留 0.8.9 里与左右键无关的修复：切歌会先停旧播放、进度立即归零，不再把上一首
的进度带到新歌。

### 移除

- revert(android): 下线左右键调进度，进度条回到拖动/点击/射线确认 ([a2cd3cb](https://github.com/OrzGeeker/OrzMusic/commit/a2cd3cb))

## [0.8.9](https://github.com/OrzGeeker/OrzMusic/compare/android-v0.8.8...android-v0.8.9) - 2026-11-22

两个真机问题，根因都很具体：

1. **左右键调进度仍不工作**：Pico 的十字键是以 `SOURCE_JOYSTICK` 轴值送进来的，
   `dispatchGenericMotionEvent` 合成 DPAD 键时用 `sendTapKey()` 直接
   `super.dispatchKeyEvent(...)`，绕过了 0.8.8 在 `dispatchKeyEvent` 里的判定。
   现在判定收口成 `SeekBarZones.seekByDirectionKey()`，两条路共用。
2. **切歌时进度条带着上一首的进度**：换歌要先下载/准备新文件，这期间旧播放还在跑，
   界面轮询读到的仍是旧位置。现在 `startSong()` 先 `playback.stop()`，并且
   `state.current?.id` 一变就把界面进度归零、时长先用服务端值顶上。

### 修复

- fix(android): 摇杆轴合成的左右键也能调进度；切歌时进度归零 ([07c28b2](https://github.com/OrzGeeker/OrzMusic/commit/07c28b2))

## [0.8.8](https://github.com/OrzGeeker/OrzMusic/compare/android-v0.8.7...android-v0.8.8) - 2026-11-22

拖动已经顺了。这轮修「左右键调进度」：真机实测按方向键时进度条**拿不到 Compose
焦点**（logcat 里一条 `focus@` 都没有），方向键全被「移动焦点」吃掉。

现在射线指着进度条时，`MainActivity.dispatchKeyEvent` 直接判定命中区并 ±5s
（`SeekBarZones` 用窗口坐标注册），不再依赖焦点；射线不在进度条上时按键原样
交回 Compose 做焦点移动。

### 修复

- fix(android): 射线指着进度条时左右键直接 ±5s ([254d718](https://github.com/OrzGeeker/OrzMusic/commit/254d718))

## [0.8.7](https://github.com/OrzGeeker/OrzMusic/compare/android-v0.8.6...android-v0.8.7) - 2026-11-22

拖动已经顺了（0.8.6）。本轮处理「左右键调整进度不工作」：左右键只在进度条
**有焦点**时生效（否则会把 Compose 的方向键导航抢掉），所以先把它变成可观测的
——`OrzSeek: focus@dock|nowplaying` / `key@… <key>` / `seekBy@… ±5000`，
并且进度条获得焦点时轨道 4→6dp、滑块 6→8dp，头显里一眼能看出来。

### 修复

- fix(android): 进度条左右键可诊断 + 焦点可见 ([f297911](https://github.com/OrzGeeker/OrzMusic/commit/f297911))

## [0.8.6](https://github.com/OrzGeeker/OrzMusic/compare/android-v0.8.5...android-v0.8.6) - 2026-11-22

0.8.5 之后拖动进度条能 seek 了，但**拖的时候一卡一卡**：每个 pointer-move 都
`onSeek` → 会话主线程 → pause/flush/解码器 seek，一次拖动几十次把主线程堵住。

现在拖动只在**松手时 seek 一次**，拖动过程只用本地预览跟手画；播放器侧改成
异步、只保留最新目标的 seek 队列。`OrzSeek` 日志也细分到 `pointer@`/`tap@`/
`dragStart@`/`dragEnd@`/`dock.onSeek`/`asyncSeek`，方便继续定位主页那条进度条。

### 修复

- fix(android): 拖动进度条只在松手时 seek 一次，拖动过程本地预览 ([aa5b425](https://github.com/OrzGeeker/OrzMusic/commit/aa5b425))

## [0.8.5](https://github.com/OrzGeeker/OrzMusic/compare/android-v0.8.4...android-v0.8.5) - 2026-11-22

0.8.4 之后仍有一个真问题：拖动进度条时音乐不跳、进度条立刻弹回原位。
用真歌定位到——`orz_decoder_seek` **只有 openmpt 系**实现（`mod/xm/it/s3m/mo3/mtm`），
`sc68 / sid / gme` 这些的 capabilities 里没有 `ORZ_CAP_SEEK`，调用直接返回不支持，
所以「有的歌能拖、有的拖了弹回去」。

现在不支持 seek 的格式改用**重解码快进**（重开解码器把 PCM 渲染丢弃到目标位置）：
实测 sc68 跳 90 秒只要 ~22ms（约 4000x 实时），用户视角没有区别。
真机验证 `mod` → `applied=true`、`sc68` → `applied=true skipRender=true now=90000`。

### 修复

- fix(android): 不支持 seek 的格式改用「重解码快进」，进度条不再弹回原位 ([7041300](https://github.com/OrzGeeker/OrzMusic/commit/7041300))

## [0.8.4](https://github.com/OrzGeeker/OrzMusic/compare/android-v0.8.3...android-v0.8.4) - 2026-11-22

0.8.3 让进度条「看起来能交互」了，但射线确认/拖动之后音频不跳位置、进度也不更新。
根因是 `SeekBar` 把 `onKeyEvent` 写在 `.focusable()` 之后 —— Compose 只把按键
冒泡给焦点节点的祖先 modifier，所以进度条一直收不到按键（原有的左右键 ±5s 也是死的）。

### 修复

- fix(android): 修好进度条的按键处理顺序，确认键按射线落点 seek ([916b68d](https://github.com/OrzGeeker/OrzMusic/commit/916b68d))

## [0.8.3](https://github.com/OrzGeeker/OrzMusic/compare/android-v0.8.2...android-v0.8.3) - 2026-11-22

真机上量了四项（PICO A8110）：seek `1530 → 30000 → 30786ms` 正常、声场峰值 `0.419`、
首播 `play→playing=140ms`、切歌后无 `orz-pcm*` / `orz-native*` 残留线程 —— 解码与播放
引擎都没问题。本次修的是交互与观感。

### 修复

- fix(android): 进度条支持射线落点确认，声场柱快起慢落并放大 ([667d1ae](https://github.com/OrzGeeker/OrzMusic/commit/667d1ae))

### 其他

- chore(android): 播放路由写进 logcat，并记录 MediaSession 丢 tag 的坑 ([c9677e8](https://github.com/OrzGeeker/OrzMusic/commit/c9677e8))
- ci(android): 预热并缓存 Robolectric 的 android-all，避免 MavenArtifactFetcher 抽风 ([42b61e0](https://github.com/OrzGeeker/OrzMusic/commit/42b61e0))

## [0.8.2](https://github.com/OrzGeeker/OrzMusic/compare/android-v0.8.1...android-v0.8.2) - 2026-11-21

### 修复

- fix(android): 格式 id 改走 MediaMetadata.extras,模块音乐不再被丢给 ExoPlayer ([3a5dff5](https://github.com/OrzGeeker/OrzMusic/commit/3a5dff5))

## [0.8.1](https://github.com/OrzGeeker/OrzMusic/compare/android-v0.8.0...android-v0.8.1) - 2026-11-21

本次只换解码核心版本：`liborzaudio.so` 由 OrzAudioCore **1.3.0** 资产重建
（23 个格式不变，`orz_build_info()` 变成 `orzcore-1.3.0-m1`，旧 WAV 缓存自动失效），
并补上设备端冒烟测试。

### 其他

- test(android): 设备端冒烟测试验证 23 格式与 JNI 解码链路 ([e8f6d5d](https://github.com/OrzGeeker/OrzMusic/commit/e8f6d5d))
- docs(android): 记录 OrzAudioCore 1.3.0 已发布的 Android 资产与升级流程 ([12d18be](https://github.com/OrzGeeker/OrzMusic/commit/12d18be))
- chore(deps): 升级 OrzAudioCore 到 1.3.0 并用上游资产重建 liborzaudio.so ([ee43d7f](https://github.com/OrzGeeker/OrzMusic/commit/ee43d7f))

## [0.8.0](https://github.com/OrzGeeker/OrzMusic/compare/android-v0.7.1...android-v0.8.0) - 2026-11-21

### 不兼容变更

- feat(android)!: 许可证改为 GPL-3.0-or-later 并补上架合规检查 ([620cc96](https://github.com/OrzGeeker/OrzMusic/commit/620cc96))

### 新增

- feat(android): 设置页增加开源许可与源码入口 ([2e6c5d2](https://github.com/OrzGeeker/OrzMusic/commit/2e6c5d2))
- feat(android): 用 PcmStream + AudioTrack 流式播放模块音乐 ([731f60e](https://github.com/OrzGeeker/OrzMusic/commit/731f60e))
- feat(android): 消费上游 Android SDK 资产，liborzaudio.so 覆盖 23 个格式 ([642ed8d](https://github.com/OrzGeeker/OrzMusic/commit/642ed8d))
- feat(android): 运行时探测解码能力并按 native-first 路由 ([7012603](https://github.com/OrzGeeker/OrzMusic/commit/7012603))

### 其他

- docs(android): 记录解码器打包决策与流式播放方案 ([de8eee4](https://github.com/OrzGeeker/OrzMusic/commit/de8eee4))
## [0.7.1](https://github.com/OrzGeeker/OrzMusic/compare/android-v0.7.0...android-v0.7.1) - 2026-11-20

### 新增

- feat(android): store-check 支持检查已发布产物;补 0.7.0 真机与上游阻塞记录 (#26) ([8b0539e](https://github.com/OrzGeeker/OrzMusic/commit/8b0539e))

### 其他

- ci(android): 发版改走 EasyRelease 的 android-v0.1.0 (#29) ([536769e](https://github.com/OrzGeeker/OrzMusic/commit/536769e))
- docs(android): 补 TCL 电视的 0.7.0 验收记录 (#28) ([9ea7514](https://github.com/OrzGeeker/OrzMusic/commit/9ea7514))
- docs(backlog): 评估 Web 与 Vapor 的拆分,结论是暂不做目录拆分 (#27) ([f154585](https://github.com/OrzGeeker/OrzMusic/commit/f154585))
## [0.7.0] - 2026-11-20

### 新增

- feat(android): 引入 Android 客户端(clients/android) (#21) ([917f3fd](https://github.com/OrzGeeker/OrzMusic/commit/917f3fd))

### 修复

- fix(android): 发版脚本生成的 CHANGELOG 只有标题没有条目 (#23) ([92a993d](https://github.com/OrzGeeker/OrzMusic/commit/92a993d))
- fix(android): 发版脚本适配受保护的 main —— 改成「准备分支 → PR → 打 tag」两步 (#22) ([9e3528e](https://github.com/OrzGeeker/OrzMusic/commit/9e3528e))
