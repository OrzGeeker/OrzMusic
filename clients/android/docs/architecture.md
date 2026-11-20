# OrzMusic 客户端架构(Android / Apple / Web / Desktop)

本文回答一个问题:**同一套 OrzMusic,怎么让 Android / Apple / PC / Web 不各写一套语义,
又能顺利合并回 `OrzGeeker/OrzMusic` 一个仓库。**

结论先行:

- **解码分两层,别混在一起**:
  - `OrzAudioCore`(C ABI 1.0):**纯解码核心** —— 格式探测、元数据、subsong/seek、PCM 渲染;
  - `OrzAudioKit`(平台 SDK 层):JNI / Swift / TS 绑定 + WAV 容器 + 解码器指纹。
    Android 暴露为 `OrzAudioCore` / `AudioDecoder`;客户端只调平台 SDK,不直接碰 C ABI。
- **客户端核心**:API 模型、Transport、播放队列、播放策略、缓存键。
- **各端共享契约 + 语义 + 生成物**;UI / 音频输出 / HTTP 客户端 / 缓存位置各端实现。
- **仓库按 `server/` + `clients/` + `sdk/` + `core/spec` 拆**,OrzAudioCore 仍是独立 SDK。
- Android 当前:`:core`(纯 Kotlin)+ `:audio-kit`(平台解码 SDK)+ `:app`(UI/Media3)。

---

## 1. 参考实现:Web 端怎么分层

`OrzGeeker/OrzMusic` 的 Web 播放器已经把边界拆得很清楚,Android 应当镜像它:

| Web 文件 | 职责 | 对应 Android |
|---|---|---|
| `Resources/Public/audio/app.js` | UI 状态、`queue`/`queueIndex`/`activeList`、搜索、歌单 | `PlaybackQueue`(core)+ Compose 状态 |
| `Resources/Public/audio/player.js` | `OrzAudioPlayer`:三种策略、WASM 初始化、播放/暂停/seek、Media Session、Wake Lock | `PlaybackPolicy`(core)+ `PlaybackController`(Media3) |
| `Resources/Public/audio/orz-decoder-worker.js` | 加载 WASM、调 `orz_decoder_*`、渲染进 ring buffer | `OrzAudioCore`/`AudioDecoder`(audio-kit/JNI)+ `MediaCache` |
| `Resources/Public/audio/orz-audio-worklet.js` | 消费 ring buffer、重采样、输出 | Media3/ExoPlayer(播 WAV) |
| `Resources/Public/audio/decoder-manifest.generated.js` | 格式清单(id/label/group) | `DecoderManifest.kt`(生成) |
| `Sources/OrzAudioKit/` | Swift 对 C ABI 的封装 + fallback | `audio-kit` + `OrzAudioCore`/`AudioDecoder` |
| `Docs/orz-audio-core.md` | SDK 边界:只解码,不负责队列/HTTP/策略 | 同左 |

> 关键点:Web 端的 `app.js` 拥有队列,`player.js` 不拥有;
> `player.js` 拥有“播放策略”,SDK 不拥有。Android 也按这条线拆。

---

## 2. 分层

```
┌──────────────────────────────────────────────────────────────┐
│ UI                                                          │
│ Compose (Android) · Alpine (Web) · SwiftUI (Apple)          │
└──────────────────────────────┬───────────────────────────────┘
                               │
┌──────────────────────────────▼───────────────────────────────┐
│ Client Core(平台无关)                                       │
│  · API 模型 + Transport 契约                                 │
│  · PlaybackQueue(队列 / next / prev / activeList)            │
│  · PlaybackPolicy( directFile / serverDecode / native )      │
│  · DecoderManifest(当前 profile 编进来的格式)                │
│  · UrlResolver / CacheKey(含解码器指纹)                      │
└──────────────────────────────┬───────────────────────────────┘
                               │
┌──────────────────────────────▼───────────────────────────────┐
│ Platform SDK(OrzAudioKit)                                    │
│  Android: JNI + WAV + 指纹 · Swift: OrzAudioKit              │
│  Web: @orzmusic/audio-core(WASM)                             │
└──────────────────────────────┬───────────────────────────────┘
                               │
┌──────────────────────────────▼───────────────────────────────┐
│ OrzAudioCore(稳定 C ABI 1.0)                                │
│  orz_probe / orz_decoder_create_memory / render_f32 / seek   │
└──────────────────────────────────────────────────────────────┘

Platform Adapter(Media3 / AVFoundation / Web Audio)与 SDK 平级:
它消费 SDK 产出的本地 WAV,或直接播放 directFile / serverDecode 的 URL。
```

