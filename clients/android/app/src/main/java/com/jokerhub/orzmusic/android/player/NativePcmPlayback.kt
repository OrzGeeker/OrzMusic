package com.jokerhub.orzmusic.android.player

import com.jokerhub.orzmusic.audiokit.OrzAudioCore
import com.jokerhub.orzmusic.audiokit.PcmStream
import java.io.File

/**
 * 「模块 / 芯片音乐」的流式播放引擎：`PcmStream`(解码线程) → [PcmAudioSink](AudioTrack)。
 *
 * 与旧的「整曲转 WAV 再交给 ExoPlayer」相比：
 * - 首播只等 2 秒缓冲，不再等全曲解码；
 * - 不落盘（raw 文件已经缓存过）；解码器支持 `ORZ_CAP_SEEK` 时直接 `orz_decoder_seek`，
 *   不支持的格式（sc68 / sid / gme 系）重开解码器**渲染快进**（实测 90 秒只要 ~22ms）；
 * - 支持 subsong。
 *
 * 生命周期（都在主线程之外调用，内部自己串行化）：
 * ```
 * val pb = NativePcmPlayback(file, format, onState) { sink -> ... }
 * pb.prepare()   // 后台线程读文件 + 建解码器
 * pb.play() / pause() / seekTo(ms) / release()
 * ```
 */
