package com.jokerhub.orzmusic.android.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.legacy.PlaybackStateCompat
import com.google.common.collect.ImmutableList
import com.jokerhub.orzmusic.android.R

/**
 * 自定义媒体通知：在 Media3 默认的播放/暂停/进度之外，
 * 额外提供「上一首 / 下一首」，通过 SessionCallback 的 custom command
 * 转给 ViewModel 的队列（Pico / TV 的遥控也能直接操作）。
 */
class OrzMediaNotificationProvider(private val context: Context) : MediaNotification.Provider {

    override fun createNotification(
        mediaSession: MediaSession,
        mediaButtonPreferences: ImmutableList<androidx.media3.session.CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback
    ): MediaNotification {
        ensureChannel()
        val player = mediaSession.player
        val metadata = player.mediaMetadata
        val title = metadata.title?.toString()?.takeIf { it.isNotBlank() } ?: "OrzMusic"
        val subtitle = metadata.artist?.toString()?.takeIf { it.isNotBlank() }
            ?: metadata.genre?.toString()?.takeIf { it.isNotBlank() }
            ?: "芯片音乐播放器"

        val prev = actionFactory.createCustomAction(
            mediaSession,
            IconCompat.createWithResource(context, android.R.drawable.ic_media_previous),
            "上一首",
            PlaybackActions.PREVIOUS,
            Bundle.EMPTY
        )
        val playPause = actionFactory.createMediaAction(
            mediaSession,
            IconCompat.createWithResource(
                context,
                if (player.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            ),
            if (player.isPlaying) "暂停" else "播放",
            PlaybackStateCompat.ACTION_PLAY_PAUSE.toInt()
        )
        val next = actionFactory.createCustomAction(
            mediaSession,
            IconCompat.createWithResource(context, android.R.drawable.ic_media_next),
            "下一首",
            PlaybackActions.NEXT,
            Bundle.EMPTY
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_orz)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(mediaSession.sessionActivity)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(player.playWhenReady)
            .addAction(prev)
            .addAction(playPause)
            .addAction(next)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionCompatToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()

        return MediaNotification(NOTIFICATION_ID, notification)
    }

    override fun handleCustomCommand(session: MediaSession, action: String, extras: Bundle): Boolean {
        when (action) {
            PlaybackActions.PREVIOUS -> PlaybackCommandBridge.previous()
            PlaybackActions.NEXT -> PlaybackCommandBridge.next()
            else -> return false
        }
        return true
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "正在播放",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "OrzMusic 后台播放控制"
                    setShowBadge(false)
                }
            )
        }
    }

    private companion object {
        const val CHANNEL_ID = "default_channel_id"
        const val NOTIFICATION_ID = 1001
    }
}
