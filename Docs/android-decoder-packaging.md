# Android 解码器打包方案（决策记录）

> 状态：已定方案，逐项落地中
> 相关：[`Docs/format-support.md`](format-support.md)、
> [`Docs/orz-audio-core.md`](orz-audio-core.md)、
> [`clients/android/THIRD_PARTY_NOTICES.md`](../clients/android/THIRD_PARTY_NOTICES.md)

## 要解决的问题

三端（服务端 native、Web WASM、Android）理论上共用同一个 OrzAudioCore，但 Android
只编进了 13 个格式（`android-openmpt-builtin` profile），剩下 10 个格式要么回落到
网页播放器，要么由服务端转 WAV（`sc68`）。目标：

1. Android 也能本地解全部 23 个格式；
2. 服务端 `serverDecode` 收敛为「本机解不了时」的兜底，而不是格式白名单；
3. 许可证边界清晰、可审计。

## 决策

| 项 | 决策 | 理由 |
|---|---|---|
| 打包形态 | **单模块、单个 `liborzaudio.so`**，不做 AAR 拆分、不做插件 dlopen、不做独立进程 | 工程成本最低（约 2 人日 vs 5~25 人日），且 `sc68`/`sid` 是模拟器实现，进程隔离的收益不足以抵消复杂度 |
| 许可证 | `clients/android/` 整体 **GPL-3.0-or-later**；`audio-core-android` AAR 的 POM 同样声明 GPL-3.0-or-later | 静态链接 GPL 解码器后整体是 GPLv3 作品。Apache-2.0 与 GPLv2 不兼容，只能升 v3 |
| profile | `android-full`（23 格式），`cacheNamespace = orzaudio-1.2.4-full` | 与 server/WASM 同源 |
| 能力判定 | **运行时探测为准**：`orz_get_format_count()` / `orz_get_format_info()` ∩ 生成的清单 | 清单先行、二进制后到也不会播错；漏编 decoder 时自动降级 |
| 播放形态 | 流式（`PcmStream` + `AudioTrack`），不再整曲转 WAV | 见 [`backlog/android-pcm-streaming.md`](backlog/android-pcm-streaming.md) |

### 被否决的备选

| 方案 | 为什么不要 |
|---|---|
| 双 AAR（Apache 主包 + GPL 附加包） | App 侧仍然是 GPLv3，多花的 3 人日只买到「AAR 保持宽松」，而 AAR 的使用方已确认可以接受 GPL |
| 插件 `.so` + `dlopen` | 必须改上游 ABI（注册表是编译期 const，`orz_register_decoder` 是 no-op），把 SDK 发版节奏绑死，法律强度还不如进程隔离 |
| 独立进程解码服务 | 能同时保住 App 的宽松许可 + 崩溃隔离，但要 20+ 人日做 AIDL/共享内存/生命周期，当前不值得 |
| 运行期下载 `.so` | 撞 Google Play「不得下载可执行代码」政策 |

## 许可证义务（GPLv3）

1. 分发 APK/AAB 时提供**同一 tag 的对应源码**（含 native 重建脚本）。
2. App 内可访问「开源许可 / 源码」入口；商店描述给出源码地址。
3. 第三方组件声明随二进制提供。
4. **不得**加入签名校验 / Play Integrity 这类防篡改（§6 反锁定）。

机器能查的项由 `bash clients/android/scripts/store-check.sh --target play` 的
「GPLv3 合规」小节校验。

## profile 与二进制的关系

```
audio-kit/native/decoder-profile.json          ← 单一事实来源(23 格式)
        ├── gen-manifest.py        → audio-kit/.../DecoderManifest.generated.kt
        └── gen-core-manifest.py   → core/.../DecoderManifest.kt
                                            ↓
                              OrzAudioCore.nativeFormats(运行时探测)
                                            ↓
                        supportedFormats = 清单 ∩ 运行时
```

`PlaybackPolicy.route(song, transport, manifest)` 的第三参数类型是
`DecoderManifestSource`，Android 端传入 `RuntimeDecoderManifest`（走
`OrzAudioCore`），纯单测与网页端继续直接用 `DecoderManifest`。

## 构建路径

**上游资产（已具备生成能力）。** OrzAudioCore 仓库现在自带 Android 交叉编译与打包：

```bash
# 在 OrzAudioCore 仓库
export ANDROID_NDK_HOME=/path/to/ndk/27.0.12077973
./script/fetch-decoder-sources.sh
./script/build-android.sh                # arm64-v8a + armeabi-v7a(可加 --abi x86_64)
./script/package-android-sdk.sh ~/release
# → OrzAudioCore-<ver>-android-full.tar.gz
```

`.github/workflows/release.yml` 新增 `android` job，打 `v*` tag 时自动产出该资产
（含 SBOM、每个 ABI 的 sha256、全部第三方许可证原文）。资产布局：

