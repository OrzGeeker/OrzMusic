# native —— OrzAudioCore + libopenmpt 的 Android 解码库

这里的代码**不是** Gradle 的 `externalNativeBuild`,而是用
[`clients/android/audio-kit/scripts/build-android.sh`](../scripts/build-android.sh)
离线交叉编译,把 `.so` 提交到 `audio-kit/src/main/jniLibs/`。

这样做的原因:

- 普通 `./gradlew assembleDebug` **不需要 NDK** —— 和仓库其它工程一样一把过;
- 产物只有 2.7MB(arm64)+ 2.2MB(armv7),比提交 libopenmpt 的静态库
  (9MB + 8.6MB)小得多;
- 解码库版本固定,不会因为 NDK/CMake 升级悄悄变化。

## 目录

```
native/
├── cpp/
│   ├── CMakeLists.txt     # 编译 liborzaudio.so
│   └── orz_jni.c          # JNI:解码成 16-bit PCM WAV
├── orzaudio/              # OrzAudioCore 1.3.0 源码(Apache-2.0)
│   ├── LICENSE
│   └── Sources/OrzAudioKitCXX/
│       ├── dispatch/      # C ABI + 解码器注册表
│       ├── openmpt/       # libopenmpt 适配
│       ├── bp/ midi/ ym6/ # builtin 解码器
│       ├── helpers/       # C++ 异常安全包装
│       └── stub_decoders.c# 未编译解码器的弱符号
└── third_party/
    └── libopenmpt/
        ├── LICENSE        # BSD-3-Clause
        └── include/libopenmpt/*.h
```

## 支持哪些格式

| 来源 | 格式 |
|---|---|
| libopenmpt | `xm` `mod` `it` `s3m` `mo3` `mtm` `fc13` `fc14` |
| OrzAudioCore builtin | `ym` `mid` `bp` |
| 目标 profile(`android-full`) | 上面 11 个 + `nsf` `spc` `sid` `sc68` `sap` `ahx` `thx` `v2m` `rad` `d00` `hsc` `amd` = 23 |

`audio-kit/native/decoder-profile.json` 是 `android-full`(23 格式),仓库里提交的
`liborzaudio.so` 也已经用上游 `libOrzAudioCore.a`(23 格式)重建过。能力判定仍以
**运行时探测**为准:

```
OrzAudioCore.nativeFormats              ← orz_get_format_count/info
OrzAudioCore.supportedFormats           ← 生成的清单 ∩ nativeFormats
```

所以二进制与清单不同步时只会让格式被静默隐藏,不会播错(重新构建时
`make sdk-android` + `build-android.sh` 会把两者对齐)。

构建方式见 [`../../../Docs/android-decoder-packaging.md`](../../../Docs/android-decoder-packaging.md)
和 `clients/android/audio-kit/README.md` 的「构建 / 发布」。

## 重新编译

依赖:

```bash
sdkmanager "ndk;27.0.12077973" "cmake;3.22.1"
```

然后(在 WSL2 / Linux / macOS 上):

```bash
bash clients/android/audio-kit/scripts/build-android.sh              # 两个 ABI
bash clients/android/audio-kit/scripts/build-android.sh --abi arm64-v8a
bash clients/android/audio-kit/scripts/build-android.sh --clean      # 删 audio-kit/native/.build
```

脚本会:

1. 下载 libopenmpt 0.8.0 源码到 `audio-kit/native/.build/`
2. 用 NDK clang 为 `arm64-v8a` / `armeabi-v7a` 编 `libopenmpt.a`
3. 用 CMake + NDK 编 `liborzaudio.so`
4. `llvm-strip` 后写入 `audio-kit/src/main/jniLibs/<abi>/`

> ⚠️ Windows 原生 Git Bash 下 NDK 的 autotools/路径转换不可靠,脚本会拒绝执行;
> Windows 直接用仓库里已提交的 `.so`。

## 版本与 ABI 约束

| 项 | 值 |
|---|---|
| OrzAudioCore | 1.3.0(源码 vendored) |
| libopenmpt | 0.8.0 |
| NDK | 27.0.12077973 |
| minSdk / `ANDROID_PLATFORM` | 29 |
| 16KB page size | 链接时 `-Wl,-z,max-page-size=16384`,已用 `readelf -l` 验证 LOAD 对齐 0x4000 |
| STL | `c++_static`(不额外带 `libc++_shared.so`) |

x86 / x86_64 没有编译;那些设备上 `NativeDecoder.isAvailable == false`,
App 会自动回落到「网页播放器」而不是崩溃。

## 许可证

- OrzAudioCore:Apache-2.0(`orzaudio/LICENSE`)
- libopenmpt:BSD-3-Clause(`third_party/libopenmpt/LICENSE`)
- lhasa(YM6 解压):`orzaudio/Sources/OrzAudioKitCXX/ym6/lhasa/COPYING.md`

App 的对外声明见 [`../THIRD_PARTY_NOTICES.md`](../../THIRD_PARTY_NOTICES.md)。
