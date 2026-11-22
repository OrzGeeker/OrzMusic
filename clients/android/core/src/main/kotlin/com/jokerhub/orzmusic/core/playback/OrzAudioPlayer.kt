package com.jokerhub.orzmusic.core.playback

/**
 * 平台播放器抽象。
 *
 * Android:Media3/ExoPlayer(WAV / mp3 / ogg / 服务端 WAV)
 * Apple:AVAudioEngine / AVPlayer
 * Web:`<audio>` / AudioWorklet
 *
 * 播放器**不拥有队列,也不决定策略** —— 那两层在 PlaybackQueue / PlaybackPolicy。
 */
sealed interface AudioSource {
    /** 元数据用于 MediaSession 通知/锁屏；客户端可以不填。 */
    data class Remote(
        val url: String,
        val id: String = "",
        val title: String = "",
        val artist: String = "",
        val format: String = "",
        val durationMs: Long = 0L
    ) : AudioSource

    data class Local(
        val path: String,
        val id: String = "",
        val title: String = "",
        val artist: String = "",
        val format: String = "",
        val durationMs: Long = 0L
    ) : AudioSource
}

interface OrzAudioPlayer {
    var onPlayingChanged: ((Boolean) -> Unit)?
    var onEnded: (() -> Unit)?
    var onError: ((String) -> Unit)?

    val isPlaying: Boolean
    val positionMs: Long
    val durationMs: Long

    /**
     * 播放器**当前真正在播的那一项**的 id（没有就是 null）。
     *
     * 界面据此判断「现在报上来的位置属于哪首歌」：换歌瞬间 `positionMs` 可能还是
     * 上一首的，直接拿去画就会把旧进度带到新歌上。
     */
    val currentItemId: String? get() = null

    /** 0f…1f;平台播放器自己保存,客户端 UI 只读写这个值。 */
    var volume: Float

    fun play(source: AudioSource)
    fun toggle()
    fun pause()
    fun stop()
    fun seekTo(positionMs: Long)
    fun release()
}
