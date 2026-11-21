package com.jokerhub.orzmusic.audiokit

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 流式 PCM 源：解码线程 → 有界 ring buffer → 播放设备（`AudioTrack`）。
 *
 * 为什么要有这一层：
 *
 * 旧实现是 `OrzAudioCore.decodeToWav()` —— **把整首曲解成 16-bit WAV 文件**再交给
 * 播放器。对 `xm`（几秒）还能忍，对 `sc68` / `sid` / 长曲目是三重灾难：
 * 首播延迟 = 全曲解码时间、缓存体积 ≈ 10MB/分钟、seek 只能靠 WAV 字节偏移。
 *
 * [PcmStream] 只做三件事：让解码线程跑在前面一小段（默认 2 秒）、把 PCM 交给
 * 消费方、把 seek / subsong 转发给解码器。它**不**碰 Android API，所以平台层
 * （`AudioTrack`）和测试替身都能直接用它。
 *
 * 线程模型：
 * - 一个解码线程，负责**全部** `AudioDecoder` 调用（SDK 要求同一 handle 串行访问）；
 * - 所有状态由 [lock] 保护，[read] 阻塞等待（最多 [readTimeoutMs]）；
 * - `seek` / `selectSubsong` 只是投递请求：清空缓冲，由解码线程执行并回执。
 *
 * 用法：
 * ```
 * OrzAudioCore.createDecoder(bytes, format).use { decoder ->
 *     PcmStream(decoder).use { stream ->
 *         stream.start()
 *         val dst = FloatArray(CHUNK * stream.channels)
 *         while (true) {
 *             val n = stream.read(dst, CHUNK)
 *             if (n == 0 && stream.isEnded) break
 *             if (n > 0) sink.write(dst, n)
 *         }
 *     }
 * }
 * ```
 */
