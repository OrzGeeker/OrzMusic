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

    const val PROFILE = "android-full"
    const val SDK_VERSION = "1.3.0"
    const val LIBOPENMPT_VERSION = "0.8.0"
    const val CACHE_NAMESPACE = "orzaudio-1.3.0-full"

    val formats: List<DecoderFormatInfo> = listOf(
        DecoderFormatInfo("xm", "XM", "openmpt", "0.8.0", "module", true, true),
        DecoderFormatInfo("mod", "MOD", "openmpt", "0.8.0", "module", true, true),
        DecoderFormatInfo("it", "IT", "openmpt", "0.8.0", "module", true, true),
        DecoderFormatInfo("s3m", "S3M", "openmpt", "0.8.0", "module", true, true),
        DecoderFormatInfo("mo3", "MO3", "openmpt", "0.8.0", "module", true, true),
        DecoderFormatInfo("mtm", "MTM", "openmpt", "0.8.0", "module", true, true),
        DecoderFormatInfo("fc13", "FC 1.3", "openmpt", "0.8.0", "module", true, true),
        DecoderFormatInfo("fc14", "FC 1.4", "openmpt", "0.8.0", "module", true, true),
        DecoderFormatInfo("nsf", "NSF", "gme", "0.6.3", "console", false, false),
        DecoderFormatInfo("spc", "SPC", "gme", "0.6.3", "console", false, false),
        DecoderFormatInfo("sid", "SID", "sidplayfp", "3.0.2", "console", false, false),
        DecoderFormatInfo("sc68", "SC68", "sc68", "embedded", "console", false, false),
        DecoderFormatInfo("ym", "YM", "ym6", "1", "console", false, false),
        DecoderFormatInfo("sap", "SAP", "asap", "embedded", "console", false, false),
        DecoderFormatInfo("ahx", "AHX", "ahx2play", "embedded", "console", false, false),
        DecoderFormatInfo("thx", "THX", "ahx2play", "embedded", "console", false, false),
        DecoderFormatInfo("mid", "MIDI", "midi", "1", "synth", false, false),
        DecoderFormatInfo("v2m", "V2M", "v2m", "embedded", "synth", false, false),
        DecoderFormatInfo("bp", "SoundMon BP", "soundmon", "2", "synth", false, false),
        DecoderFormatInfo("rad", "RAD", "adplug", "2.4", "synth", false, false),
        DecoderFormatInfo("d00", "D00", "adplug", "2.4", "synth", false, false),
        DecoderFormatInfo("hsc", "HSC", "adplug", "2.4", "synth", false, false),
        DecoderFormatInfo("amd", "AMD", "adplug", "2.4", "synth", false, false),
    )

    val supportedIds: Set<String> = formats.map { it.id }.toSet()

    fun supports(format: String): Boolean = format.lowercase() in supportedIds
}