**硬约束**

- `core` 不 import Android / Compose / Retrofit / Media3 / JNI。
- `audio-kit`(平台 SDK)只做「解码 + WAV + 指纹」:不选策略、不拥队列、不拼 API URL、
  不决定缓存位置、不做 HTTP。
- `app` 的播放适配只消费 SDK 的输出,不直接调 JNI。
- `OrzAudioCore` 不做策略、队列、HTTP、缓存。SDK 文档已经这样定义。

### 2.1 为什么“解码便利层”放在 OrzAudioKit,不放进 C core

| 能力 | 放哪 | 理由 |
|---|---|---|
| probe / create / render PCM / seek / subsong / 元数据 | **OrzAudioCore(C ABI)** | 纯计算、零 I/O;WASM/原生共用;服务端也直接用 |
| JNI / Swift / TS 绑定 | **OrzAudioKit(平台 SDK)** | 各语言/各 ABI 的胶水,不能污染 C ABI |
| WAV 容器、PCM 落盘、解码器指纹 | **OrzAudioKit** | 容器/文件是平台或客户端关注点;服务端有自己的一套 WAV 缓存 |
| HTTP 下载、raw 文件获取 | **客户端 app** | SDK 不应该知道文件从哪来 |
| 缓存目录、LRU、清理 | **客户端 app** | 各平台存储权限/配额不同 |
| 播放策略、队列 | **客户端 core** | SDK 文档明确“播放策略不属于 SDK” |
| 播放设备(Media3/AVAudioEngine/Web Audio) | **客户端 adapter** | 各平台差异最大的一层 |

> 一句话:**C core 只解 PCM;WAV/文件/指纹放平台 SDK;策略/队列/HTTP/缓存放客户端。**
> 如果把这些都塞进 C ABI,服务端、Web WASM、Apple 会被迫带上一套不属于它们的 I/O。

---

## 3. 契约

### 3.1 `OrzMusicTransport`(HTTP)

```kotlin
interface OrzMusicTransport {
    val baseUrl: String
    fun updateBaseUrl(raw: String)
    fun absolute(path: String): String
    suspend fun stats(): Stats
    suspend fun formats(): FormatSummary
    suspend fun songs(page: Int, per: Int, format: String?): Page<Song>
    suspend fun search(query: String, format: String?): List<Song>
    suspend fun artists(page: Int, per: Int): Page<Artist>
    suspend fun artistSongs(artistId: String, page: Int, per: Int): Page<Song>
    suspend fun albums(page: Int, per: Int): Page<Album>
    suspend fun playlists(): List<Playlist>
    suspend fun playlist(id: String): Playlist
}
```

Android 用 Retrofit/OkHttp 实现;Apple 用 `URLSession`;Desktop 用 Ktor/OkHttp。
**API 的单一事实来源是后端 `GET /api/openapi.json`。**

### 3.2 `PlaybackQueue`(队列)

对齐 Web 的 `queue` / `queueIndex` / `activeList`:

- `setFrom(list, song)`:重置队列并定位;目标不在列表就追加。
- `next()` / `previous()`:越界返回 `null`。
- `remove(id)`:删当前项时索引落到下一首或末尾。
- `activeList(fallback)`:队列为空时回落到当前列表。

### 3.3 `PlaybackPolicy`(策略)

```kotlin
sealed interface PlaybackRoute {
    data class Remote(val url: String)      // directFile / serverDecode
    data class Native(val rawUrl: String)   // wasmDecode 且 profile 支持
    data class Unsupported(val format: String)
}
```

- `directFile` / `serverDecode` → `Remote`(`/stream`)
- `wasmDecode` + manifest 支持 → `Native`(`/raw`)
- `wasmDecode` + manifest 不支持 → `Unsupported`

### 3.4 `OrzAudioPlayer`(平台播放器)

```kotlin
interface OrzAudioPlayer {
    var onPlayingChanged: ((Boolean) -> Unit)?
    var onEnded: (() -> Unit)?
    var onError: ((String) -> Unit)?
    val isPlaying: Boolean
    val positionMs: Long
    val durationMs: Long
    fun play(source: AudioSource)  // Remote / Local
    fun toggle(); fun pause(); fun stop(); fun seekTo(ms: Long); fun release()
}
```