class PcmStream(
    private val decoder: AudioDecoder,
    /** ring buffer 容量（帧）。默认约 2 秒，是抖动与首播延迟的折中。 */
    capacityFrames: Int = 0,
    /** 每次向解码器请求的帧数。 */
    private val chunkFrames: Int = 4096,
    /** [read] 等待数据的上限；返回 0 表示这段时间里没拿到数据。 */
    private val readTimeoutMs: Long = 200L,
    /** seek / subsong 请求等待解码线程回执的上限。 */
    private val controlTimeoutMs: Long = 500L
) : Closeable {

    val info: DecoderStreamInfo = decoder.info
    val sampleRate: Int = info.sampleRate.coerceAtLeast(1)
    val channels: Int = info.channels.coerceAtLeast(1)
    val durationMs: Long = info.durationMs
    val subsongCount: Int = info.subsongCount

    /** ring buffer 的实际容量（帧）。 */
    val capacity: Int = if (capacityFrames > 0) capacityFrames else sampleRate * 2

    private val lock = Object()
    private val ring = FloatArray(capacity * channels)
    private var ringHead = 0          // 读位置(帧)
    private var ringCount = 0         // 已缓冲帧数

    private val decodeChunk = FloatArray(chunkFrames * channels)

    private val running = AtomicBoolean(false)
    private val ended = AtomicBoolean(false)
    private var failure: Throwable? = null

    /** 已交给消费方的帧数(seek 后重置)，用来算播放位置。 */
    private val deliveredFrames = AtomicLong(0)

    // ── 控制请求:只由解码线程执行，保证 handle 串行访问 ──
    private var pendingSeekMs: Long = -1
    private var seekResult: Boolean? = null
    private var pendingSubsong: Int = -1
    private var subsongResult: Boolean? = null

    private var thread: Thread? = null

    @Volatile
    private var closed = false

    /** 流是否已经解码到底（缓冲可能还有剩）。 */
    val isEnded: Boolean get() = ended.get()

    /** 当前播放位置(毫秒)，基于已消费帧数。 */
    val positionMs: Long get() = deliveredFrames.get() * 1000L / sampleRate

    /** 已在缓冲里、尚未被消费的帧数。 */
    val bufferedFrames: Int get() = synchronized(lock) { ringCount }

    /** 启动解码线程。重复调用无副作用。 */
    fun start() {
        check(!closed) { "PcmStream 已关闭" }
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ decodeLoop() }, "orz-pcm-decode").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    /**
     * 取最多 [frames] 帧到 [dst]（interleaved float32）。
     *
     * 返回实际帧数；`0` 有两种含义，用 [isEnded] 区分：流已结束，
     * 或这次等待超时（播放器应该继续下一个周期）。
     *
     * @throws OrzAudioCoreException 解码线程上报的错误。
     */
    fun read(dst: FloatArray, frames: Int): Int {
        if (frames <= 0) return 0
        val need = frames * channels
        require(dst.size >= need) { "buffer 太小: ${dst.size} < $need" }

        synchronized(lock) {
            val deadline = System.currentTimeMillis() + readTimeoutMs
            while (ringCount == 0 && !ended.get() && failure == null) {
                val wait = deadline - System.currentTimeMillis()
                if (wait <= 0) break
                lock.wait(wait)
            }

            failure?.let { throw it }
            if (ringCount == 0) return 0

            val take = minOf(frames, ringCount)
            for (i in 0 until take) {
                val srcBase = ((ringHead + i) % capacity) * channels
                System.arraycopy(ring, srcBase, dst, i * channels, channels)
            }
            ringHead = (ringHead + take) % capacity
            ringCount -= take
            deliveredFrames.addAndGet(take.toLong())
            lock.notifyAll()          // 有空间了，唤醒解码线程
            return take
        }
    }

    /** 跳到 [positionMs]。返回 false 表示解码器不支持或请求超时。 */
    fun seekTo(positionMs: Long): Boolean {
        if (positionMs < 0) return false
        // 未 start 时没有解码线程，直接在当前线程执行（此时不存在并发 render）。
        val worker = thread
        if (worker == null) return decoder.seekMs(positionMs)
        synchronized(lock) {
            if (closed) return false
            pendingSeekMs = positionMs
            pendingSubsong = -1
            seekResult = null
            subsongResult = null
            resetBufferLocked(positionMs)
            lock.notifyAll()

            val deadline = System.currentTimeMillis() + controlTimeoutMs
            while (seekResult == null) {
                val wait = deadline - System.currentTimeMillis()
                if (wait <= 0) return false
                lock.wait(wait)
            }
            return seekResult == true
        }
    }

    /** 切换到第 [index] 个子曲（多 subsong 的 NSF / SID）。 */
    fun selectSubsong(index: Int): Boolean {
        if (index < 0) return false
        val worker = thread
        if (worker == null) return decoder.selectSubsong(index)
        synchronized(lock) {
            if (closed) return false
            pendingSubsong = index
            pendingSeekMs = -1
            subsongResult = null
            seekResult = null
            resetBufferLocked(0)
            lock.notifyAll()

            val deadline = System.currentTimeMillis() + controlTimeoutMs
            while (subsongResult == null) {
                val wait = deadline - System.currentTimeMillis()
                if (wait <= 0) return false
                lock.wait(wait)
            }
            return subsongResult == true
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        running.set(false)
        // 先 cancel 再 join:解码线程可能正卡在 renderF32 里。
        runCatching { decoder.cancel() }
        synchronized(lock) {
            ringCount = 0
            ended.set(true)
            seekResult = seekResult ?: false
            subsongResult = subsongResult ?: false
            lock.notifyAll()
        }
        thread?.let { worker ->
            runCatching { worker.join(2000) }
            if (worker.isAlive) {
                // 没退出就不要销毁句柄:nativeDestroy 与 render 竞争会崩。
                return
            }
        }
        thread = null
        runCatching { decoder.close() }
    }

    // ── 内部 ─────────────────────────────────────────────────

    private fun resetBufferLocked(positionMs: Long) {
        ringHead = 0
        ringCount = 0
        ended.set(false)
        deliveredFrames.set(positionMs * sampleRate / 1000L)
    }

    private fun decodeLoop() {
        try {
            while (running.get() && !closed) {
                if (!applyPendingControl()) break

                synchronized(lock) {
                    // 有空间才继续渲染；消费方没追上来就挂起，避免抢 CPU。
                    while (ringCount > capacity - chunkFrames && running.get()) {
                        lock.wait(500)
                    }
                }
                if (!running.get()) break
                if (!applyPendingControl()) break

                val rendered = decoder.renderF32(decodeChunk, chunkFrames)
                if (rendered == 0) {
                    synchronized(lock) {
                        ended.set(true)
                        lock.notifyAll()
                    }
                    break
                }

                synchronized(lock) {
                    var offset = 0
                    while (offset < rendered && running.get()) {
                        val space = capacity - ringCount
                        if (space <= 0) {
                            lock.wait(500)
                            continue
                        }
                        val take = minOf(rendered - offset, space)
                        for (i in 0 until take) {
                            val dstBase = ((ringHead + ringCount + i) % capacity) * channels
                            System.arraycopy(
                                decodeChunk,
                                (offset + i) * channels,
                                ring,
                                dstBase,
                                channels
                            )
                        }
                        ringCount += take
                        offset += take
                        lock.notifyAll()
                    }
                }
            }
        } catch (t: Throwable) {
            synchronized(lock) {
                failure = t
                ended.set(true)
                seekResult = seekResult ?: false
                subsongResult = subsongResult ?: false
                lock.notifyAll()
            }
        } finally {
            running.set(false)
        }
    }

    /** 在解码线程上执行挂起的 seek / subsong。返回 false 表示流已关闭。 */
    private fun applyPendingControl(): Boolean {
        synchronized(lock) {
            if (closed) return false
            val seek = pendingSeekMs
            if (seek >= 0) {
                pendingSeekMs = -1
                val ok = runCatching { decoder.seekMs(seek) }.getOrDefault(false)
                seekResult = ok
                if (!ok) deliveredFrames.set(0)
                lock.notifyAll()
            }
            val subsong = pendingSubsong
            if (subsong >= 0) {
                pendingSubsong = -1
                val ok = runCatching { decoder.selectSubsong(subsong) }.getOrDefault(false)
                subsongResult = ok
                lock.notifyAll()
            }
            return true
        }
    }
}
