# OrzMusic Android 客户端:后端口径与客户端结构

> **服务端契约(API 表、播放策略表、分页/错误)已上移到
> [`Docs/api.md`](../../../Docs/api.md)**,那里是唯一来源。
> 本文件只留**客户端侧**内容:怎么消费那三种策略、原生解码的约束、
> 客户端结构、构建/验收记录和已知缺口。

---

## 1. 客户端怎么消费三种播放策略

当前客户端的处理:

- `directFile` / `serverDecode` → Media3/ExoPlayer
- `wasmDecode` 且格式已编入 → 下载 raw → JNI 解成 WAV → ExoPlayer
- `wasmDecode` 且格式未编入 → 明确提示 + 「在网页播放器打开」

### 原生解码:方案 A 已落地

| 方案 | 状态 |
|---|---|
| **A. NDK 集成 OrzAudioCore** | ✅ 已实现。`liborzaudio.so` 内含 OrzAudioCore 1.2.4 + libopenmpt 0.8.0,通过 JNI 把模块文件解成 16-bit PCM WAV |
| B. 后端强制服务端解码(`?decode=server`) | 备选,未采用 |

已编入的格式:

| 解码器 | 格式 |
|---|---|
| libopenmpt | `xm` `mod` `it` `s3m` `mo3` `mtm` `fc13` `fc14` |
| OrzAudioCore builtin | `ym` `mid` `bp` |

未编入(`nsf` / `spc` / `sid` / `ahx` / `sap` / `v2m` / `rad` / `d00` / `hsc` / `amd`)
仍回落到网页播放器。注意 `sc68` 是 `serverDecode`,服务端已经转 WAV,不需要本地解码。

产物体积与约束:

- `liborzaudio.so` 约 **2.7MB(arm64)/ 2.2MB(armv7)**,静态链接 `c++_static`,不额外带 `libc++_shared.so`
- 链接时带 `-Wl,-z,max-page-size=16384`,已用 `readelf -l` 验证 LOAD 对齐 `0x4000`
- 重编:`bash clients/android/audio-kit/scripts/build-android.sh`(需要 NDK 27 + CMake 3.22)

---

## 2. 客户端结构

分层与跨端复用(Web / Apple / Desktop)见
[**architecture.md**](architecture.md)。
这里只列当前 Android 代码的落点。

```
clients/android/
├── core/                         纯 Kotlin(JVM),无 Android 依赖
│   ├── model/Models.kt           DTO + PlayStrategy
│   ├── api/OrzMusicTransport.kt  HTTP 契约(Retrofit/URLSession 各自实现)
│   ├── playback/
│   │   ├── PlaybackQueue.kt      队列 / next / prev(对齐 Web 的 queue)
│   │   ├── PlaybackPolicy.kt     策略选择(direct/server/native)
│   │   ├── OrzAudioPlayer.kt     平台播放器抽象
│   │   └── DecoderManifest.kt    由 audio-kit/native/decoder-profile.json 生成
│   └── util/                     UrlResolver / CacheKey(含解码器指纹)
├── app/                          Android
│   └── src/main/java/com/jokerhub/orzmusic/android/
│       ├── data/                 Retrofit 实现 + ApiFactory
│       ├── player/               PlaybackController(Media3)/MediaCache
│       │                         (解码调用 :audio-kit 的 OrzAudioCore)
│       └── ui/                   Compose / ViewModel / 自适应
└── audio-kit/                   平台解码 SDK(OrzAudioCore / AudioDecoder)
    ├── src/main/jniLibs/        liborzaudio.so
    └── native/                  C/JNI + OrzAudioCore 源码 + profile
```

依赖方向:`:app → :core` + `:app → :audio-kit`;`:core` 不 import Android/Compose/Media3/Retrofit,
`:audio-kit` 不依赖 core/Compose/Media3/Retrofit。

audio-kit 解码(平台 SDK):

```
clients/android/audio-kit/native/
├── cpp/                    CMakeLists.txt + orz_jni.c
├── orzaudio/               OrzAudioCore 1.2.4 源码(Apache-2.0)
└── third_party/libopenmpt/ 0.8.0 头文件 + LICENSE
clients/android/audio-kit/src/main/jniLibs/
├── arm64-v8a/liborzaudio.so
└── armeabi-v7a/liborzaudio.so
```

