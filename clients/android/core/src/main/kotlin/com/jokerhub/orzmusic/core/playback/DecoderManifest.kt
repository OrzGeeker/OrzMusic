package com.jokerhub.orzmusic.core.playback

/**
 * **自动生成,不要手改。**
 *
 * 单一事实来源:`clients/android/audio-kit/native/decoder-profile.json`
 * 生成器:`clients/android/scripts/gen-core-manifest.py`
 *
 * 与 Web 端 `decoder-manifest.generated.js` 同一层职责:
 * OrzAudioCore 提供完整 manifest,客户端按自己编进来的 profile 取子集。
 */
data class DecoderFormat(
    val id: String,
    val label: String,
    val group: String
)

object DecoderManifest {

    const val PROFILE = "android-openmpt-builtin"
    const val SDK_VERSION = "OrzAudioCore 1.2.4"
    const val LIBOPENMPT_VERSION = "0.8.0"

    /**
     * 解码器指纹:进 WAV 缓存键。
     * 升级 OrzAudioCore / libopenmpt 后,旧 WAV 自动失效 ——
     * 与服务端把 `orz_build_info()` fingerprint 写进 PCM 缓存键是同一口径。
     */
    const val CACHE_NAMESPACE = "orzaudio-1.2.4-libopenmpt-0.8.0"

    val formats: List<DecoderFormat> = listOf(
        DecoderFormat("xm", "XM", "modules"),
        DecoderFormat("mod", "MOD", "modules"),
        DecoderFormat("it", "IT", "modules"),
        DecoderFormat("s3m", "S3M", "modules"),
        DecoderFormat("mo3", "MO3", "modules"),
        DecoderFormat("mtm", "MTM", "modules"),
        DecoderFormat("fc13", "FC 1.3", "modules"),
        DecoderFormat("fc14", "FC 1.4", "modules"),
        DecoderFormat("ym", "YM", "retro"),
        DecoderFormat("mid", "MIDI", "synth"),
        DecoderFormat("bp", "SoundMon BP", "synth"),
    )
    val supportedIds: Set<String> = formats.map { it.id }.toSet()

    fun supports(format: String): Boolean = format.lowercase() in supportedIds

    fun label(format: String): String =
        formats.firstOrNull { it.id == format.lowercase() }?.label ?: format.uppercase()
}
