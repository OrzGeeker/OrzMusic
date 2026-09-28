# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## 解码能力

OrzAudioCore v1.2.4 作为外置 SDK 消费。解码逻辑封装在 `OrzAudioCoreSDK`（C system library），
通过稳定 ABI v1 暴露。同一份 SDK 编译为 WASM（浏览器端）和原生库（服务端），零 brew/apt 依赖。

## Build & Test

```bash
# Build (debug)
swift build

# Build (release)
swift build -c release

# Run tests
swift test

# Run a single test
swift test --filter testPlayStrategy

# Audit production audio fingerprint generation policy
make audit-fingerprints
make audit-fingerprints ALL=1

# Run server (requires PostgreSQL)
swift run OrzMusicService
# Or via Docker:
docker compose up --build -d
```

### 跑测试前的前置条件

- `Tests/Browser/*.test.mjs` 里的 WASM 解码用例依赖 `keygenmusic/` 子模块中的 fixture。
  该目录是 git submodule（见 `.gitmodules`），克隆后未初始化时这几个用例会以 ENOENT 失败，
  看起来像"既有失败"，实际是环境缺失：先跑 `git submodule update --init --recursive`。
  初始化后 `make browser-test` 应为 111 例全过。
- `swift test` 需要原生 SDK 产物，先跑 `./script/update-audio-core-server.sh`
  （不是 `update-audio-core-web.sh`，那个只装浏览器端 WASM）。
- 若 `swift test` 报全部 `_orz_*` 符号未定义，先检查 `Package.swift` 里有没有与 SDK 的
  `libOrzAudioCore.dylib` 同名的 library product：SwiftPM 会把它编译成 `libOrzAudioCore.a`，
  而它在 `-L` 搜索路径中排在 `.audio-core-sdk/server/native/lib` 之前，会遮蔽真正的 C 库。
  默认的 swiftbuild 引擎（Swift 6.4 / Xcode 27）把产物放在 `.build/out/Products/Debug/`，
  旧的 native 引擎布局不同，所以不会暴露这个问题。

## SDK 更新

```bash
# 安装/更新服务端 SDK（从 Release 下载，含校验）
./script/update-audio-core-server.sh

# 安装/更新 Web WASM SDK
./script/update-audio-core-web.sh
```

## Agent Collaboration

`AGENTS.md` 是本仓库跨智能体共享的项目知识源。Claude Code 通过 `CLAUDE.md` 的 `@AGENTS.md` 导入复用这些内容；不要在多个 agent 配置文件里复制同一段架构、命令或策略说明。

### Shared configuration

- `AGENTS.md`：共享项目知识、架构约束、构建/测试命令、扫描/指纹策略。
- `CLAUDE.md`：Claude Code 入口，只保留 Claude 专属路由说明，并导入 `AGENTS.md`。
- `.claude/settings.json`：可提交的 Claude Code 项目级权限/安全规则。
- `.claude/settings.local.json`：个人本机权限，已 gitignore，不要提交。
- `.claude/agents/`：Claude Code 项目级 subagents，用于架构审查、解码审计、前端审查和验证执行。

### Claude Code subagents

- `orz-architect`：架构评审、OrzAudioCore SDK 边界、跨平台复用和迁移顺序。
- `orz-decoder-auditor`：解码、扫描、CAS、音频指纹、格式统计和播放策略审计。
- `orz-frontend-reviewer`：播放器 UI、快捷键、队列、进度条、音量和可访问性审查。
- `orz-verifier`：运行聚焦测试/构建/审计命令并汇总结果。

Subagents 上下文隔离；委派任务时要明确目标、相关文件和期望验证命令。默认让 subagents 做只读审查或验证，主 agent 负责最终代码修改，除非用户明确要求并行实现。

## Architecture

### Target Dependency Chain

```
App (Vapor) → OrzAudioKit (Swift) → OrzAudioCoreSDK (C system library)
```

- **OrzAudioCoreSDK** — 已发布的 OrzAudioCore v1.2.4 system library target。包含所有 C 解码器（openmpt、gme、sidplayfp、adplug、ym6、midi、sc68、asap、ahx2play、v2m、bp）。
  通过 `audio-core-sdk.lock.json` 锁定版本和校验和，由 CI 自动下载。
