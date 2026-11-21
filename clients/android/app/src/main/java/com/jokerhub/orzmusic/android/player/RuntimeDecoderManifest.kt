package com.jokerhub.orzmusic.android.player

import com.jokerhub.orzmusic.audiokit.OrzAudioCore
import com.jokerhub.orzmusic.core.playback.DecoderFormat
import com.jokerhub.orzmusic.core.playback.DecoderManifest
import com.jokerhub.orzmusic.core.playback.DecoderManifestSource

/**
 * 「本机真的能解哪些格式」—— 清单（生成的 `DecoderManifest`）∩ 运行时探测
 * （`OrzAudioCore.nativeFormats`，来自 `orz_get_format_info()`）。
 *
 * 两者会不同步：SDK 升级后重建了清单但没重建 `liborzaudio.so`、某次构建漏了
 * decoder、设备 ABI 不受支持。以运行时为准，清单只提供标签与分组。
 *
 * 为什么不直接查 `DecoderManifest`：那是编译期常量，它说「这个 SDK 版本支持」
 * 而不是「这台设备支持」。详见 `Docs/android-decoder-packaging.md`。
 */
object RuntimeDecoderManifest : DecoderManifestSource {

    override val formats: List<DecoderFormat>
        get() {
            val installed = OrzAudioCore.nativeFormats
            return DecoderManifest.formats.filter { it.id in installed }
        }

    override fun supports(format: String): Boolean =
        format.lowercase() in OrzAudioCore.nativeFormats
}