Android:Media3;Apple:AVPlayer/AVAudioEngine;Web:`<audio>`/AudioWorklet。
播放器**不拥有队列**。

### 3.5 `DecoderManifest`(生成)

- 单一事实来源(正式):OrzAudioCore `decoder-manifest.json`(含每个格式的 `profiles`);
- 当前 Android proto:`clients/android/audio-kit/native/decoder-profile.json`
  (`android-openmpt-builtin` 子集,等迁到 SDK 后改用上游 manifest);
- 生成器:`clients/android/scripts/gen-core-manifest.py`
- 产物:`core/.../DecoderManifest.kt`
- 与 Web 的 `decoder-manifest.generated.js`、Swift 的
  `DecoderManifest.generated.swift` 同层。
- **不要在 UI/Player 里手写格式白名单。**

### 3.7 SDK 形态对齐(C / Swift / Web / Android)

OrzAudioCore 已经有 C/C++、Swift、Web 三种 SDK。Android 用完全相同的方式:

| 平台 | 产物 | 包管理器 | 公开 API |
|---|---|---|---|
| C/C++ | `libOrzAudioCore.a/.so` + headers + CMake/pkg-config | CMake `find_package` | C ABI `orz_*` |
| Swift | `OrzAudioCore.xcframework` / SwiftPM | SPM | `AudioDecoder` |
| Web | `@orzmusic/audio-core` + WASM | npm | TS API |
| **Android** | **`audio-core-android-<sdkVersion>.aar`** | **Maven / Gradle** | **Kotlin `OrzAudioCore` / `AudioDecoder`** |

目标布局:`OrzAudioCore/SDK/Android/`(与 `SDK/Web/` 平级):

```
SDK/Android/
├── build.gradle.kts
├── consumer-rules.pro
├── src/main/
│   ├── java/com/orzgeeker/audiocore/
│   │   ├── OrzAudioCore.kt          # abiVersion / buildInfo / cacheFingerprint / probe / formats
│   │   ├── AudioDecoder.kt          # 流式 create/render/seek/subsong/close(对齐 Swift)
│   │   ├── DecoderManifest.generated.kt
│   │   └── WavFile.kt               # 对齐 Swift WAVFile
│   ├── cpp/orz_jni.c                # 只做 JNI 转换
│   └── jniLibs/<abi>/liborzaudio.so
└── native/                          # 可选:构建中间产物
```

发布坐标:`com.orzgeeker:audio-core-android:<sdkVersion>`
(当前 1.2.4;Android 绑定单独发版时用 `1.2.4-android.N`)。

**当前 `clients/android/audio-kit` 已经具备正式 SDK 的核心形状**:

- ✅ AAR + sources + POM + checksums(`maven-publish` 已跑通)
- ✅ JNI 流式 API:`createDecoder / renderF32 / seek / selectSubsong / destroy / probe`
- ✅ `AudioDecoder` / `WavFile.kt`,对齐 Swift
- ✅ `DecoderManifest.generated.kt` 由上游 `decoder-manifest.json` + profile 生成
- ✅ consumer rules、jniLibs、16KB page 对齐

还差的步骤:

1. 移到 `OrzAudioCore/SDK/Android/`;
2. 把 `script/build-native-libs.sh` 扩成 Android 版,加 `package-sdk-android.sh` / CI;
3. OrzMusic Android client 改为 Maven 坐标 + `audio-core-sdk.lock.json`。

详见 [`audio-kit/README.md`](../audio-kit/README.md)。

### 3.6 `CacheKey`(缓存 + 指纹)

- raw:`raw/<songId>.<format>`
- WAV:`pcm/<songId>-<decoderNamespace>.wav`
- `decoderNamespace` 来自 `decoder-profile.json`(OrzAudioCore 版本 + libopenmpt 版本)。
- 这是服务端把 `orz_build_info()` fingerprint 写进 PCM 缓存键的客户端等价物:
  升级 SDK 后旧 WAV 自动失效。

---

## 4. 当前 Android 结构

> 界面与 Web 播放器的逐项映射见 [`ui-parity.md`](ui-parity.md)。

