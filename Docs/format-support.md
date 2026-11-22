# OrzMusic 格式支持状态

> 最后校验：2026-07-22
> 口径：以当前 OrzMusic 后端 `AudioFormat`、`AudioEngine`、前端 SDK manifest 和本地 `/api/songs/formats` 返回为准。

## 当前结论

- **OrzMusic 应用层支持格式：27 种**，由 `Sources/OrzAudioKit/AudioFormat.swift` 决定。
- **当前本地音乐库：5533 首**，来自 `/api/songs/formats` 的实时统计；这个数字随扫描源和数据库内容变化，不是固定能力上限。
- **当前本地库中有曲目的格式：25 种**；`flac`、`m4a`、`aac` 已支持但当前库内数量为 0。
- **OrzAudioCore SDK v1.3.1 是外置解码 SDK**，OrzMusic 不再直接编译内嵌 decoder 源码。
- **SDK/Web manifest 目前包含 `thx`**，但 OrzMusic 后端 `AudioFormat` 尚未声明 `thx`，因此当前应用层扫描、上传、API 分类和播放策略不把 `thx` 视为已支持格式。

## 当前库内格式数量

> 以下数量是当前开发库快照，用来校验页面分类与数据库是否一致；不是项目长期承诺值。

| 分类 | 格式 | 当前数量 | 默认播放策略 | 说明 |
|:-----|:-----|--------:|:-------------|:-----|
| 模块音乐 | `xm` | 3396 | `wasmDecode` | OrzAudioCore / libopenmpt 路径 |
| 模块音乐 | `mod` | 1093 | `wasmDecode` | OrzAudioCore / libopenmpt 路径 |
| 模块音乐 | `it` | 254 | `wasmDecode` | OrzAudioCore / libopenmpt 路径 |
| 模块音乐 | `s3m` | 94 | `wasmDecode` | OrzAudioCore / libopenmpt 路径 |
| 模块音乐 | `mo3` | 5 | `wasmDecode` | OrzAudioCore / libopenmpt 路径 |
| 模块音乐 | `mtm` | 1 | `wasmDecode` | OrzAudioCore / libopenmpt 路径 |
| 模块音乐 | `fc13` | 4 | `wasmDecode` | Future Composer 1.3 |
| 模块音乐 | `fc14` | 16 | `wasmDecode` | Future Composer 1.4 |
| 复古/芯片 | `nsf` | 5 | `wasmDecode` | Game Music Emu 路径 |
| 复古/芯片 | `spc` | 3 | `wasmDecode` | Game Music Emu 路径 |
| 复古/芯片 | `sid` | 48 | `wasmDecode` | libsidplayfp 路径 |
| 复古/芯片 | `sc68` | 120 | `serverDecode` | 服务端原生解码，避免浏览器端 68K 解释器性能问题 |
| 复古/芯片 | `ym` | 21 | `wasmDecode` | YM2149 路径 |
| 复古/芯片 | `sap` | 32 | `wasmDecode` | ASAP 路径 |
| 复古/芯片 | `ahx` | 63 | `wasmDecode` | ahx2play 路径 |
| 合成/FM | `mid` | 110 | `wasmDecode` | 浏览器端使用 WASM runtime；服务端缓存路径可走标准解码 fallback |
| 合成/FM | `v2m` | 130 | `wasmDecode` | v2m-player 路径 |
| 合成/FM | `bp` | 4 | `wasmDecode` | SoundMon BP 解码器 |
| 合成/FM | `rad` | 13 | `wasmDecode` | AdPlug 路径 |
| 合成/FM | `d00` | 10 | `wasmDecode` | AdPlug 路径 |
| 合成/FM | `hsc` | 18 | `wasmDecode` | AdPlug 路径 |
| 合成/FM | `amd` | 17 | `wasmDecode` | AdPlug 路径 |
| 常规音频 | `mp3` | 15 | `directFile` | 浏览器原生播放 |
| 常规音频 | `ogg` | 41 | `directFile` | 浏览器原生播放 |
| 常规音频 | `wav` | 20 | 自动判定 | PCM/IEEE float 直放；压缩 WAV 走服务端解码 |
| 常规音频 | `flac` | 0 | `directFile` | 已支持，当前库无样本 |
| 常规音频 | `m4a` | 0 | `directFile` | 已支持，当前库无样本 |
| 常规音频 | `aac` | 0 | `directFile` | 已支持，当前库无样本 |

## 播放策略口径

OrzMusic 目前有三条播放路径：

| 策略 | 适用格式 | 实际行为 |
|:-----|:---------|:---------|
| `directFile` | `mp3`、`ogg`、`flac`、`m4a`、`aac`、PCM/IEEE float `wav` | 服务端返回 CAS 原始文件，由浏览器 `<audio>` 原生播放。 |
| `wasmDecode` | `xm`、`mod`、`it`、`s3m`、`mo3`、`mtm`、`fc13`、`fc14`、`nsf`、`spc`、`sid`、`ym`、`sap`、`ahx`、`amd`、`rad`、`d00`、`hsc`、`v2m`、`bp`、`mid` | 服务端返回原始文件，浏览器 Worker 加载 OrzAudioCore WASM，通过 ABI v1 `orz_decoder_*` 接口解码并写入 AudioWorklet ring buffer。 |
| `serverDecode` | `sc68`、压缩编码 `wav` | 服务端使用 OrzAudioCore 原生库或 ffmpeg fallback 生成 PCM WAV 缓存，再交给浏览器播放。 |

