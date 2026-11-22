package com.jokerhub.orzmusic.android.player

import android.content.Context
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.jokerhub.orzmusic.audiokit.OrzAudioCore
import java.io.File
import java.util.concurrent.TimeUnit
import android.os.Handler

/**
 * 播放服务里的那个 `Player`：一个 `SimpleBasePlayer`，内部两条腿。
 *
 * ```
 * 播放项                引擎
 * ───────────────────── ────────────────────────────────────────────────
 * 模块/芯片音乐格式      NativePcmPlayback（PcmStream + AudioTrack）
 * 其它（mp3/ogg/wav…）   ExoPlayer
 * ```
 *
 * 为什么要包一层而不是直接用 ExoPlayer：ExoPlayer 只能播容器/文件，
 * 吃不了 OrzAudioCore 实时渲染的 float PCM。而通知栏、锁屏、耳机线控
 * 都挂在 `MediaSession` 上，所以必须让 MediaSession 控制的那个 `Player`
 * 自己就是 PCM 播放器 —— 这正是 `SimpleBasePlayer` 的用途。
 *
 * 判定“是否走本地解码”只看 `MediaItem` 的 tag（由 `PlaybackController`
 * 写入的格式 id）+ `OrzAudioCore.supports()`（运行时探测）。
 */
