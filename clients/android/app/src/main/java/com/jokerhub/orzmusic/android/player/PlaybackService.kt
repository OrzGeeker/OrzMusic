package com.jokerhub.orzmusic.android.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.jokerhub.orzmusic.android.MainActivity

/**
 * 后台播放宿主。
 *
 * ExoPlayer 归 service 所有，Activity / ViewModel 通过 MediaController 控制：
 *   · 按 HOME / 锁屏后播放继续（前台服务 + MediaSession）
 *   · 通知栏 / 锁屏 / 耳机线控由 Media3 默认通知提供
 *   · Activity 销毁不会停止播放；重新进入时重新连接 controller
 *
 * 声场 PCM 采样也在这里的 AudioSink 管线里完成（VisualizerAudioProcessor）。
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(OrzMediaNotificationProvider(this))

        // 自定义 AudioProcessor 挂在 AudioSink 上；放在默认 processor 之前，
        // 拿到的是解码后的 PCM，而不是 AudioTrack 已经写走的数据。
        val visualizer = VisualizerAudioProcessor()
        val player = ExoPlayer.Builder(this)
            .setRenderersFactory(OrzRenderersFactory(this, visualizer))
            .build()
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // 暂停/播完时把声场归零，避免停在最后一帧。
                if (!isPlaying) VisualizerBus.reset()
            }
        })

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val previousCommand = SessionCommand(PlaybackActions.PREVIOUS, Bundle.EMPTY)
        val nextCommand = SessionCommand(PlaybackActions.NEXT, Bundle.EMPTY)
        val customLayout = listOf(
            CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                .setSessionCommand(previousCommand)
                .setDisplayName("上一首")
                .setSlots(CommandButton.SLOT_BACK)
                .build(),
            CommandButton.Builder(CommandButton.ICON_NEXT)
                .setSessionCommand(nextCommand)
                .setDisplayName("下一首")
                .setSlots(CommandButton.SLOT_FORWARD)
                .build()
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setCustomLayout(customLayout)
            .setCallback(object : MediaSession.Callback {
                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) {
                        PlaybackActions.PREVIOUS -> PlaybackCommandBridge.previous()
                        PlaybackActions.NEXT -> PlaybackCommandBridge.next()
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        // 从最近任务划掉时：还在播就继续（用户可能锁屏/戴耳机），否则退出。
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        VisualizerBus.reset()
        super.onDestroy()
    }
}


/**
 * ExoPlayer 1.5 没有公开的 `Builder.setAudioSink`，通过 RenderersFactory
 * 覆盖 `buildAudioSink` 注入 PCM 采样 processor。
 */
object PlaybackActions {
    const val PREVIOUS = "com.jokerhub.orzmusic.android.PREVIOUS"
    const val NEXT = "com.jokerhub.orzmusic.android.NEXT"
}

private class OrzRenderersFactory(
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