> 从 Android 客户端起 `serverDecode` 是**兜底语义**而不是硬命令：客户端如果在本地
> 编进了对应 decoder，就下 `/raw` 自己解（native-first），不再请求服务端转 WAV。
> 契约与打包方式见 [`android-decoder-packaging.md`](android-decoder-packaging.md)。

> `AudioFormat.playStrategy` 中 `wav` 的默认值是 `serverDecode`，但 `AudioEngine.resolveStreamStrategy()` 会读取 WAV 头：PCM/IEEE float 会被优化为 `directFile`，ADPCM/GSM 等压缩 WAV 才走 `serverDecode`。

## SDK 与应用层边界

```
OrzAudioCore SDK v1.3.1（外置 SDK，校验锁定）
  ├── Native library（服务端）
  └── WASM bundle（浏览器）
              ↓
Sources/OrzAudioKit/（Swift ABI 封装）
              ↓
Sources/App/（Vapor API、CAS、扫描、缓存）
              ↓
Resources/Public/audio/（Worker、AudioWorklet、播放器 UI）
```

- OrzAudioCore 负责格式探测、元数据和 PCM 解码能力。
- OrzMusic 负责 CAS、数据库、扫描、搜索、播放策略、WAV 缓存、播放队列和 UI。
- `directFile` / `wasmDecode` / `serverDecode` 是 OrzMusic 产品策略，不是 OrzAudioCore 公共 ABI 的一部分。
- 前端格式展示由 SDK manifest 与前端常规音频配置组合而来；后端入库/播放能力仍以 `AudioFormat` 为准。

## 构建与 SDK 更新

```bash
# 查看项目常用命令
make help

# 安装或更新服务端 Native SDK
make sdk-server

# 安装或更新 Web WASM SDK
make sdk-web

# 构建服务端
swift build
```

OrzAudioCore SDK 版本和校验信息由 `audio-core-sdk.lock.json` 锁定。更新 SDK 后，应同时验证：

- `Resources/Public/audio/decoder-manifest.generated.js` 是否与 SDK release manifest 一致。
- `Sources/OrzAudioKit/AudioFormat.swift` 是否声明了应用层要支持的格式。
- 前端格式分类、后端 `/api/songs/formats`、搜索 `format` 参数是否保持同一格式 ID 口径。

## 播放流程

```
浏览器请求 /api/songs/:id/stream
  ↓
SongController.stream()
  ↓
AudioEngine.resolveStreamStrategy()
  ├─ directFile   → 返回 CAS 原始文件给 <audio>
  ├─ wasmDecode   → 返回 CAS 原始文件，Worker/WASM 实时解码
  └─ serverDecode → 服务端解码为 PCM WAV，命中或写入 WAV 缓存
```

浏览器端 WASM 解码流程：

```
fetch raw file
  ↓
ArrayBuffer
  ↓
Worker + OrzAudioCore WASM ABI v1
  ↓
SharedArrayBuffer ring
  ↓
AudioWorklet
  ↓
AudioContext 输出
```

## 存储、缓存与指纹

- CAS 保存扫描或上传得到的**原始文件**，文件名由内容 SHA-256 和扩展名组成。
- 数据库保存 SHA-256、格式、标题、艺术家、专辑、时长等元数据，不保存“预处理后替代文件”作为主音频。
- `serverDecode` 生成的 PCM WAV 位于独立缓存中；缓存失效与 SDK 版本、解码器能力、输出参数、subsong 等因素有关。
- 解码失败不会替换或删除 CAS 原始文件，也不会提交不完整 WAV 缓存。

音频指纹策略：

- 入库去重首先使用 CAS SHA-256。
- `audio_fingerprint` 是感知去重/相似匹配的增强字段，不参与播放路径、格式分类或 CAS 存储。
- 生产扫描只对常规容器音频生成指纹：`mp3`、`ogg`、`wav`、`flac`、`m4a`、`aac`。
- 模块、芯片和合成格式（例如 `xm/mod/it/s3m/v2m/sc68/ym/sid/ahx/bp`）跳过指纹生成，避免 `fpcalc/ffmpeg` 在非容器格式上长时间失败或卡住。
- `make audit-fingerprints` 验证生产指纹策略；`ALL=1` 全量验证会生成指纹的容器音频；`FORCE_ALL_FORMATS=1` 才强制跑所有格式的慢失败路径。

## 已知边界与后续关注

- `thx` 已出现在 Web/SDK manifest 中，但 OrzMusic 后端尚未纳入 `AudioFormat`；如果要正式支持，需要补后端 enum、扫描、API、前端分类和回归样本。
- `sc68` 当前默认服务端解码，这是产品性能策略；不表示 SDK/WASM 永久不能支持。
- `wav` 策略不是单纯按扩展名决定，而是按 WAV 编码类型动态决定。
- 当前格式数量来自本地数据库快照；切换音乐源、清库重扫或导入新文件后，页面数量会变化。
