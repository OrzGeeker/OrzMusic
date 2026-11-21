package com.jokerhub.orzmusic.core.playback

/**
 * 「这台设备能解哪些格式」的来源。
 *
 * 为什么不是直接用 [DecoderManifest]：清单是**编译期**产物（`decoder-profile.json`
 * → 生成的 Kotlin 常量），而真正能不能解取决于**运行时加载的那份 `.so`**。
 * 两者可能不同步：
 *
 *   · SDK 升级后重建了清单但忘了重建 `liborzaudio.so`；
 *   · 某次构建漏了某个 decoder（弱符号存根让它运行期不可用）；
 *   · 设备 ABI 不受支持（`System.loadLibrary` 失败）。
 *
 * 所以 `core/` 只依赖这个接口，Android 端传入
 * `OrzAudioCore.supportedFormats` 包出来的实现（清单 ∩ 运行时）。
 * 纯单测和网页端可以继续直接用 [DecoderManifest]。
 */
interface DecoderManifestSource {

    /** 本设备可用的格式。 */
    val formats: List<DecoderFormat>

    fun supports(format: String): Boolean

    fun label(format: String): String =
        formats.firstOrNull { it.id == format.lowercase() }?.label ?: format.uppercase()
}
