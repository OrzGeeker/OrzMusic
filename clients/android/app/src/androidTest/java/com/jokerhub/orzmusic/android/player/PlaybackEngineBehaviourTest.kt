package com.jokerhub.orzmusic.android.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jokerhub.orzmusic.core.playback.AudioSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 真机量化 4 件事（不是「应该行」，是把数字打出来）：
 *
 * 1. 本地解码路径的 seek 生效吗？
 * 2. 声场（`VisualizerBus`）在本地解码路径上有没有数据？量级多少？
 * 3. 首播从 `play()` 到出声多久？
 * 4. 切歌之后解码/写线程有没有残留？
 */
@RunWith(AndroidJUnit4::class)
class PlaybackEngineBehaviourTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val errors = java.util.Collections.synchronizedList(mutableListOf<String>())

    private lateinit var player: PlaybackController
    private val tempFiles = mutableListOf<File>()

    private data class Snapshot(val playing: Boolean, val positionMs: Long, val durationMs: Long)

    private fun snapshot(): Snapshot {
        var snap = Snapshot(false, 0L, 0L)
        instrumentation.runOnMainSync {
            snap = Snapshot(player.isPlaying, player.positionMs, player.durationMs)
        }
        return snap
    }

    private fun open() {
        instrumentation.runOnMainSync {
            player = PlaybackController(context)
            player.onError = { errors += it }
        }
    }

    @After
    fun tearDown() {
        if (::player.isInitialized) runCatching { instrumentation.runOnMainSync { player.release() } }
        tempFiles.forEach { it.delete() }
    }

    private fun playFile(format: String, bytes: ByteArray, id: String, durationMs: Long = 0L) {
        val file = File(context.cacheDir, "raw/$id.$format")
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        tempFiles += file
        instrumentation.runOnMainSync {
            player.play(
                AudioSource.Local(
                    path = file.absolutePath,
                    id = id,
                    title = id,
                    artist = "device-test",
                    format = format,
                    durationMs = durationMs
                )
            )
        }
    }

    @Test
    fun seekJumpsToRequestedPositionAndKeepsPlaying() {
        open()
        playFile("mod", mod(patterns = 8), "seek-mod")
        assertTrue("没有开始播放：${errors.toList()}", awaitUntil(20_000) { snapshot().playing })
        Thread.sleep(1_500)

        val before = snapshot().positionMs
        val target = 30_000L
        instrumentation.runOnMainSync { player.seekTo(target) }
        Thread.sleep(800)
        val after = snapshot().positionMs
        println("SEEK before=$before target=$target after=$after duration=${snapshot().durationMs}")

        assertEquals("seek 报错：${errors.toList()}", emptyList<String>(), errors.toList())
        assertTrue(
            "seek 后位置没跳到 $target（实际 $after，seek 前 $before）",
            after >= target - 1_000 && after <= target + 3_000
        )
        assertTrue("seek 之后就不播了", snapshot().playing)

        // seek 之后还能继续往前走
        val p1 = snapshot().positionMs
        assertTrue("seek 之后位置不前进（$p1）", awaitUntil(5_000) { snapshot().positionMs > p1 + 300 })
    }

    /**
     * 模拟在进度条上拖动：短时间内连续发很多次 seek。
     *
     * 真实故障现象：拖动时进度条跟手，松手后**立刻弹回原位**、音乐也从老位置继续 ——
     * 说明播放器拒绝了 seek（`PcmStream.seekTo` 超时 → 位置没更新）。
     */
    @Test
    fun rapidDragSeeksLandOnLastTarget() {
        open()
        playFile("mod", mod(patterns = 8), "drag-mod")
        assertTrue("没有开始播放：${errors.toList()}", awaitUntil(20_000) { snapshot().playing })
        Thread.sleep(1_000)

        val targets = (0 until 30).map { 5_000L + it * 1_000L }
        for (t in targets) {
            instrumentation.runOnMainSync { player.seekTo(t) }
            Thread.sleep(10)
        }
        Thread.sleep(1_500)

        val final = snapshot().positionMs
        println("DRAG lastTarget=${targets.last()} final=$final")
        assertEquals("拖动后报错：${errors.toList()}", emptyList<String>(), errors.toList())
        assertTrue(
            "松手后没落在最后的目标 ${targets.last()}（实际 $final，等于回到原位）",
            final >= targets.last() - 2_000 && final <= targets.last() + 4_000
        )
    }

    /**
     * 解码器不支持 `ORZ_CAP_SEEK` 的格式（sc68 / sid / gme 系）走「重解码快进」。
     *
     * 真机上 sc68 的 `orz_decoder_seek` 返回不支持 → 进度条弹回原位、音乐不跳。
     * 这里用 `forceSkipSeek` 强制走同一条兜底路径（MOD 本身支持 seek，正好能验证
     * 兜底路径的正确性），并断言跳转后位置落在目标上、继续往前走。
     */
    @Test
    fun seekFallsBackToRenderSkipWhenDecoderCannotSeek() {
        val file = File(context.cacheDir, "raw/skipseek-mod.mod")
        file.parentFile?.mkdirs()
        file.writeBytes(mod(patterns = 8))
        tempFiles += file

        val states = java.util.Collections.synchronizedList(mutableListOf<NativePcmPlayback.State>())
        val playback = NativePcmPlayback(
            file = file,
            format = "mod",
            onSamples = { _, _, _ -> },
            onState = { state, _ -> states += state },
            forceSkipSeek = true
        )
        try {
            playback.prepare()
            assertTrue("准备超时：$states", awaitUntil(20_000) { states.contains(NativePcmPlayback.State.READY) })
            playback.play()
            Thread.sleep(800)

            val target = 30_000L
            val applied = playback.seekTo(target)
            Thread.sleep(700)
            println("SKIPSEEK applied=$applied usedSkip=${playback.lastSeekUsedSkip} pos=${playback.positionMs}")

            assertTrue("重解码快进没生效（applied=$applied）", applied)
            assertTrue("没走重解码快进这条路", playback.lastSeekUsedSkip)
            assertTrue(
                "跳转后位置不在目标附近（pos=${playback.positionMs}）",
                playback.positionMs >= target - 1_000 && playback.positionMs <= target + 3_000
            )
        } finally {
            playback.release()
        }
    }

    @Test
    fun nativePlaybackFeedsVisualizerBus() {
        VisualizerBus.reset()
        open()
        playFile("mod", mod(patterns = 4), "viz-mod")
        assertTrue("没有开始播放：${errors.toList()}", awaitUntil(20_000) { snapshot().playing })

        var peak = 0f
        var nonZeroFrames = 0
        val deadline = System.currentTimeMillis() + 6_000
        while (System.currentTimeMillis() < deadline) {
            val max = VisualizerBus.levels.value.maxOrNull() ?: 0f
            if (max > 0.02f) nonZeroFrames++
            if (max > peak) peak = max
            Thread.sleep(50)
        }
        println("VIS peak=$peak nonZeroSamples=$nonZeroFrames/120")

        // 不是「有没有变化」而是「有没有到视觉上看得见的量级」
        assertTrue("声场全 0：本地解码路径没有往 VisualizerBus 推数据", peak > 0.02f)
    }

    @Test
    fun firstPlayLatencyIsMeasured() {
        open()
        // 先连上 MediaSession（首帧抓的是「解码 + AudioTrack 初始化」，不含连接开销）
        playFile("mod", mod(patterns = 2), "latency-warm", durationMs = 1_000)
        assertTrue("预热失败：${errors.toList()}", awaitUntil(20_000) { snapshot().playing })
        Thread.sleep(1_000)

        val t0 = System.nanoTime()
        playFile("mod", mod(patterns = 2), "latency-mod")
        val started = awaitUntil(20_000) { snapshot().playing }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        println("LATENCY play→playing=${elapsedMs}ms started=$started")
        assertTrue("没起来：${errors.toList()}", started)
        assertTrue("首播耗时 ${elapsedMs}ms 超过 2s", elapsedMs < 2_000)
    }

    @Test
    fun switchingTracksLeavesNoDecoderThreads() {
        open()
        playFile("mod", mod(patterns = 2), "thread-0")
        assertTrue("没开始播：${errors.toList()}", awaitUntil(20_000) { snapshot().playing })

        for (i in 1..3) {
            Thread.sleep(500)
            playFile("mod", mod(patterns = 2), "thread-$i")
            Thread.sleep(1_200)
        }
        // 停掉当前曲目，等一点时间让 release 生效
        instrumentation.runOnMainSync { player.stop() }
        Thread.sleep(2_000)

        val leftover = Thread.getAllStackTraces().keys
            .filter { it.isAlive && (it.name.startsWith("orz-pcm") || it.name.startsWith("orz-native")) }
            .map { it.name }
        println("THREADS leftover=$leftover")
        assertTrue("切歌后残留解码线程：$leftover", leftover.isEmpty())
        assertEquals("播放出错：${errors.toList()}", emptyList<String>(), errors.toList())
    }

    private fun awaitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(50)
        }
        return cond()
    }

    /**
     * 生成一个能真出声的 ProTracker MOD：`patterns` 个 pattern，每 16 行一个 C-3，
     * 采样 1 是一段 128 字节的锯齿波整段循环 —— 这样 FFT 有真实的谐波能量，
     * seek 也有足够的时长（每个 pattern ≈ 7.7s）。
     */
    private fun mod(patterns: Int): ByteArray {
        val sampleBytes = 128
        val total = 1084 + patterns * 1024 + sampleBytes
        val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        buf.position(20)                       // 20 字节标题留空
        buf.position(20 + 22)                  // 采样 1 头（30 字节，偏移 22 是 length）
        buf.putShort((sampleBytes / 2).toShort())   // length（word）
        buf.put(0)                             // finetune
        buf.put(64)                            // volume
        buf.putShort(0)                        // repeat start
        buf.putShort((sampleBytes / 2).toShort())   // repeat length = 全长循环
        buf.position(950)
        buf.put(patterns.toByte())             // song length
        buf.put(127)                           // restart
        for (i in 0 until patterns) buf.put(i.toByte())   // 曲序
        buf.position(1080)
        buf.put("M.K.".toByteArray(Charsets.US_ASCII))
        for (p in 0 until patterns) {
            for (row in 0 until 64 step 16) {
                buf.position(1084 + p * 1024 + row * 16)
                buf.put(0x01)                  // sample 1 高 4 位 | period 高 4 位
                buf.put(0xAC.toByte())         // period 428 = C-3
                buf.put(0x10)                  // sample 1 低 4 位 | effect 0
                buf.put(0x00)
            }
        }
        buf.position(1084 + patterns * 1024)
        for (i in 0 until sampleBytes) buf.put((i - 64).toByte())   // 锯齿波
        return buf.array()
    }
}
