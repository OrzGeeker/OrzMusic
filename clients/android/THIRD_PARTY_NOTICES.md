# OrzMusic Android —— 第三方声明

OrzMusic Android 在 `liborzaudio.so` 中静态链接了以下开源组件。
完整许可证文本随源码存放在 `audio-kit/native/` 下。

## OrzAudioCore 1.2.4

- 用途:C ABI、解码器注册表、builtin YM/MIDI/BP 解码
- 许可证:**Apache-2.0**
- 源码:https://github.com/OrzGeeker/OrzAudioCore
- 本仓库副本:`audio-kit/native/orzaudio/`(含 `LICENSE`)

## libopenmpt 0.8.0

- 用途:XM / MOD / IT / S3M / MO3 / MTM / FC13 / FC14 解码
- 许可证:**BSD-3-Clause**
- 源码:https://lib.openmpt.org/
- 本仓库副本:`audio-kit/native/third_party/libopenmpt/LICENSE` 与头文件

## lhasa

- 用途:YM6 文件的 LHA 解压(`OrzAudioKitCXX/ym6/lhasa`)
- 许可证:见 `audio-kit/native/orzaudio/Sources/OrzAudioKitCXX/ym6/lhasa/COPYING.md`

## Android NDK / libc++

- `liborzaudio.so` 静态链接了 NDK 的 `c++_static`
- 许可证:Apache-2.0 with LLVM Exceptions(Android NDK 自带 NOTICE)

---

以上组件均按各自许可证分发;本项目不修改其许可证条款。