```
clients/android/
├── core/                         纯 Kotlin(JVM),无 Android
│   ├── model/Models.kt           DTO + PlayStrategy
│   ├── api/OrzMusicTransport.kt  HTTP 契约
│   ├── playback/
│   │   ├── PlaybackQueue.kt
│   │   ├── PlaybackPolicy.kt
│   │   ├── OrzAudioPlayer.kt
│   │   └── DecoderManifest.kt    生成
│   └── util/                     UrlResolver / CacheKey
├── app/                          Android 客户端
│   ├── data/                     Retrofit 实现 + ApiFactory
│   ├── player/                   PlaybackController(Media3)/MediaCache
│   ├── ui/                       AppRoot / FormatCatalog / PlayerDock
│   │                             QueueDrawer / ShortcutsDialog / screens
│   └── (解码调用 :audio-kit)
└── audio-kit/                   平台解码 SDK(AAR + JNI + native/)
    ├── src/main/jniLibs/         liborzaudio.so(arm64 + armv7)
    ├── native/                   OrzAudioCore 源码 + JNI + profile
    └── README.md                 发布 / 迁移到 OrzAudioCore 的说明
```

依赖方向:

```
:app → :core
:app → :audio-kit → OrzAudioCore C ABI → libopenmpt
```

`:audio-kit` 可以发布成 `com.orzgeeker:audio-core-android:1.2.4`;
之后 Android client 用 Maven 坐标 + `audio-core-sdk.lock.json` 锁定,不再用 project 依赖。

`:core` 目前只依赖 `kotlinx.serialization-json`。它可以在下一步平移到
Kotlin Multiplatform 的 `commonMain`(Android/Desktop 共用),或者作为
`core/kotlin` 放进 monorepo。

---

## 5. 合并回 `OrzGeeker/OrzMusic` 的目录计划

> 本节是**终态**。分阶段怎么落地(第一步只搬 `clients/android`、不动 server/web、
> CI 与发版怎么接)见 [migration-upstream.md](migration-upstream.md)。

目标结构(建议):

```
OrzMusic/
├── Package.swift
├── server/                      ← Sources/App:Vapor、DB、CAS、扫描、API
├── clients/
│   ├── web/                     ← Resources/Public + Resources/Views
│   ├── android/                 ← 已落地(自 EasyAndroid 迁入)
│   ├── apple/                   ← SwiftUI(iOS/macOS)
│   └── desktop/                 ← 可选
├── sdk/
│   ├── audio-kit-android/       ← 当前 audio-kit(OrzAudioCore Kotlin/JNI)
│   ├── audio-kit-swift/         ← Sources/OrzAudioKit
│   └── audio-kit-web/           ← SDK/Web(@orzmusic/audio-core)
├── core/
│   ├── spec/                    OpenAPI + 播放/队列/缓存语义 + 测试向量
│   └── kotlin/                  KMP 核心(Android/Desktop)
├── script/
└── audio-core-sdk.lock.json     ← OrzAudioCore 仍是独立 SDK/system library
```

### server / clients / sdk 的边界

| 层 | 拥有 | 不拥有 |
|---|---|---|
| `server/` | HTTP API、DB/迁移、CAS、扫描、ffmpeg fallback、服务端 WAV 缓存 | 客户端队列/UI/客户端缓存 |
| `clients/*` | UI、播放适配、缓存目录/LRU、队列、策略 | 服务端模型/DB |
| `sdk/audio-kit-*` | 平台绑定、WAV 容器、解码器指纹、manifest 子集 | 策略、HTTP、缓存位置、UI |
| `OrzAudioCore` | 纯解码(C ABI) | 一切 I/O、策略、队列、缓存 |

**server 和 clients 不互相 import**:它们只共享 `core/spec` 的 API/语义/测试向量。
`server` 直接用 OrzAudioCore 做 `serverDecode`;`clients` 通过 `sdk/audio-kit-*` 解码。

### 迁移顺序

1. **现在**:`clients/android` 里保持 `:core` + `:audio-kit` + `:app`;语义已在 core,
   解码已在 audio-kit。
2. **第 1 步**:`Sources/App` → `server/`;`Resources/Public` + `Resources/Views` →
   `clients/web/`;`Sources/OrzAudioKit` → `sdk/audio-kit-swift/`;
   `SDK/Web` → `sdk/audio-kit-web/`。服务端和 Web 先独立可构建。
3. **第 2 步**:`core/` → `core/kotlin`(KMP);
   `audio-kit` → `sdk/audio-kit-android/`。Android 消费者改为依赖新坐标。
4. **第 3 步**:Apple 用 Swift 实现同一套 `OrzAudioPlayer` / `PlaybackQueue` /
   `PlaybackPolicy` 语义,依赖 `sdk/audio-kit-swift`;用 `core/spec` 做一致性验收。
