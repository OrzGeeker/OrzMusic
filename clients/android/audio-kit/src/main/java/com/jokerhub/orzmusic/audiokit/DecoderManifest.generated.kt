package com.jokerhub.orzmusic.audiokit

/**
 * **自动生成,不要手改。**
 *
 * 输入:
 *   · `clients/android/audio-kit/native/decoder-manifest.json`(OrzAudioCore 上游)
 *   · `clients/android/audio-kit/native/decoder-profile.json`(Android profile)
 * 生成器:`clients/android/audio-kit/scripts/gen-manifest.py`
 *
 * 与 Swift 的 `DecoderManifest.generated.swift`、Web 的
 * `decoder-manifest.generated.js` 同源。
 */
object DecoderManifest {

    const val PROFILE = "android-openmpt-builtin"
    const val SDK_VERSION = "1.2.4"
    const val LIBOPENMPT_VERSION = "0.8.0"
    const val CACHE_NAMESPACE = "orzaudio-1.2.4-libopenmpt-0.8.0"

    val formats: List<DecoderFormatInfo> = listOf(
        DecoderFormatInfo("xm", "XM", "openmpt", "0.8.0", "module", true, true),
        DecoderFormatInfo("mod", "MOD", "openmpt", "0.8.0", "module", true, true),
        DecoderFormatInfo("it", "IT", "openmpt", "0.8.0", "module", true, true),
        DecoderFormatInfo("s3m", "S3M", "openmpt", "0.8.0", "module", true, true),
        DecoderFormatInfo("mo3", "MO3", "openmpt", "0.8.0", "module", true, true),
        DecoderFormatInfo("mtm", "MTM", "openmpt", "0.8.0", "module", true, true),
        DecoderFormatInfo("fc13", "FC 1.3", "openmpt", "0.8.0", "module", true, true),
        DecoderFormatInfo("fc14", "FC 1.4", "openmpt", "0.8.0", "module", true, true),
        DecoderFormatInfo("ym", "YM", "ym6", "1", "console", false, false),
        DecoderFormatInfo("mid", "MIDI", "midi", "1", "synth", false, false),
        DecoderFormatInfo("bp", "SoundMon BP", "soundmon", "2", "synth", false, false),
    )

    val supportedIds: Set<String> = formats.map { it.id }.toSet()

    fun supports(format: String): Boolean = format.lowercase() in supportedIds
}