@UnstableApi
class OrzPlaybackPlayer(
    private val context: Context,
    private val visualizer: VisualizerAudioProcessor
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val exo: ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(VisualizerRenderersFactory(context, visualizer))
        .build()

    private val handler = Handler(Looper.getMainLooper())
    private var cancelPolling: Runnable? = null

    // ── 当前播放项 ──
    private var item: MediaItem? = null
    private var isNative = false
    private var native: NativePcmPlayback? = null

    // ── 状态 ──
    private var playWhenReady = false
    private var nativeState = Player.STATE_IDLE
    private var nativePositionMs = 0L
    private var nativeDurationMs = 0L
    private var nativeError: PlaybackException? = null
    private var volume = 1f

    private val exoListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (!isNative) invalidateState()
        }
    }

    init {
        exo.addListener(exoListener)
    }

    // ── SimpleBasePlayer ────────────────────────────────────────

    override fun getState(): State {
        val current = item
        val builder = State.Builder()
            .setAvailableCommands(AVAILABLE_COMMANDS)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setVolume(volume)

        if (current == null) {
            return builder.setPlaybackState(Player.STATE_IDLE).build()
        }

        val data = MediaItemData.Builder(current.mediaId)
            .setMediaItem(current)
            .setMediaMetadata(current.mediaMetadata)
            .setDurationUs(durationUs())
            .setIsSeekable(true)
            .build()

        return builder
            .setPlaylist(listOf(data))
            .setPlaybackState(playbackState())
            .setContentPositionMs(positionMs())
            .setPlayerError(if (isNative) nativeError else exo.playerError)
            .build()
    }

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<*> {
        val next = mediaItems.getOrNull(startIndex.coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0)))
        switchTo(next, startPositionMs)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> =
        Futures.immediateVoidFuture() // 播放器只维护单曲

    override fun handleMoveMediaItems(from: Int, to: Int, newIndex: Int): ListenableFuture<*> =
        Futures.immediateVoidFuture()

    override fun handleReplaceMediaItems(from: Int, to: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        mediaItems.firstOrNull()?.let { switchTo(it, 0L) }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(from: Int, to: Int): ListenableFuture<*> {
        releaseEngines()
        item = null
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (isNative) {
            native?.prepare()
        } else {
            exo.prepare()
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        if (isNative) {
            if (playWhenReady) native?.play() else native?.pause()
        } else {
            exo.playWhenReady = playWhenReady
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        playWhenReady = false
        if (isNative) {
            releaseNative()
            nativeState = Player.STATE_IDLE
            nativePositionMs = 0L
        } else {
            exo.stop()
        }
        stopPolling()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
        if (seekCommand == Player.COMMAND_SEEK_BACK || seekCommand == Player.COMMAND_SEEK_FORWARD) {
            // 兜底：即使有控制器硬发快退/快进，也不要动进度（0.8.12 起明确不支持）。
            Log.i(SEEK_TAG, "ignored transport seek command=$seekCommand")
            return Futures.immediateVoidFuture()
        }
        if (isNative) {
            // 主线程只置位 + 通知界面（拖动时每个 move 都会进来）；
            // 真正的 pause/flush/解码器 seek 在 NativePcmPlayback 的专用线程上做。
            nativePositionMs = positionMs
            native?.requestSeek(positionMs)
            Log.i(SEEK_TAG, "player.handleSeek native($positionMs) queued")
        } else {
            exo.seekTo(positionMs)
            Log.i(SEEK_TAG, "player.handleSeek exo($positionMs)")
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        this.volume = volume.coerceIn(0f, 1f)
        exo.volume = this.volume
        native?.setVolume(this.volume)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> =
        Futures.immediateVoidFuture() // 只用媒体音量,不接管设备音量

    override fun handleRelease(): ListenableFuture<*> {
        stopPolling()
        runCatching { exo.release() }
        releaseEngines()
        item = null
        return Futures.immediateVoidFuture()
    }

    // ── 引擎切换 ────────────────────────────────────────────────

    private fun switchTo(mediaItem: MediaItem?, startPositionMs: Long) {
        releaseEngines()
        item = mediaItem
        nativeError = null
        nativeState = Player.STATE_IDLE
        nativePositionMs = startPositionMs.coerceAtLeast(0L)
        nativeDurationMs = mediaItem?.mediaMetadata?.durationMs ?: 0L
        if (mediaItem == null) {
            isNative = false
            return
        }

        val format = mediaItemFormat(mediaItem)
        val uri = mediaItem.localConfiguration?.uri
        val filePath = uri?.takeIf { it.scheme == "file" }?.path
        val decodable = format != null && OrzAudioCore.supports(format)

        isNative = decodable && filePath != null
        // 路由必须能在 logcat 里看见：0.8.1 的「模块音乐全被丢给 ExoPlayer」就是在
        // 设备上完全静默，只能靠抓 ExoPlayer 的堆栈反推。
        Log.i(
            PLAYBACK_TAG,
            if (isNative) "native format=$format file=${File(filePath!!).name}"
            else "exo    uri=${uri?.toString()?.take(120)} format=$format decodable=$decodable"
        )
        if (isNative) {
            val playback = NativePcmPlayback(
                file = File(filePath!!),
                format = format!!,
                onSamples = { samples, frames, channels ->
                    visualizer.analyzeInterleaved(samples, frames, channels, sampleRateOf())
                },
                onState = { state, message -> onNativeState(state, message) }
            )
            native = playback
            startPolling()
        } else {
            exo.setMediaItem(mediaItem, startPositionMs)
        }
    }

    private fun sampleRateOf(): Int = native?.sampleRate?.takeIf { it > 0 } ?: 44_100

    private fun onNativeState(state: NativePcmPlayback.State, message: String?) {
        handler.post {
            when (state) {
                NativePcmPlayback.State.BUFFERING -> nativeState = Player.STATE_BUFFERING
                NativePcmPlayback.State.READY -> {
                    nativeState = Player.STATE_READY
                    nativeDurationMs = native?.durationMs ?: nativeDurationMs
                    if (playWhenReady) native?.play()
                }
                NativePcmPlayback.State.ENDED -> {
                    nativeState = Player.STATE_ENDED
                    playWhenReady = false
                }
                NativePcmPlayback.State.ERROR -> {
                    nativeState = Player.STATE_IDLE
                    playWhenReady = false
                    nativeError = PlaybackException(
                        message ?: "解码失败",
                        null,
                        PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                    )
                }
            }
            invalidateState()
        }
    }

    private fun playbackState(): Int {
        if (item == null) return Player.STATE_IDLE
        return if (isNative) nativeState else exo.playbackState
    }

    private fun positionMs(): Long =
        if (isNative) (native?.positionMs ?: nativePositionMs) else exo.currentPosition.coerceAtLeast(0L)

    private fun durationUs(): Long {
        val ms = if (isNative) {
            native?.durationMs ?: nativeDurationMs
        } else {
            exo.duration.takeIf { it != C.TIME_UNSET && it > 0 }
                ?.div(1000L)
                ?: item?.mediaMetadata?.durationMs
                ?: 0L
        }
        return if (ms > 0) TimeUnit.MILLISECONDS.toMicros(ms) else C.TIME_UNSET
    }

    // ── 位置轮询（SimpleBasePlayer 需要被动失效刷新位置）──
    private fun startPolling() {
        cancelPolling?.let { handler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                invalidateState()
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        cancelPolling = runnable
        handler.postDelayed(runnable, POLL_INTERVAL_MS)
    }

    private fun stopPolling() {
        cancelPolling?.let { handler.removeCallbacks(it) }
        cancelPolling = null
    }

    private fun releaseNative() {
        native?.release()
        native = null
        VisualizerBus.reset()
    }

    private fun releaseEngines() {
        stopPolling()
        releaseNative()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 250L

        val AVAILABLE_COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_RELEASE,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                // 不提供 SEEK_BACK / SEEK_FORWARD：Pico 的十字键在媒体会话里会被映射成
                // 「快退/快进」，射线指着进度条时按左右键就会悄悄改进度（真机反馈）。
                // 需要 ±5s 用正在播放页的按钮（走 seekTo）。
                Player.COMMAND_SET_VOLUME,
                Player.COMMAND_GET_VOLUME,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_AUDIO_ATTRIBUTES,
                Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_CHANGE_MEDIA_ITEMS,
                Player.COMMAND_SET_SPEED_AND_PITCH
            )
            .build()
    }
}

/**
 * ExoPlayer 侧的 renderer 工厂：把声场采样 processor 挂进 AudioSink。
 * 本地解码那条腿直接用 [VisualizerAudioProcessor.analyzeInterleaved]，不经过这里。
 */
@UnstableApi
class VisualizerRenderersFactory(
    context: Context,
    private val visualizer: VisualizerAudioProcessor
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .setAudioProcessors(arrayOf(visualizer))
        .build()
}
