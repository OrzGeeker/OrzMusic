package com.jokerhub.orzmusic.core.playback

import com.jokerhub.orzmusic.core.api.OrzMusicTransport
import com.jokerhub.orzmusic.core.model.PlayStrategy
import com.jokerhub.orzmusic.core.model.Song
import com.jokerhub.orzmusic.core.model.strategy

/**
 * 播放路由 —— 与 Web 端 `OrzAudioPlayer.play(song)` 内部的策略选择同一职责。
 *
 * 重要:策略选择**不属于 OrzAudioCore**。SDK 只负责解码;
 * 客户端根据 `playStrategy` + 自己编进来的 decoder manifest 决定走哪条路。
 */
sealed interface PlaybackRoute {
    /** directFile / serverDecode:直接交给平台播放器(Media3 / <audio> / AVPlayer)。 */
    data class Remote(val url: String) : PlaybackRoute

    /** wasmDecode 且当前 profile 编进来了:下载 raw → 平台解码器 → 本地 PCM/WAV。 */
    data class Native(val rawUrl: String) : PlaybackRoute

    /** wasmDecode 但当前 profile 没编:回落到网页播放器。 */
    data class Unsupported(val format: String) : PlaybackRoute
}

object PlaybackPolicy {

    fun route(
        song: Song,
        transport: OrzMusicTransport,
        manifest: DecoderManifest = DecoderManifest
    ): PlaybackRoute = when (song.strategy) {
        PlayStrategy.DirectFile, PlayStrategy.ServerDecode ->
            PlaybackRoute.Remote(transport.absolute(song.streamUrl))

        PlayStrategy.WasmDecode ->
            if (manifest.supports(song.fileFormat)) {
                PlaybackRoute.Native(transport.absolute(song.rawUrl.ifBlank { song.streamUrl }))
            } else {
                PlaybackRoute.Unsupported(song.fileFormat)
            }
    }
}
