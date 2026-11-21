package com.jokerhub.orzmusic.android.player

import com.jokerhub.orzmusic.audiokit.OrzAudioCore
import com.jokerhub.orzmusic.audiokit.PcmStream
import java.io.File

/**
 * 「模块 / 芯片音乐」的流式播放引擎：`PcmStream`(解码线程) → [PcmAudioSink](AudioTrack)。
 *
 * 与旧的「整曲转 WAV 再交给 ExoPlayer」相比：
 * - 首播只等 2 秒缓冲，不再等全曲解码；
 * - 不落盘（raw 文件已经缓存过），seek 直接走 `orz_decoder_seek`；
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
    private val onState: (State, String?) -> Unit
) {

    enum class State { BUFFERING, READY, ENDED, ERROR }

    @Volatile
    private var stream: PcmStream? = null

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
            startWriter(createdStream, createdSink)
        } catch (t: Throwable) {
            fail(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun startWriter(createdStream: PcmStream, createdSink: PcmAudioSink) {
        val ch = createdStream.channels
        val chunkFrames = 2048
        val buffer = FloatArray(chunkFrames * ch)
        worker = Thread({
            try {
                while (!released) {
                    if (!playing) {
                        Thread.sleep(20)
                        continue
                    }
                    val frames = createdStream.read(buffer, chunkFrames)
                    if (frames == 0) {
                        if (createdStream.isEnded) {
                            playing = false
                            onState(State.ENDED, null)
                            break
                        }
                        continue
                    }
                    onSamples(buffer, frames, ch)
                    createdSink.write(buffer, frames)
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

    fun seekTo(positionMs: Long) {
        val createdStream = stream ?: return
        val target = positionMs.coerceAtLeast(0L)
        val createdSink = sink
        if (createdSink != null) {
            // flush 只能在 pause 之后；先暂停再清空，避免把还在播的数据一起丢掉。
            val wasPlaying = playing
            playing = false
            createdSink.pause()
            createdSink.flush()
            if (createdStream.seekTo(target)) {
                positionBaseMs = target
                headBaseFrames = createdSink.playedFrames
            }
            if (wasPlaying) {
                playing = true
                createdSink.play()
            }
        } else {
            positionBaseMs = target
        }
    }

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
