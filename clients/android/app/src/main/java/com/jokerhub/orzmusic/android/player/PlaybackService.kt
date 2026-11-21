package com.jokerhub.orzmusic.android.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.util.UnstableApi
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
 * `MediaSession` 现在绑在 [OrzPlaybackPlayer] 上（而不是裸 ExoPlayer）：
 * 模块/芯片音乐走 `PcmStream` + `AudioTrack` 流式解码，容器音频走内部 ExoPlayer，
 * 对外仍然是同一个 `Player`，所以通知栏 / 锁屏 / 耳机线控行为不变。
 *
 *   · 按 HOME / 锁屏后播放继续（前台服务 + MediaSession）
 *   · 通知栏 / 锁屏 / 耳机线控由 Media3 默认通知提供
 *   · Activity 销毁不会停止播放；重新进入时重新连接 controller
 *
 * 声场 PCM 采样：ExoPlayer 那条腿挂在 AudioSink 的 processor 上；
 * 本地解码那条腿在 [NativePcmPlayback] 的写线程里调用同一个分析器。
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: OrzPlaybackPlayer? = null

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(OrzMediaNotificationProvider(this))

        val visualizer = VisualizerAudioProcessor()
        val created = OrzPlaybackPlayer(this, visualizer)
        player = created

        created.addListener(object : androidx.media3.common.Player.Listener {
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

        mediaSession = MediaSession.Builder(this, created)
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
        val current = mediaSession?.player
        // 从最近任务划掉时：还在播就继续（用户可能锁屏/戴耳机），否则退出。
        if (current == null || !current.playWhenReady || current.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        VisualizerBus.reset()
        super.onDestroy()
    }
}

object PlaybackActions {
    const val PREVIOUS = "com.jokerhub.orzmusic.android.PREVIOUS"
    const val NEXT = "com.jokerhub.orzmusic.android.NEXT"
}
