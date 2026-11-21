# 更新日志 · OrzMusic Android 客户端

客户端版本独立于服务端(根目录 `VERSION`)。发版入口:
`bash clients/android/scripts/android-release.sh`。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

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
