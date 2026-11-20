package com.jokerhub.orzmusic.android.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.jokerhub.orzmusic.core.playback.AudioSource
import com.jokerhub.orzmusic.core.playback.OrzAudioPlayer
import java.io.File

/**
 * [OrzAudioPlayer] 的 Android 实现。
 *
 * 真正的 ExoPlayer 在 [PlaybackService] 里；这里只是 MediaController 代理。
 * 这样按 HOME / 锁屏后 service 继续播放，通知栏/锁屏/耳机线控也能控制。
 *
 * 首次连接是异步的：连接前收到的命令先排队，连接成功后按顺序补发。
 */
class PlaybackController(context: Context) : OrzAudioPlayer {

    private val appContext = context.applicationContext
    private var controller: MediaController? = null
    private var released = false
    private var volumeValue = 1f
    private val pending = mutableListOf<(MediaController) -> Unit>()
    private val future = MediaController.Builder(
        appContext,
        SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
    ).buildAsync()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            onPlayingChanged?.invoke(isPlaying)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) onEnded?.invoke()
        }

        override fun onPlayerError(error: PlaybackException) {
            onError?.invoke(error.errorCodeName + ": " + (error.message ?: "播放失败"))
        }
    }

    init {
        future.addListener(
            {
                if (released) {
                    runCatching { MediaController.releaseFuture(future) }
                    return@addListener
                }
                val connected = runCatching { future.get() }.getOrNull()
                if (connected == null) {
                    onError?.invoke("无法连接播放服务")
                    return@addListener
                }
                controller = connected
                connected.addListener(listener)
                connected.volume = volumeValue
                // 连接前 service 可能已经在后台播放；同步一次真实状态给 UI。
                onPlayingChanged?.invoke(connected.isPlaying)
                val actions = pending.toList()
                pending.clear()
                actions.forEach { it(connected) }
            },
            ContextCompat.getMainExecutor(appContext)
        )
    }

    private fun withController(action: (MediaController) -> Unit) {
        val current = controller
        if (current != null) action(current) else pending.add(action)
    }

    override var onPlayingChanged: ((Boolean) -> Unit)? = null
    override var onEnded: (() -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    override val isPlaying: Boolean get() = controller?.isPlaying == true
    override val positionMs: Long get() = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L
    override val durationMs: Long get() = controller?.duration
        ?.takeIf { it != androidx.media3.common.C.TIME_UNSET && it > 0 }
        ?: 0L

    override var volume: Float
        get() = controller?.volume ?: volumeValue
        set(value) {
            volumeValue = value.coerceIn(0f, 1f)
            controller?.volume = volumeValue
        }

    override fun play(source: AudioSource) {
        val item = source.toMediaItem()
        withController { c ->
            c.stop()
            c.clearMediaItems()
            c.setMediaItem(item)
            c.prepare()
            c.playWhenReady = true
        }
    }

    override fun toggle() {
        withController { if (it.isPlaying) it.pause() else it.play() }
    }

    override fun pause() {
        withController { it.pause() }
    }

    override fun stop() {
        withController {
            it.stop()
            it.clearMediaItems()
        }
    }

    override fun seekTo(positionMs: Long) {
        withController { it.seekTo(positionMs.coerceAtLeast(0L)) }
    }

    override fun release() {
        released = true
        pending.clear()
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        runCatching { MediaController.releaseFuture(future) }
    }

    private fun AudioSource.toMediaItem(): MediaItem {
        val builder = MediaItem.Builder()
        val metadata = MediaMetadata.Builder()
        when (this) {
            is AudioSource.Remote -> {
                builder.setUri(url)
                builder.setMediaId(id)
                if (title.isNotBlank()) metadata.setTitle(title)
                if (artist.isNotBlank()) metadata.setArtist(artist)
                if (format.isNotBlank()) metadata.setGenre(format.uppercase())
                if (durationMs > 0) metadata.setDurationMs(durationMs)
            }
            is AudioSource.Local -> {
                builder.setUri(Uri.fromFile(File(path)).toString())
                builder.setMediaId(id)
                if (title.isNotBlank()) metadata.setTitle(title)
                if (artist.isNotBlank()) metadata.setArtist(artist)
                if (format.isNotBlank()) metadata.setGenre(format.uppercase())
                if (durationMs > 0) metadata.setDurationMs(durationMs)
            }
        }
        return builder.setMediaMetadata(metadata.build()).build()
    }
}