测试:

```bash
cd clients/android
./gradlew :core:test          # 队列 / 策略 / URL 纯 JVM 单测
./gradlew :app:assembleDebug
```

技术栈(与模板一致 + 本应用新增):

- Compose + `androidx.tv:tv-material:1.0.0`(TV 焦点与触屏点击共用)
- Retrofit 2.11 + OkHttp 4.12 + kotlinx.serialization 1.7.3
- `androidx.media3:media3-exoplayer:1.5.0`
- NDK 27 交叉编译的 `liborzaudio.so`(OrzAudioCore 1.2.4 + libopenmpt 0.8.0)
- 没有 Hilt / Room / Coil —— 当前复杂度不需要

### 自适应规则

只有一条判据:**可用宽度**。

| 宽度 | 布局 |
|---|---|
| < 760dp | 顶部品牌 + 标签行 + 单栏(手机竖屏、小平板) |
| ≥ 760dp | 左侧导航栏 + 主内容(平板横屏、TV、Pico 2D 面板) |
| 宽屏 + 艺术家/歌单 | 左列表 + 右详情两栏 |

TV 与 VR 都落在宽屏分支;TV 用 D-pad 走 `tv-material3` 的 `Card`,
Pico 直接触摸/遥控都行。

---

## 3. 构建与验收

```bash
cd clients/android

./gradlew assembleDebug            # debug 包(带自截图钩子)
./gradlew testDebugUnitTest        # JVM 截图:TV + 手机
./gradlew assembleRelease bundleRelease   # 正式包 + AAB
```

> `liborzaudio.so` 已提交在 `audio-kit/src/main/jniLibs/`,**普通构建不需要 NDK**。
> 只有改了 `audio-kit/native/cpp/` 或升级 libopenmpt / OrzAudioCore 时才跑:
>
> ```bash
> sdkmanager "ndk;27.0.12077973" "cmake;3.22.1"
> bash clients/android/audio-kit/scripts/build-android.sh
> ```

真机:

```bash
# TCL 电视
bash "$EASYANDROID/tools/tv-install.sh" "$(pwd)/app/build/outputs/apk/debug/app-debug.apk"

# Pico / 手机 / 平板
adb -s "$PICO_ADDR" install -r app/build/outputs/apk/debug/app-debug.apk

# 自截图
bash "$EASYANDROID/tools/ui-dump.sh" com.jokerhub.orzmusic.android --launch
```

测试里只渲染无状态的 `LibraryContent`,不碰网络/ViewModel ——
所以 `testDebugUnitTest` 在 CI 里也能稳定跑。

---

## 4. 开发时踩到的坑

1. **Kotlin 的块注释可以嵌套。** 在 KDoc 里写 `DTOs/*.swift` 会引入一个 `/*`,
   把后面的 `*/` 吃掉,报 `Unclosed comment` —— 整个包都编译不过。
   写路径时避开 `/*`,或写成 `DTOs/` 下的 `*.swift`。
2. **Retrofit 的 kotlinx-serialization converter 包名是
   `com.jakewharton.retrofit2.converter.kotlinx.serialization`**,
   不是 `retrofit2.converter.kotlinx.serialization`(后者是 2.x 的新坐标)。
3. **Vapor 分页用 `per`**,不是 `per_page`。
4. **Robolectric 4.17 跑 SDK 36 需要 JDK 21**,仓库统一 JDK 17 →
   `app/src/test/resources/robolectric.properties` 里钉 `sdk=35`。
5. **线上 API 对 Python urllib 的默认 UA 返回 403**(Cloudflare),
   用 `curl` 或带浏览器 UA 的客户端调试。
6. **`compileSdk = 36` 在 AGP 8.7.3 上会打 warning**,实测能构建/签名/出 AAB。
7. **OrzAudioCore 的 `stub_decoders.c` 用弱符号**,所以只编 openmpt 时**不用改上游**:
   同时编 `openmpt_impl.c`(强符号)和 `stub_decoders.c`(弱符号),链接器会选强符号。
   其它解码器(gme/sidplayfp/...)继续用弱符号存根,`audio_engine.c` 能正常链。
