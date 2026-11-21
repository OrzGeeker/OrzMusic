package com.jokerhub.orzmusic.android.player

import android.content.Context
import com.jokerhub.orzmusic.core.model.Song
import com.jokerhub.orzmusic.audiokit.OrzAudioCore
import com.jokerhub.orzmusic.core.playback.DecoderManifest
import com.jokerhub.orzmusic.core.util.CacheKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * wasmDecode 曲目的本地缓存:
 *
 *   1. 下载原始模块文件 → cache/raw/<id>.<format>
 *   2. 用 OrzAudioCore 解成 WAV → cache/pcm/<id>-<decoderNamespace>.wav
 *   3. 平台播放器播放 WAV
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

    suspend fun wavFor(song: Song, rawUrl: String): File = withContext(Dispatchers.IO) {
        if (!OrzAudioCore.isAvailable) {
            error("当前设备的 ABI 没有原生解码器")
        }

        val raw = File(root, CacheKey.raw(song))
        if (!raw.isFile || raw.length() < 32L) {
            raw.parentFile?.mkdirs()
            download(rawUrl, raw)
        }

        val wav = File(root, CacheKey.wav(song, DecoderManifest.CACHE_NAMESPACE))
        OrzAudioCore.decodeToWav(raw, wav, song.fileFormat)
        trimPcmCache(keep = wav)
        wav
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

    /** WAV 很大(一首 ~15MB);给缓存设一个上限,按最后访问时间删旧的。 */
    private fun trimPcmCache(keep: File) {
        val dir = File(root, "pcm")
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_PCM_CACHE_BYTES) return
        for (f in files) {
            if (total <= MAX_PCM_CACHE_BYTES) break
            if (f.absolutePath == keep.absolutePath) continue
            val size = f.length()
            if (f.delete()) total -= size
        }
    }

    private companion object {
        const val MAX_PCM_CACHE_BYTES = 300L * 1024L * 1024L
    }
}