class NativePcmPlayback(
    private val file: File,
    private val format: String,
    /** 拿到解码后的采样,用来喂声场分析；在主线程之外调用。 */
    private val onSamples: (FloatArray, Int, Int) -> Unit,
    /** 状态变化回调(主线程不可保证，调用方自己切线程)。 */
    private val onState: (State, String?) -> Unit,
    /** 测试用：强制走「重解码快进」，即使解码器自己支持 seek。 */
    private val forceSkipSeek: Boolean = false
) {

    enum class State { BUFFERING, READY, ENDED, ERROR }

    @Volatile
    private var stream: PcmStream? = null

    /** 原始文件字节：不支持的格式要靠重开解码器快进。 */
    @Volatile
    private var sourceBytes: ByteArray? = null

    /** 上次切歌/seek 的结果，供日志判断走的是哪条路。 */
    @Volatile
    var lastSeekUsedSkip: Boolean = false
        private set

    @Volatile
    private var sink: PcmAudioSink? = null

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var playing = false

    @Volatile
    private var released = false

    /** 出错信息（ERROR 状态时非空）。 */
    @Volatile
    var errorMessage: String? = null
        private set

    val sampleRate: Int get() = stream?.sampleRate ?: 0
    val channels: Int get() = stream?.channels ?: 0
    val durationMs: Long get() = stream?.durationMs ?: 0L
    val subsongCount: Int get() = stream?.subsongCount ?: 0

    @Volatile
    private var positionBaseMs = 0L

    @Volatile
    private var headBaseFrames = 0L

    /** 已播位置(毫秒)，基于 AudioTrack 播放头，暂停/恢复不会跳。 */
    val positionMs: Long
        get() {
            val track = sink ?: return positionBaseMs
            val frames = (track.playedFrames - headBaseFrames).coerceAtLeast(0L)
            val advance = frames * 1000L / track.sampleRate.coerceAtLeast(1)
            return (positionBaseMs + advance).coerceAtMost(
                durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
            )
        }

    val isPlaying: Boolean get() = playing && !released

    /** 后台准备：读文件 → createDecoder → PcmStream → AudioTrack。 */
    fun prepare() {
        if (threadForPrepare != null) return
        onState(State.BUFFERING, null)
        threadForPrepare = Thread({ doPrepare() }, "orz-native-prepare").apply {
            isDaemon = true
            start()
        }
    }

    @Volatile
    private var threadForPrepare: Thread? = null

    private fun doPrepare() {
        try {
            if (!OrzAudioCore.isAvailable) {
                fail("当前设备没有加载 liborzaudio.so")
                return
            }
            if (!file.isFile || file.length() < 32L) {
                fail("原始文件不存在: ${file.absolutePath}")
                return
            }
            val bytes = file.readBytes()
            sourceBytes = bytes
            val decoder = OrzAudioCore.createDecoder(bytes, format)
            val createdStream = PcmStream(decoder)
            val createdSink = PcmAudioSink(createdStream.sampleRate, createdStream.channels)
            if (released) {
                createdSink.close()
                createdStream.close()
                return
            }
            stream = createdStream
            sink = createdSink
            createdStream.start()
            onState(State.READY, null)
            startWriter()
        } catch (t: Throwable) {
            fail(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun startWriter() {
        worker = Thread({
            var buffer = FloatArray(0)
            var bufferChannels = 0
            try {
                while (!released) {
                    // 每轮重新读字段：seek 可能整条换掉 stream（重解码快进）。
                    val currentStream = stream
                    val currentSink = sink
                    if (currentStream == null || currentSink == null) {
                        Thread.sleep(20)
                        continue
                    }
                    if (!playing) {
                        Thread.sleep(20)
                        continue
                    }
                    val ch = currentStream.channels
                    val chunkFrames = 2048
                    // 缓冲区跟着通道数缓存（seek 换流时通道数一般不变）。
                    if (bufferChannels != ch) {
                        buffer = FloatArray(chunkFrames * ch)
                        bufferChannels = ch
                    }
                    val frames = currentStream.read(buffer, chunkFrames)
                    if (frames == 0) {
                        if (currentStream.isEnded) {
                            playing = false
                            onState(State.ENDED, null)
                            break
                        }
                        continue
                    }
                    onSamples(buffer, frames, ch)
                    currentSink.write(buffer, frames)
                }
            } catch (t: Throwable) {
                if (!released) fail(t.message ?: t.javaClass.simpleName)
            }
        }, "orz-native-writer").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    private fun fail(message: String) {
        errorMessage = message
        playing = false
        onState(State.ERROR, message)
    }

    fun play() {
        if (released) return
        playing = true
        sink?.let {
            it.play()
            // AudioTrack 从 stop/flush 之后需要重新 play()
        }
    }

    fun pause() {
        playing = false
        sink?.pause()
    }

    /**
     * 跳到 [positionMs]。
     *
     * 两条腿：
     * - 解码器支持 `ORZ_CAP_SEEK`（openmpt 系）→ `orz_decoder_seek`，几乎零成本；
     * - 不支持（sc68 / sid / gme 系…）→ 重开解码器把 PCM 渲染丢弃到目标位置。
     *   这些解码器渲得极快（实测 sc68 跳 90 秒 ~22ms），所以照样秒跳。
     *
     * 任意一条失败都返回 `false`，此时位置不动（界面会退回原位置）。
     */
    fun seekTo(positionMs: Long): Boolean {
        val createdStream = stream ?: return false
        val createdSink = sink ?: run {
            positionBaseMs = positionMs.coerceAtLeast(0L)
            return false
        }
        val target = positionMs.coerceIn(0L, durationMs.takeIf { it > 0 } ?: positionMs.coerceAtLeast(0L))

        // flush 只能在 pause 之后；先暂停再清空，避免把还在播的数据一起丢掉。
        val wasPlaying = playing
        playing = false
        createdSink.pause()
        createdSink.flush()

        val ok = if (!forceSkipSeek && createdStream.isSeekable) {
            lastSeekUsedSkip = false
            createdStream.seekTo(target)
        } else {
            lastSeekUsedSkip = true
            seekByRendering(target, createdSink)
        }

        if (ok) {
            positionBaseMs = target
            headBaseFrames = createdSink.playedFrames
        }
        if (wasPlaying) {
            playing = true
            createdSink.play()
        }
        return ok
    }

    /**
     * 不支持 seek 的解码器：换一个新句柄，把 PCM 直接渲染丢弃到 [targetMs]。
     * 成功时把新的 [PcmStream] 装进 [stream]，写线程下一轮就会用上它。
     */
    private fun seekByRendering(targetMs: Long, createdSink: PcmAudioSink): Boolean {
        val bytes = sourceBytes ?: return false
        val old = stream
        val sampleRate = createdSink.sampleRate
        val skipFrames = targetMs * sampleRate / 1000
        return try {
            val decoder = OrzAudioCore.createDecoder(bytes, format)
            val ch = decoder.info.channels.coerceAtLeast(1)
            val buffer = FloatArray(4096 * ch)
            var rendered = 0L
            while (rendered < skipFrames) {
                val want = minOf(4096L, skipFrames - rendered).toInt()
                val n = decoder.renderF32(buffer, want)
                if (n <= 0) break
                rendered += n
            }
            val fresh = PcmStream(decoder)
            fresh.start()
            stream = fresh
            old?.close()
            true
        } catch (t: Throwable) {
            // 失败就保持原样：老流还在（只是缓冲被清空了），位置按老的算。
            errorMessage = t.message ?: t.javaClass.simpleName
            false
        }
    }

    /** 当前解码器是否原生支持 seek（不支持时会走重解码快进，所以对外仍可 seek）。 */
    val decoderSupportsSeek: Boolean get() = stream?.isSeekable == true

    fun selectSubsong(index: Int) {
        val createdStream = stream ?: return
        val createdSink = sink ?: return
        val wasPlaying = playing
        playing = false
        createdSink.pause()
        createdSink.flush()
        if (createdStream.selectSubsong(index)) {
            positionBaseMs = 0L
            headBaseFrames = createdSink.playedFrames
        }
        if (wasPlaying) {
            playing = true
            createdSink.play()
        }
    }

    fun setVolume(volume: Float) {
        sink?.volume = volume
    }

    fun release() {
        if (released) return
        released = true
        playing = false
        // 先停写线程，再关 sink，最后关 stream（PcmStream.close 内部会 cancel+join+destroy）。
        worker?.let { runCatching { it.join(1000) } }
        worker = null
        runCatching { sink?.close() }
        sink = null
        runCatching { stream?.close() }
        stream = null
    }
}
