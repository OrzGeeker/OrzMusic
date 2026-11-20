package com.jokerhub.orzmusic.core.util

import com.jokerhub.orzmusic.core.model.Song

/**
 * 本地缓存键。
 *
 * WAV 键包含解码器 namespace —— 与服务端把 `orz_build_info()` fingerprint
 * 写进 PCM 缓存键是同一口径:升级 OrzAudioCore / libopenmpt 后旧缓存自动失效。
 */
object CacheKey {

    fun raw(song: Song): String {
        val format = song.fileFormat.lowercase().ifBlank { "bin" }
        return "raw/${song.id}.$format"
    }

    fun wav(song: Song, decoderNamespace: String): String =
        "pcm/${song.id}-${decoderNamespace}.wav"
}
