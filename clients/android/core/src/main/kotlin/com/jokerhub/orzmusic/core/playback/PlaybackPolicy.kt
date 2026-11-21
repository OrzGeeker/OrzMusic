package com.jokerhub.orzmusic.core.playback

import com.jokerhub.orzmusic.core.api.OrzMusicTransport
import com.jokerhub.orzmusic.core.model.PlayStrategy
import com.jokerhub.orzmusic.core.model.Song
import com.jokerhub.orzmusic.core.model.strategy

/**
 * 播放路由 —— 与 Web 端 `OrzAudioPlayer.play(song)` 内部的策略选择同一职责。
 *
 * 重要：策略选择**不属于 OrzAudioCore**。SDK 只负责解码；客户端根据
 * `playStrategy` + 本机实际可用的 decoder 决定走哪条路。
 *
 * 语义是 **native-first**：服务端的 `serverDecode` 只是「本机解不了时的兜底」，
 * 不是硬命令 —— 本机编进了对应 decoder 就直接下原始文件本地解，把服务端 WAV
 * 转换降到真正必需的少数格式（压缩 WAV、未装解码器的旧客户端）。
 */
sealed interface PlaybackRoute {
    /** directFile，或本机解不了、需要服务端转码的 serverDecode。 */
    data class Remote(val url: String) : PlaybackRoute

    /** 本机有对应 decoder：下载 raw → 平台解码器 → 本地 PCM。 */
    data class Native(val rawUrl: String) : PlaybackRoute

    /** wasmDecode 且本机没编：回落到网页播放器。 */
    data class Unsupported(val format: String) : PlaybackRoute
}

object PlaybackPolicy {

    fun route(
        song: Song,
        transport: OrzMusicTransport,
        manifest: DecoderManifestSource = DecoderManifest
    ): PlaybackRoute {
        val local = manifest.supports(song.fileFormat)
        return when (song.strategy) {
            // 容器音频交给平台播放器；本地解码没有意义。
            PlayStrategy.DirectFile ->
                PlaybackRoute.Remote(transport.absolute(song.streamUrl))

            // 服务端愿意转码，但本机能解就自己解。
            PlayStrategy.ServerDecode ->
                if (local) {
                    PlaybackRoute.Native(transport.absolute(song.rawUrl.ifBlank { song.streamUrl }))
                } else {
                    PlaybackRoute.Remote(transport.absolute(song.streamUrl))
                }

            // 服务端只给原始文件：必须本机解，否则只能引导到网页播放器。
            PlayStrategy.WasmDecode ->
                if (local) {
                    PlaybackRoute.Native(transport.absolute(song.rawUrl.ifBlank { song.streamUrl }))
                } else {
                    PlaybackRoute.Unsupported(song.fileFormat)
                }
        }
    }
}
