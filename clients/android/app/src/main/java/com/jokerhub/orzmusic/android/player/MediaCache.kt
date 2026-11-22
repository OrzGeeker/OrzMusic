package com.jokerhub.orzmusic.android.player

import android.content.Context
import com.jokerhub.orzmusic.core.model.Song
import com.jokerhub.orzmusic.core.util.CacheKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * wasmDecode / 本地可解曲目的原始文件缓存:
 *
 *   下载原始模块文件 → cache/raw/<id>.<format> → 交给 `PcmStream` 流式解码
 *
 * 0.8.1 之前这里还会把整首曲解成 WAV（`cache/pcm/`，一首十几 MB），流式播放上线后
 * 那条路径就没有调用者了，已随 0.8.13 一并删除。
 *
 * 不做任何策略判断 —— 路由由 core 的 `PlaybackPolicy` 决定,
 * 这里只接收已经拼好的 raw URL。
 */
class MediaCache(context: Context) {

    private val root = File(context.cacheDir, "orzmusic")

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    /**
     * 只要原始文件（流式解码路径用）：不转 WAV、不碰 pcm/ 缓存。
     *
     * 由 `PlaybackRoute.Native` 进来后直接交给 `PcmStream` +
     * [PcmAudioSink]，避免“整首曲解成 WAV”的首播延迟与磁盘占用。
     */
    suspend fun rawFor(song: Song, rawUrl: String): File = withContext(Dispatchers.IO) {
        val raw = File(root, CacheKey.raw(song))
        if (!raw.isFile || raw.length() < 32L) {
            raw.parentFile?.mkdirs()
            download(rawUrl, raw)
        }
        raw
    }

    private fun download(url: String, dest: File) {
        val tmp = File(dest.parentFile, "${dest.name}.part")
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("下载失败: HTTP ${response.code}")
            val body = response.body ?: error("下载失败: 响应为空")
            tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }
}