8. **libopenmpt 的 autotools 构建要按 ABI 给工具链**:
   arm64 用 `aarch64-linux-android29-clang`、**不要带** `-march=armv7-a`;
   armv7 用 `armv7a-linux-androideabi29-clang`。`--build` 要和构建机一致
   (`x86_64-pc-linux-gnu` / `arm64-apple-darwin`)。
9. **JNI 解码是同步阻塞的**:调用方必须放到 `Dispatchers.IO`;协程取消
   **不会**中断正在执行的 native 解码。客户端用「解码完成后检查 `current.id`」
   来丢弃过期结果。
10. **16KB page size**:targetSdk 36 要求 native 库支持 16KB pages,
    链接时加 `-Wl,-z,max-page-size=16384`,`readelf -l` 里 LOAD 的 Align 应为 `0x4000`。

---

## 5. 路线图

- [x] **模块音乐播放(主流格式)**:NDK 集成 OrzAudioCore + libopenmpt
- [ ] 其余芯片格式(`nsf` / `spc` / `sid` / `ahx` / `sap` / `v2m` / adplug 系)
- [ ] 解码进度 / 可取消(长曲目第一次解码要等几秒)
- [x] 解码缓存上限(按最后访问时间清理,300 MB)
- [x] 歌单创建 / 加载 / 删除(Android 队列面板)
- [ ] 专辑页(等后端有数据)
- [x] 后台播放 + MediaSession + 通知栏 / TV 播放控制
- [ ] 通知栏切歌在 Activity 被销毁后的独立队列(当前依赖 ViewModel)
- [ ] 封面图(等后端 album artwork)
- [ ] Android TV 推荐频道(Play 的 TV 形态因子要求)

---

## 6. 实机验收(2026-11-16)

| 设备 | 系统 | 结果 |
|---|---|---|
| TCL 电视 `tcl_mt5879_cn` | Android 11 / API 30 / armeabi-v7a | ✅ 装(TGuard,82s)、启动、曲库 5533 首加载、D-pad 焦点;MP3 播放;MOD **原生解码播放** |
| Pico 4 `A8110` | Android 10 / API 29 / arm64-v8a | ✅ `adb install`、宽屏(左导航 + 列表)渲染、曲库加载;MOD **原生解码播放** |

播放验证:

- MP3:筛选到 `MP3`(15 首)→ `reFX Trasher 2 VST v1.1`,进度 `0:01 / 0:08`
- MOD 原生解码:`Car Mechanic Simulator 2015`(15KB `.mod`)→ 播放页进度 `0:28 / 1:16`
- 设备上的解码产物:
  ```
  run-as <pkg> ls cache/orzmusic/pcm/  → <songId>.wav, 14,764,844 bytes
  ffprobe → pcm_s16le, 48000 Hz, stereo, 76.9 s
  ```
  说明 JNI → WAV → ExoPlayer 这条链路是通的,不是「放了个静音文件」。

### 验收时踩到的两个环境问题(不是 App 的 bug)

1. **电视上的 Clash Meta 代理把 API 全挡了。**
   `dumpsys connectivity` 里 VPN 网络的 `HttpProxy=127.125.11.172:37937`,
   而它的 bypass 列表只含 `192.168.*` / `localhost` / 少数域名,不含
   `music.jokerhub.cn`。App 表现为 `加载失败:timeout`。
   临时 `am force-stop com.github.metacubex.clash.meta` 后直连正常;
   验完已重新拉起 Clash(它的 VPN 需要用户在 App 里再开一次)。
2. **电视屏保会抢输入。**
   `mResumedActivity` 变成 `com.tcl.appreciate.art/...DreamActivity` 时,
   `am start` 返回成功但按键全被吞。测试期间用
   `svc power stayon true` + `settings put system screen_off_timeout 2147483647`
   临时压住,验完已恢复(`stayon false` / `120000` / `screensaver_enabled 1`)。

> Pico 重启后无线 ADB 会失效;这次是用 `bash tools/pico-usb.sh`(USB 插在 Windows
> 主机上)恢复的。之后 `adb install` 走无线,3 秒装完。
