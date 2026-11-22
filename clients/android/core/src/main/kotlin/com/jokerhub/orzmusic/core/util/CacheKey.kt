package com.jokerhub.orzmusic.core.util

import com.jokerhub.orzmusic.core.model.Song

/**
 * 本地缓存键。
 *
 * 只有原始文件这一种键：Android 走 `PcmStream` 流式解码，不再落 WAV
 * （旧的 `wav()` / `cache/pcm/` 已随 0.8.13 删除）。
 * 服务端仍把 `orz_build_info()` fingerprint 写进 PCM 缓存键，两者互不影响。
 */
object CacheKey {

    fun raw(song: Song): String {
        val format = song.fileFormat.lowercase().ifBlank { "bin" }
        return "raw/${song.id}.$format"
    }
}