```
OrzAudioCore-<ver>-android-full/
  android/<abi>/libOrzAudioCore.so     # 共享库,可直接 System.loadLibrary
  android/<abi>/libOrzAudioCore.a      # 静态库(OrzMusic 用它嵌 JNI,单 .so 分发)
  native/include/orz_audio_core.h
  metadata/{decoder-manifest.json,android.json,build-info.json,sbom.cdx.json}
  licenses/*
```

**本仓库的接入已经写好**，只等资产发布：

1. `audio-core-sdk.lock.json` 增加 `androidAssets` / `androidAssetsSha256`；
2. `make sdk-android` → `script/update-audio-core-android.sh` 下载、校验 sha256、
   解包到 `clients/android/audio-kit/native/.sdk/`（gitignored）；
3. `audio-kit/scripts/build-android.sh` 自动检测 `.sdk/android-<abi>/libOrzAudioCore.a`，
   走到 `ORZ_PREBUILT_CORE` 分支——**只编 JNI 胶水**，链接 23 格式的核心库；
4. 没有资产时退回 vendored 源码路径（只编 libopenmpt，13 格式），行为与今天一致。

**许可证结论（已核实源码原文）**：真正触发 copyleft 的只有 `sid`(libsidplayfp)、
`sap`(asap)、`sc68` 三个，均为 **GPL-2.0-or-later / GPL-3.0-or-later**，可统一到 GPLv3。
`v2m` 是 **Artistic-2.0**、`ahx`/`thx` 是 **BSD-3-Clause**、`nsf`/`spc` 与 adplug 系是
**LGPL-2.1-or-later**——都比之前标注的更宽松，但既然整个 `clients/android/` 已经统一
走 GPLv3，不需要再拆分。

**不打包 C64 ROM**：`sid` 只保证 PSID 曲目；需要 kernal/basic ROM 的 RSID 曲目由
调用方自行处理（OrzMusic 这边保留服务端兜底）。

## 验证

| 检查 | 命令 |
|---|---|
| 清单未漂移 | `python3 clients/android/audio-kit/scripts/gen-manifest.py --check` |
| core 清单未漂移 | `python3 clients/android/scripts/gen-core-manifest.py --check` |
| 策略单测 | `cd clients/android && ./gradlew :core:test` |
| 上架合规 | `bash clients/android/scripts/store-check.sh --target play` |
| 真实能力 | 装机后看 `OrzAudioCore.supportedFormats` 是否等于 profile 的 23 项 |

最后一项没有自动化：`liborzaudio.so` 是 ARM 产物，CI 跑不起来。重建 `.so` 后必须在
设备上确认一遍；清单/二进制不一致时的表现是「格式被静默隐藏」，不会播错。

## 已完成

- [x] `clients/android/LICENSE` = GPLv3 全文；根 `README.md` 加许可证范围表
- [x] `audio-core-android` 的 POM 改为 `GPL-3.0-or-later`
- [x] `THIRD_PARTY_NOTICES.md` 重写（含 §6 源码获取方式、不打包 C64 ROM 的说明）
- [x] `store/play.md` / `store/cn.md` 增加 GPL 声明与源码地址
- [x] `store-check.sh` 的「GPLv3 合规」小节（许可证全文、声明完整性、反锁定扫描、源码地址）
- [x] `android-release.yml` 随 Release 上传 `-source.tar.gz`
- [x] `decoder-profile.json` → `android-full`（23 格式），两份生成物同步
- [x] 运行时能力探测：JNI `nativeFormatIds()`，Kotlin `OrzAudioCore.nativeFormats`，
      `PlaybackPolicy` 改吃 `DecoderManifestSource`
- [x] native-first 路由：本机能解的 `serverDecode` 格式不再请求服务端 WAV
- [x] 流式解码基础件：`PcmStream` / `PcmAudioSink` / `MediaCache.rawFor()`
- [x] `orz_jni.c` 增加 `nativeCancel`（安全关闭顺序：cancel → join → destroy）

## 本机实测记录（2026-11）

工具链放在仓库外：

| 组件 | 路径 |
|---|---|
| Android NDK r27b | `/mnt/e/dev/toolchains/android-ndk-r27b` |
| Android SDK（platform 36 / build-tools 36） | `/mnt/e/dev/toolchains/android-sdk`（`clients/android/local.properties` 指向它，已 gitignore） |
| 构建产物 | `/mnt/e/dev/orz-assets/OrzAudioCore-1.2.4-android-full.tar.gz`（sha256 `2691354ad7ff…`） |

实测结论：

- 上游两个 ABI 交叉编译通过：`libOrzAudioCore.so` 7.2MB（arm64）/ 6.3MB（armv7），
  LOAD 段对齐 `0x4000`（16KB），只导出 `orz_*`；自包含静态库 17MB / 15MB。
- 本仓库 `liborzaudio.so` 重建为 23 格式：arm64 4.5MB、armv7 3.9MB，
  **只导出 11 个 JNI 符号**（上游所有第三方符号都被 version script 收成 local——
  不做这一步会导出 5695 个符号）。
