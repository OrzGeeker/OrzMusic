package com.jokerhub.orzmusic.android.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jokerhub.orzmusic.core.playback.AudioSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 端到端回归：模块音乐必须走本地解码（PcmStream），不能落到 ExoPlayer。
 *
 * 0.8.1 真机故障：`MediaItem` 的格式 id 只写在 `tag` 上，而 Media3 的
 * `LocalConfiguration.toBundle()` 不序列化 tag —— 过了 `MediaController` 这一跳
 * 格式就没了，`OrzPlaybackPlayer` 把 `.mod` 文件丢给 ExoPlayer，报
 * `UnrecognizedInputFormatException: Source error`。
 *
 * 这个用例走的是**和 UI 完全相同的链路**：
 * `PlaybackController.play(AudioSource.Local)` → MediaSession → `OrzPlaybackPlayer`
 * → `PcmStream` + `AudioTrack`。在 0.8.1 上它会拿到 onError 且永远不 playing。
 */
@RunWith(AndroidJUnit4::class)
class PlaybackSessionNativeRoutingTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    /** MediaController 只能在创建它的 Looper 线程上用，所以所有交互都回主线程。 */
    private lateinit var player: PlaybackController
    private val errors = java.util.Collections.synchronizedList(mutableListOf<String>())

    private data class Snapshot(val playing: Boolean, val positionMs: Long, val durationMs: Long)

    private fun snapshot(): Snapshot {
        var snap = Snapshot(false, 0L, 0L)
        instrumentation.runOnMainSync {
            snap = Snapshot(player.isPlaying, player.positionMs, player.durationMs)
        }
        return snap
    }

    @Test
    fun modFilePlaysThroughNativePcmPath() {
        val file = File(context.cacheDir, "raw/regression-mod.mod")
        file.parentFile?.mkdirs()
        file.writeBytes(silentButLongEnoughMod())

        instrumentation.runOnMainSync {
            player = PlaybackController(context)
            player.onError = { errors += it }
            // 与 AppViewModel.startNative() 传的参数同构（format 来自 song.fileFormat）。
            player.play(
                AudioSource.Local(
                    path = file.absolutePath,
                    id = "REGRESSION-MOD",
                    title = "regression",
                    artist = "device-test",
                    format = "mod",
                    durationMs = 3_000
                )
            )
        }

        try {
            val started = awaitUntil(20_000) { snapshot().playing || errors.isNotEmpty() }

            assertEquals("播放器报了错：$errors", emptyList<String>(), errors.toList())
            assertTrue("20s 内没有进入 playing（ExoPlayer 分支会先报 Source error）", started)
            assertTrue("isPlaying 应为 true", snapshot().playing)

            // 位置真的在走，说明 PcmStream 在持续喂 PCM（不是卡在 buffering）。
            val advanced = awaitUntil(8_000) { snapshot().positionMs > 300 }
            assertTrue("播放位置没有前进（position=${snapshot().positionMs}）", advanced)
        } finally {
            runCatching { instrumentation.runOnMainSync { player.release() } }
            file.delete()
        }
    }

    private fun awaitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(100)
        }
        return cond()
    }

    /**
     * 最小可循环的 ProTracker MOD：1 个 2 字节循环采样 + 第 0 行一个音符，
     * 这样解码器会持续输出（不是 0 长度直接结束）。
     */
    private fun silentButLongEnoughMod(): ByteArray {
        val buf = ByteBuffer.allocate(1084 + 1024 + 2).order(ByteOrder.BIG_ENDIAN)
        buf.position(20)                 // 20 字节标题留空
        buf.position(20 + 22)            // 采样 1 的头(每份 30 字节)
        buf.putShort(1)                  // length = 1 word
        buf.put(0)                       // finetune
        buf.put(64)                      // volume
        buf.putShort(0)                  // repeat start
        buf.putShort(1)                  // repeat length → 2 字节循环
        buf.position(950)
        buf.put(1)                       // song length
        buf.put(127)                     // restart position
        buf.put(0)                       // pattern 表
        buf.position(1080)
        buf.put("M.K.".toByteArray(Charsets.US_ASCII))
        // pattern 0 第 0 行第 1 声道：period 428(C-3) + 采样 1
        buf.position(1084)
        buf.put(0x01)                    // (sample & 0xF0) | period hi
        buf.put(0xAC.toByte())           // period lo
        buf.put(0x10)                    // (sample & 0x0F) << 4 | effect
        buf.put(0x00)                    // effect param
        buf.position(1084 + 1024)
        buf.put(0x00); buf.put(0x00)     // 采样数据
        return buf.array()
    }
}
