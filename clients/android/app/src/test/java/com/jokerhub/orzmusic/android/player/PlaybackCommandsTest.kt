package com.jokerhub.orzmusic.android.player

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 钉住 `OrzPlaybackPlayer.AVAILABLE_COMMANDS` 这张能力表。
 *
 * 0.8.12 真机故障：表里多报了 `COMMAND_SEEK_BACK/FORWARD`，Pico 把十字键映射成
 * 快退/快进后 Media3 直接调 `seekBack()/seekForward()`，位置从 `6016ms` 跳到
 * `24018ms`。现在这两条既不在表里、`handleSeek` 也会兜底忽略；这里守的是第一层。
 *
 * 反过来也要守住"别把该有的能力删了"：播放/暂停、seekTo（拖动进度条、±5s 按钮、
 * 通知栏用的都是它）、切歌、音量、倍速都必须在。
 */
@RunWith(AndroidJUnit4::class)
class PlaybackCommandsTest {

    private val commands: Player.Commands = OrzPlaybackPlayer.AVAILABLE_COMMANDS

    @Test
    fun transportSeekBackAndForwardAreNotAdvertised() {
        assertFalse(
            "COMMAND_SEEK_BACK 又回到能力表里了 —— 真机上十字键会偷偷改播放进度",
            commands.contains(Player.COMMAND_SEEK_BACK)
        )
        assertFalse(
            "COMMAND_SEEK_FORWARD 又回到能力表里了 —— 真机上十字键会偷偷改播放进度",
            commands.contains(Player.COMMAND_SEEK_FORWARD)
        )
    }

    @Test
    fun playbackAndSeekToRemainAvailable() {
        val required = listOf(
            "COMMAND_PLAY_PAUSE" to Player.COMMAND_PLAY_PAUSE,
            "COMMAND_PREPARE" to Player.COMMAND_PREPARE,
            "COMMAND_STOP" to Player.COMMAND_STOP,
            "COMMAND_RELEASE" to Player.COMMAND_RELEASE,
            "COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM" to Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            "COMMAND_SEEK_TO_DEFAULT_POSITION" to Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            "COMMAND_SEEK_TO_MEDIA_ITEM" to Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            "COMMAND_GET_CURRENT_MEDIA_ITEM" to Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            "COMMAND_GET_TIMELINE" to Player.COMMAND_GET_TIMELINE,
            "COMMAND_GET_METADATA" to Player.COMMAND_GET_METADATA,
            "COMMAND_SET_MEDIA_ITEM" to Player.COMMAND_SET_MEDIA_ITEM,
            "COMMAND_CHANGE_MEDIA_ITEMS" to Player.COMMAND_CHANGE_MEDIA_ITEMS,
            "COMMAND_SET_VOLUME" to Player.COMMAND_SET_VOLUME,
            "COMMAND_GET_VOLUME" to Player.COMMAND_GET_VOLUME,
            "COMMAND_SET_SPEED_AND_PITCH" to Player.COMMAND_SET_SPEED_AND_PITCH
        )
        val missing = required.filterNot { (_, value) -> commands.contains(value) }.map { it.first }
        assertTrue("能力表少了下述命令（通知栏/锁屏/进度条会失灵）：$missing", missing.isEmpty())
    }
}
