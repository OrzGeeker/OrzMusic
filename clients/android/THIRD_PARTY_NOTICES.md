# OrzMusic Android —— 许可证与第三方声明

## 本目录的整体许可证

`clients/android/`（`app/`、`core/`、`audio-kit/`、`audio-kit/native/`）按
**GNU GPL v3.0 或更新版本**分发，全文见 [`LICENSE`](LICENSE)。

原因：`liborzaudio.so` 静态链接了 GPL 系解码器（`sidplayfp`、`sc68`、`asap` 等），
链接后的整体构成 GPLv3 作品。仓库根的 `LICENSE`（Apache-2.0）只覆盖服务端与 Web 端
（`Sources/`、`Resources/`），不覆盖本目录。

对外的 SDK 产物 `com.orzgeeker:audio-core-android` 的 POM 同样声明 `GPL-3.0-or-later`。

## 对应源码（GPLv3 §6）

GPLv3 要求分发二进制时提供**完整对应源码**，包括能重建同一 `.so` 的构建脚本。

| 内容 | 位置 |
|---|---|
| 客户端源码 | 本仓库，tag `android-v<version>`（与 APK 同一 tag） |
| 解码 SDK 与适配层 | 本仓库 `clients/android/audio-kit/native/`（`orzaudio/`） |
| 重新编译 `liborzaudio.so` | `clients/android/audio-kit/scripts/build-android.sh` |
| 上游 OrzAudioCore | <https://github.com/OrzGeeker/OrzAudioCore>，版本见 [`../../audio-core-sdk.lock.json`](../../audio-core-sdk.lock.json) |

## 静态链接进 `liborzaudio.so` 的组件

| 组件 | 用途（格式） | 版本 | 许可证 | 源码 | 本仓库副本 |
|---|---|---|---|---|---|
| OrzAudioCore | C ABI、解码器注册表；builtin 解码器 `ym6`(`ym`) / `midi`(`mid`) / `soundmon`(`bp`) | 1.3.1 | Apache-2.0 | <https://github.com/OrzGeeker/OrzAudioCore> | `audio-kit/native/orzaudio/`（含 `LICENSE`） |
| libopenmpt | `xm` `mod` `it` `s3m` `mo3` `mtm` `fc13` `fc14` | 0.8.0 | BSD-3-Clause | <https://lib.openmpt.org/> | `audio-kit/native/third_party/libopenmpt/` |
| lhasa | YM6 文件的 LHA 解压 | 0.4.x | ISC | <https://github.com/fragglet/lhasa> | `audio-kit/native/orzaudio/Sources/OrzAudioKitCXX/ym6/lhasa/COPYING.md` |
| Android NDK / libc++ | `c++_static` | NDK 27.0.12077973 | Apache-2.0 WITH LLVM-exception | — | Android NDK 自带 NOTICE |

## 随 `android-full` profile 编入的组件

> 下列组件在 `decoder-profile.json` 切换到 `android-full` 后进入 `liborzaudio.so`。
> 许可证已按上游源码里的 `LICENSE` / `COPYING` 原文核对（2026-11，OrzAudioCore
> `script/fetch-decoder-sources.sh` 锁定的那几份源码）。

| 组件 | 用途（格式） | 版本 | 许可证 | 源码 |
|---|---|---|---|---|
| libgme (Game Music Emu) | `nsf` `spc` | 0.6.3 | LGPL-2.1-or-later | <https://github.com/libgme/game-music-emu> |
| libadplug + libbinio | `rad` `d00` `hsc` `amd` | 2.4 / 1.5 | LGPL-2.1-or-later | <https://github.com/adplug/adplug> · <https://github.com/adplug/libbinio> |
| libsidplayfp | `sid` | 3.0.2 | GPL-2.0-or-later | <https://github.com/libsidplayfp/libsidplayfp> |
| libsc68（webaudio68） | `sc68` | 2.2.1 | GPL-3.0-or-later | <https://github.com/wothke/webaudio68>（源码随 OrzAudioCore `third_party/sources/` 锁定） |
| ASAP | `sap` | 8.0.0 | GPL-2.0-or-later | <https://asap.sourceforge.net/>（Piotr Fusik） |
| ahx2play | `ahx` `thx` | 6861c29 | **BSD-3-Clause** | 源码随 OrzAudioCore `third_party/sources/ahx2play-*.tar.gz` 锁定 |
| v2m-player | `v2m` | 2d6b6aa | **Artistic-2.0** | <https://github.com/Sound-Linux-More/v2mplayer>（源码随 OrzAudioCore 锁定） |

上述 LGPL 组件随 GPLv3 整体分发时，LGPL-2.1 §3 允许按 GPLv3 条款再许可，
因此不需要额外的重链接材料——这正是选择"整个 `clients/android/` 走 GPLv3"的原因。

真正触发 copyleft 的只有 `sid` / `sap` / `sc68` 三个（GPL-2.0-or-later /
GPL-3.0-or-later，可统一到 GPLv3）。`v2m`（Artistic-2.0）与 `ahx`/`thx`
（BSD-3-Clause）本身是宽松许可，与 GPLv3 兼容。

## 不随包分发的内容

- **C64 kernal / basic ROM**：`sidplayfp` 播放 RSID 需要它，但 ROM 版权属 Cloanto，
  不随 APK 分发。`sid` 只保证 PSID 可播；需要 ROM 的曲目保留服务端兜底。
- **服务端 PCM WAV 缓存**：由服务端按需生成，不进入客户端产物。

## 合规检查

`bash scripts/store-check.sh --target play` 会校验本文件、`LICENSE` 与源码链接是否存在，
并阻止在 App 内加入签名校验 / Play Integrity 这类防篡改（会违反 GPLv3 §6 的反锁定条款）。