5. **第 4 步**:Web 保持 TS 实现,但 API/策略/队列语义和 `decoder-manifest` 与其他端对齐。
6. **第 5 步**:monorepo CI 矩阵(Server Swift / Web npm / Android Gradle / Apple SPM)。

### 明确不共享的东西

- UI(Compose / SwiftUI / Alpine)
- 音频输出(Media3 / AVFoundation / Web Audio)
- 文件缓存实现(路径/权限/清理策略)
- HTTP 客户端(Retrofit / URLSession / Ktor)
- JNI / Swift / WASM 绑定代码

---

## 6. 现在就能复用的“基建”

| 资产 | 位置 | 谁用 |
|---|---|---|
| OpenAPI | 后端 `/api/openapi.json` | 所有客户端 |
| DTO 字段口径 | `core/model/Models.kt` | Android/Desktop;Apple 按同一 JSON |
| 分页 `page`/`per` | [backend-api.md](backend-api.md) | 所有客户端 |
| 播放策略表 | [backend-api.md](backend-api.md) / 本文 | 所有客户端 |
| 解码器 manifest | `audio-kit/native/decoder-profile.json` → 各端生成 | Android/Apple/Web |
| PCM 缓存指纹 | `orz_build_info()` / `CACHE_NAMESPACE` | Server + 所有客户端 |
| 队列/policy 测试向量 | `core/src/test` | 各端 conformance |
| 样例模块文件 | 后续放 `core/spec/fixtures` | 解码回归 |

---

## 7. 版本与指纹

- **Server**:`orz_build_info()` 的 fingerprint 写进 PCM WAV 缓存键。
- **Android**:`decoder-profile.json` 的 `cacheNamespace` 写进 WAV 文件名。
- **Apple/Desktop/Web**:同一规则;升级 SDK 后旧缓存自动失效。
- 版本锁定:OrzAudioCore 1.2.4 / libopenmpt 0.8.0 / NDK 27(Android)。
- 升级步骤:改 `decoder-profile.json` + `audio-kit/native/` 版本 → 重跑
  `clients/android/scripts/gen-core-manifest.py` → 重编 `.so` → 旧缓存自然失效。

---

## 8. 决策与风险

| 决策 | 选择 | 理由 |
|---|---|---|
| core 共享方式 | 现在纯 Kotlin JVM,后续 KMP | Swift 无法直接消费 Kotlin;但 Android/Desktop 可共享 |
| Apple 端 | Swift 原生实现同一语义 | `OrzAudioKit` 已经是 Swift;不要为了共享引入跨语言桥 |
| 策略放哪 | Client Core | SDK 文档明确“播放策略不属于 SDK” |
| 解码放哪 | C core + 平台 SDK | C core 只解 PCM;WAV/绑定/指纹在 OrzAudioKit |
| Android 输出 | Media3 播 WAV | 先复用成熟播放器;以后可换自定义 Renderer/AudioTrack,core 不变 |
| 解码格式范围 | 当前 openmpt + builtin | 覆盖约 90%;剩余格式回落到 Web;改 profile 即可扩 |
| 是否共享 UI | 否 | 四个端形态差异太大,共享 UI 的收益低于成本 |

**风险**

- 如果直接把业务逻辑写进 JNI/`MediaCache`,以后 Apple/PC 会复制一份不一致的实现。
  所以 core 的测试必须覆盖队列/策略/缓存键,并作为跨端 conformance 的基线。
- 如果手写格式白名单,升级 SDK 后会漂移。唯一来源必须是 `decoder-profile.json`。
- 如果各端各自决定缓存键,升级解码器后会出现“服务端已失效、客户端仍读旧 WAV”。
  统一用 SDK fingerprint / `CACHE_NAMESPACE`。

---

## 9. 验证

```bash
# 纯核心单测(不碰 Android/设备)
cd clients/android && ./gradlew :core:test

# 生成物一致性
python3 clients/android/scripts/gen-core-manifest.py --check

# 平台 SDK + App 构建 + 截图
./gradlew :audio-kit:assembleDebug :app:assembleDebug :app:testDebugUnitTest

# 真机(MOD 原生解码)—— 设备脚本在 EasyAndroid 仓库
EASYANDROID=/path/to/EasyAndroid
bash "$EASYANDROID/tools/tv-install.sh" "$(pwd)/app/build/outputs/apk/debug/app-debug.apk"
bash "$EASYANDROID/tools/pico-panel.sh" com.jokerhub.orzmusic.android awake
```
