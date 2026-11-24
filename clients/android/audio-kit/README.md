# OrzAudioCore Android SDK(原型)

这是 OrzAudioCore 的 Android 绑定,和仓库里的 C/C++、Swift、Web SDK 同一层:

| 平台 | 发布物 | 包管理器 | 公开 API |
|---|---|---|---|
| C/C++ | `libOrzAudioCore.a/.so` + headers + CMake/pkg-config | CMake `find_package` | C ABI `orz_*` |
| Swift | `OrzAudioCore.xcframework` / SwiftPM | SPM | `AudioDecoder` |
| Web | `@orzmusic/audio-core` + WASM | npm | TS API |
| **Android** | **`audio-core-android-1.4.0.aar`** | **Maven / Gradle** | **Kotlin `OrzAudioCore` / `AudioDecoder`** |

目标是把它从「OrzMusic 内部的 `:audio-kit` 模块」提升为 **OrzAudioCore 仓库里的 `SDK/Android/`**,
由 SDK 统一负责 JNI + WAV + manifest + 指纹;客户端只依赖 AAR。

---

## 当前状态

- 模块:`clients/android/audio-kit`(Android Library)
- 坐标:`com.orzgeeker:audio-core-android:1.4.0`
- 产物:AAR + sources jar + POM(含 checksums)
- JNI:C ABI 1.0;`.so` 在 `src/main/jniLibs/{arm64-v8a,armeabi-v7a}/liborzaudio.so`
- 许可证:**GPL-3.0-or-later**(静态链接 GPL 解码器;见
  [`../THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md))
- 格式 profile:`android-full`(23 个格式)
- 仓库里的 `liborzaudio.so` 由上游 `OrzAudioCore-<ver>-android-full` 资产里的
  `libOrzAudioCore.a`(23 个格式)+ 本模块的 JNI 胶水链接而成:
  `make sdk-android` 装资产,`scripts/build-android.sh` 只编胶水(秒级)。
  没有资产时退回 vendored 源码 + 本地 libopenmpt(13 个格式)。
- 能力判定**始终**以运行时探测为准:`OrzAudioCore.nativeFormats`
  (← `orz_get_format_count/info`)∩ 生成的清单 = `OrzAudioCore.supportedFormats`
- 公开 API:
  - `OrzAudioCore.createDecoder(data, format, subsong)` → `AudioDecoder`
  - `AudioDecoder.renderF32 / seekMs / selectSubsong / cancel / close`
  - `PcmStream(decoder)`:解码线程 + 有界 ring buffer,给 `AudioTrack` 流式播放用
    (`read / seekTo / selectSubsong / isEnded / positionMs`)
  - `OrzAudioCore.probe / nativeFormats / supportedFormats / cacheFingerprint / buildInfo`
  - `WavFile.write(decoder, output)`(对齐 Swift `WAVFile`)
  - `OrzAudioCore.decodeToWav(...)`(便利层:整曲解成 WAV。App 内已无调用点,
    保留给 AAR 外部消费方;OrzMusic 自己的正式路径是 `PcmStream`)
- manifest:`DecoderManifest.generated.kt` 由上游 `decoder-manifest.json` + Android profile 生成
  (`clients/android/audio-kit/scripts/gen-manifest.py`),不再手写格式白名单。

## 构建 / 发布

```bash
cd clients/android

# 只构建 AAR
./gradlew :audio-kit:assembleRelease
# → audio-kit/build/outputs/aar/audio-kit-release.aar

# 发布到本地 Maven 仓库(默认 build/repo)
./gradlew :audio-kit:publishReleasePublicationToLocalRepository
# → audio-kit/build/repo/com/orzgeeker/audio-core-android/1.4.0/
#     audio-core-android-1.4.0.aar
#     audio-core-android-1.4.0-sources.jar
#     audio-core-android-1.4.0.pom
#     .module + sha256/sha512

# 发布到 ~/.m2(本机其它工程可直接用)
./gradlew :audio-kit:publishToMavenLocal
```

消费者(OrzMusic 之外的 Android 工程):

```kotlin
repositories {
    maven { url = uri("https://maven.pkg.github.com/OrzGeeker/OrzAudioCore") } // 或 Maven Central
}

dependencies {
    implementation("com.orzgeeker:audio-core-android:1.4.0")
}
```

`OrzMusic` 在 monorepo 里应通过 `audio-core-sdk.lock.json` 锁定版本 + sha256,
而不是直接 `project(":audio-kit")` —— 和 server/web/apple 锁定 SDK 的方式一致。

## 目标布局(OrzAudioCore/SDK/Android)

```
OrzAudioCore/
├── decoder-manifest.json              # 单一事实来源(格式 / decoder / profiles)
├── Sources/OrzAudioKitCXX/...         # C ABI(所有平台共用)
└── SDK/
    ├── Web/                           # 已有
    └── Android/                       # 本模块的目标位置
        ├── build.gradle.kts
        ├── consumer-rules.pro
        ├── src/main/
        │   ├── java/com/orzgeeker/audiocore/
        │   │   ├── OrzAudioCore.kt          # abiVersion / buildInfo / probe / formats
        │   │   ├── AudioDecoder.kt          # 流式 decode(对齐 Swift AudioDecoder)
        │   │   ├── DecoderManifest.generated.kt
        │   │   └── WavFile.kt               # 对齐 Swift WAVFile
        │   ├── cpp/orz_jni.c                # 只做 JNI 转换,不含业务策略
        │   └── jniLibs/<abi>/liborzaudio.so
        └── native/                          # 可选:构建脚本/静态库
```

### 构建 preset 建议

| preset | ABI | profile | 说明 |
|---|---|---|---|
| `android-arm64` | arm64-v8a | builtin-lite 或 full | 手机 / Pico |
| `android-armv7` | armeabi-v7a | builtin-lite 或 full | TCL 电视等 32 位 |
| `android-x86_64` | x86_64 | builtin-lite | 模拟器 / CI |

`full` 需要 `script/build-native-libs-android.sh` 先为每个 ABI 编出
`libopenmpt.a` / `libgme.a` / `libsidplayfp.a` / `libadplug.a` / `libbinio.a` /
`libasap.a` / `libsc68.a` / `libv2m.a` / `libahx2play.a`;
`builtin-lite` 不依赖第三方。

### manifest / 版本 / 缓存指纹

- `decoder-manifest.json` 是格式的单一事实来源;Android 生成
  `DecoderManifest.generated.kt`(和 Swift 的 `DecoderManifest.generated.swift`、
  Web 的 `decoder-manifest.generated.js` 同源)。
- AAR 版本 = `sdkVersion`(当前 1.4.0);如果 Android 绑定单独发版,用
  `1.4.0-android.N`,但解码指纹仍取 `orz_build_info()`。
- WAV/缓存键里必须带 `cacheFingerprint`(Swift 已经这样做),升级 SDK 后旧缓存失效。

## 与 C/Swift/Web 的差异

| 项 | C/C++ | Swift | Web | Android |
|---|---|---|---|---|
| 产物 | `.a/.so` + headers | `.xcframework` | `wasm` + JS | `.aar` |
| 包管理 | CMake / pkg-config | SPM | npm | Maven / Gradle |
| 绑定 | 无 | Swift | TS | Kotlin + JNI |
| 音频输出 | 调用方 | 调用方 | AudioWorklet | 调用方(Media3/AudioTrack) |
| 文件/容器 | 调用方 | `WAVFile.swift` | 浏览器 | `WavFile.kt` |
| 构建 | CMake preset | `build-xcframework.sh` | `build-wasm.sh` | Gradle + NDK |
| 发布 | tarball | XCFramework zip | npm | AAR + Maven |

## 迁移到 OrzAudioCore 的步骤

1. [ ] 把 `clients/android/audio-kit` 移到 `OrzAudioCore/SDK/Android`;
   `native/` 里的 OrzAudioCore 源码不再需要(直接引用仓库根 `Sources/OrzAudioKitCXX`)。
2. [x] `DecoderManifest.generated.kt` 由 `decoder-manifest.json` + profile 生成
   (搬到 SDK 后把 profile 变成构建 preset 的输入)。
3. [x] `AudioDecoder` 流式 API + `WavFile.kt`,对齐 Swift 的
   `AudioDecoder.swift` / `WAVFile.swift`。
4. [ ] 加 `script/build-native-libs-android.sh`、`script/package-sdk-android.sh`
   (AAR + manifest + licenses + sha256 + SBOM)。
5. [ ] CI:NDK 27 + CMake,构建 arm64/armv7/x86_64 AAR;16KB page 对齐;
   设备/模拟器上的 JNI decode 测试(fixtures 与 C/Swift/Web 共用)。
6. [ ] `OrzMusic` 的 Android client 改为消费 Maven 坐标 + lockfile。

> 目前只剩「搬进 OrzAudioCore + 打包/CI + 消费坐标」;
> JNI 边界、流式 API、manifest 生成、AAR 发布都已就位。

## `.so` 从锁定的上游资产构建,但仍提交入库(2026-11-21 更新)

OrzAudioCore **v1.3.0** 起才有 Android 产物,资产里是**自包含静态库**:

```
OrzAudioCore v1.4.0 的 Release 资产:
  OrzAudioCore-1.4.0-android-full.tar.gz          ← arm64-v8a / armeabi-v7a / x86_64
  OrzAudioCore-1.4.0-{linux-x86_64,linux-arm64}-server.tar.gz
  OrzAudioCore-1.4.0-macos-arm64-full.tar.gz
  OrzAudioCore-1.4.0-apple-full.xcframework.zip
  OrzAudioCore-1.4.0-web-full.tgz                 ← Web/WASM
```

所以 `.so` 的**来源**已经是可锁定、可校验的了(仓库根 `audio-core-sdk.lock.json`
的 `assets[]` 里 `android/full` 条目的 `sha256`):

```bash
make sdk-android                                   # 下载 + 校验 + 解到 native/.sdk(已 gitignore)
bash clients/android/audio-kit/scripts/build-android.sh   # 只编 JNI 胶水,链接上面的 .a
```

为什么产物仍然入库:

- `git clone` 之后 `./gradlew assembleDebug` **不需要 NDK**(4.5 MB + 3.9 MB),
  CI 与 Windows 开发者都能直接构建;
- 上游资产是静态库,链接 JNI 胶水这一步仍然需要 NDK,入库 `.so` 把这一步固化成
  「升级时才做」;
- 判定「本机究竟能解哪些格式」不看 `.so` 从哪来,只看运行时
  `orz_get_format_count()` / `orz_get_format_info()`。

依赖版本以 `audio-core-sdk.lock.json` 为准(当前 OrzAudioCore 1.4.0 / libopenmpt 0.8.0 /
NDK 27)。升级流程:重新生成 schema-v1 lock → `make sdk-android` → `build-android.sh` →
确认 `OrzAudioCore.supportedFormats` 仍是 23 项。