- `:core:test` / `:app:testDebugUnitTest` / `:app:assembleDebug` / `:app:lintDebug`
  全部通过，APK 里两个 ABI 的 `.so` 都是新构建，并附带
  `assets/licenses/{GPL-3.0.txt,THIRD_PARTY_NOTICES.md}`。
- 播放：`OrzPlaybackPlayer`（Media3 `SimpleBasePlayer`）+ `NativePcmPlayback`
  （`PcmStream` + `AudioTrack`）；服务端改动在 `swift:6.1-noble` 容器里
  `swift build` 通过，`ServerDecodeHitTrackerTests` 等 11 个用例全绿。

踩到并已修掉的上游问题（都提交在 OrzAudioCore 里）：

1. `find_library(... NO_DEFAULT_PATH)` 在 NDK 工具链下会被 `CMAKE_FIND_ROOT_PATH` 重定向，
   加 `NO_CMAKE_FIND_ROOT_PATH` 才能找到那些 `.a`。
2. 只出 `ORZ_BUILD_SHARED=ON` 的 `.so` 时没有静态库，消费方没法做单 `.so` 分发 →
   新增 `build_core_static`（同一 CMakeLists，`ORZ_BUILD_SHARED=OFF` 再编一遍）。
3. 合并静态库：11 个 `.a` 合成一个自包含 `libOrzAudioCore.a`，
   否则消费方要关心 9 个依赖的链接顺序。

## 发布流程（需要你的凭据）

```bash
cd /mnt/e/dev/OrzAudioCore
git add -A
git commit -m "feat(android): full-profile cross build, SDK asset packaging, release job"

# 1) 先把版本号从 1.2.4 涨到 1.3.0(新增平台产物 = minor),
#    check-release-readiness.mjs 会校验下面这些地方必须一致:
#      decoder-manifest.json            sdkVersion
#      CMakeLists.txt                   project(... VERSION)
#      SDK/Web/decoder-manifest.json    sdkVersion
#      SDK/Web/package.json(.lock)      version
#    然后 node script/check-release-readiness.mjs 1.3.0 必须通过。
# 2) 打 tag 推送,tag 会触发 release.yml 的 android job:
git tag v1.3.0 && git push origin main --tags
```

CI 产出 `OrzAudioCore-1.3.0-android-full.tar.gz` 之后，回本仓库：

```bash
# lock 里补 androidAssets / androidAssetsSha256(从 release 的 .sha256 抄)
make sdk-android
bash clients/android/audio-kit/scripts/build-android.sh
cd clients/android && ./gradlew assembleDebug
```

## 剩余待办

- [x] 上游具备 Android full 交叉编译 + 打包 + CI job（OrzAudioCore
      `script/build-android.sh` / `script/package-android-sdk.sh` / release `android` job）
- [x] 上游 `script/build-android.sh` / `script/package-android-sdk.sh` 已在本机实测跑通
      （NDK r27b）：两个 ABI 的 `libOrzAudioCore.so`（7.2MB / 6.3MB，16KB 对齐）
      + 自包含静态库（17MB / 15MB），资产 `OrzAudioCore-1.2.4-android-full.tar.gz`
      sha256 `2691354ad7ffe3cbc6cd0990eb0fea3bdf425cc981d58f6eadb0a454a1f5bfe9`
- [x] `liborzaudio.so` 已重建为 23 格式（arm64 5.1MB / armv7 4.3MB）
- [ ] 上游发一个带 `android` job 的 tag，把 `androidAssets` 写进 lock
      （在那之前用 `bash script/update-audio-core-android.sh --local <tarball>`）
- [ ] 替换 `audio-kit/src/main/jniLibs/*/liborzaudio.so`（替换后在设备上确认
      `OrzAudioCore.supportedFormats` = 23 项）
- [x] Media3 桥：`OrzPlaybackPlayer`（`SimpleBasePlayer`）把 `PcmStream` 接进
      `PlaybackService`，通知栏/锁屏/耳机线控行为不变 ——
      见 [`backlog/android-pcm-streaming.md`](backlog/android-pcm-streaming.md)
- [x] 核实许可证：`v2m` = Artistic-2.0、`ahx2play` = BSD-3-Clause、
      `asap`/`sidplayfp` = GPL-2.0-or-later、`sc68` = GPL-3.0-or-later（见上文）
- [x] 确认不打包 C64 ROM（`sid` 只保证 PSID）
- [x] App 内「开源许可 / 源码」页：设置页 → 按钮，内容来自 APK assets
      （构建时从仓库根拷贝 `LICENSE` 与 `THIRD_PARTY_NOTICES.md`，不会漂移）
- [x] 服务端 `warm-decode-cache` 按实际命中收敛：`make decode-hits` 看命中，
      `MIN_HITS` / `HITS_WINDOW_DAYS` 过滤（见 `Docs/performance.md`）