- **OrzAudioKit** —纯 Swift target。封装 `OrzAudioCoreSDK` 的稳定 ABI，对不支持格式 fallback 到 ffmpeg CLI。
- **App** — Vapor web 服务器。包含路由、模型、迁移、CAS 存储、扫描器。

### 三种播放策略

- **`directFile`** — 浏览器 `<audio>` 原生播放（mp3, ogg, flac, m4a, aac）
- **`wasmDecode`** — Worker/WASM 解码 → SharedArrayBuffer → AudioWorklet（xm, mod, it, sid, mid, ym, bp, ...）
- **`serverDecode`** — 服务器端原生解码 / ffmpeg 回退 → WAV 缓存（sc68, wav with ADPCM/GSM）

### Content-Addressed Storage (CAS)

文件按 SHA-256 哈希存储：`{CAS_ROOT}/{sha256[:2]}/{sha256}.{ext}`（默认 `./data/music/`）。

数据库只存 `sha256` + `fileFormat`（不存路径）。`CasStorageService.swift` 负责 store/resolve/delete。

### Audio Fingerprints

- 音频指纹只在扫描/上传创建新 `Song` 时尝试生成；二次扫描遇到相同 SHA-256 会跳过，不会补生成。
- CAS SHA-256 是当前可靠去重主线；`audio_fingerprint` 是未来感知去重/相似匹配增强字段，不影响播放、格式分类或 CAS 存储。
- 生产策略只对容器音频生成指纹：`mp3`、`ogg`、`wav`、`flac`、`m4a`、`aac`。
- 模块/芯片/合成格式（如 `xm/mod/it/v2m/sc68/ym/sid/ahx/bp`）跳过指纹生成，避免 `fpcalc/ffmpeg` 对不支持格式长时间挂起。
- `make audit-fingerprints` 按生产策略抽样验证；`ALL=1` 只全量验证会生成指纹的容器音频；`FORCE_ALL_FORMATS=1` 才强制跑所有格式，仅用于诊断超时保护。
- 外部进程调用应保留超时保护。

### 关键文件

| File | Purpose |
|------|---------|
| `Package.swift` | SPM targets + OrzAudioCoreSDK system library linkage |
| `audio-core-sdk.lock.json` | OrzAudioCore 版本锁定 + 制品校验和 |
| `Sources/OrzAudioKit/AudioDecoder.swift` | 官方 Swift ABI 封装 |
| `Sources/OrzAudioKit/AudioEngine.swift` | 流策略解析 + 解码编排 |
| `Sources/OrzAudioKit/AudioFingerprinter.swift` | 容器音频指纹生成（fpcalc/ffmpeg/SHA-256 fallback） |
| `Sources/OrzFingerprintAudit/main.swift` | 指纹策略审计 CLI |
| `Sources/App/Services/CasStorageService.swift` | 内容寻址存储 |
| `Sources/App/Services/MusicScannerService.swift` | 扫描入库、CAS 去重、生产指纹策略 |
| `script/update-audio-core-server.sh` | 服务端 SDK 安装/更新 |
| `script/update-audio-core-web.sh` | Web WASM SDK 安装/更新 |
| `Resources/Public/audio/player.js` | 前端 WASM 解码 + 播放 |

## Frontend

- 单页应用 `Resources/Views/player.leaf`（Alpine.js + Leaf 模板）
- 品牌为 **OrzMusic**；统一图标是圆角近黑底、暖白 O 形圆环与薄荷绿五柱音频波形，主文件为 `Resources/Public/brand/orz-logo.svg`
- 更新品牌图标时同步 `favicon.ico`、32/192/512 PNG、根目录 favicon fallback 与透明单色空状态版本，避免多个入口显示不同设计
- 格式导航曲目数来自 `GET /api/songs/formats`；搜索支持可选 `format` 过滤
- 未保存队列在页面内存中；保存的播放列表持久化在服务端数据库，当前无用户隔离
- WASM bridge 在 `Resources/Public/audio/orz_audio_builtin.js`（由 OrzAudioCore SDK 提供）
- 播放加载 ABI-v1 WASM runtime 到 Worker；Worker 调用 `orz_decoder_*` 并写入 AudioWorklet ring buffer
