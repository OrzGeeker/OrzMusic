package com.jokerhub.orzmusic.android.player

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 在解码器输出 PCM 后、AudioTrack 写入前取一份数据，计算柱状电平。
 *
 * 不申请 RECORD_AUDIO：数据来自本应用自己的播放管线。
 * 只支持 PCM16 / float；其他编码直接透传（不画声场）。
 */
class VisualizerAudioProcessor : BaseAudioProcessor() {

    private companion object {
        const val FFT_SIZE = 1024
    }

    private var channels = 2
    private var encoding = C.ENCODING_INVALID
    private var sampleBytes = 2
    private var sampleRate = 48_000
    private var lastPublishMs = 0L
    private var smoothed = FloatArray(VisualizerBus.BAR_COUNT)
    private val fftReal = FloatArray(FFT_SIZE)
    private val fftImag = FloatArray(FFT_SIZE)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        encoding = inputAudioFormat.encoding
        sampleRate = inputAudioFormat.sampleRate
        sampleBytes = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> 0
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size <= 0) return

        // 先复制一份给分析线程用，再把原 buffer 原样透传给 AudioSink。
        // 必须从 inputBuffer 本身 get（不能用 duplicate）：AudioProcessor 契约要求
        // queueInput 消费输入，否则 AudioSink 会反复处理同一块 PCM，听起来像噪声/卡带。
        val copy = ByteArray(size)
        inputBuffer.get(copy)
        val output = replaceOutputBuffer(size)
        output.put(copy)
        output.flip()

        if (sampleBytes > 0) analyze(copy)
    }

    private fun analyze(bytes: ByteArray) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPublishMs < 32L) return
        lastPublishMs = now

        val samples = when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                FloatArray(shorts.remaining()) { shorts.get(it) / 32768f }
            }
            C.ENCODING_PCM_FLOAT -> {
                val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                FloatArray(floats.remaining()) { floats.get(it) }
            }
            else -> return
        }

        val frames = samples.size / channels
        if (frames <= 0) return

        // 取最后 FFT_SIZE 帧做单声道混音 + Hann 窗；不足则补零。
        val startFrame = (frames - FFT_SIZE).coerceAtLeast(0)
        for (i in 0 until FFT_SIZE) {
            val frame = startFrame + i
            var mono = 0f
            if (frame < frames) {
                var channel = 0
                while (channel < channels) {
                    mono += samples[frame * channels + channel]
                    channel++
                }
                mono /= channels.toFloat()
            }
            val hann = 0.5f - 0.5f * cos(2.0 * PI * i / (FFT_SIZE - 1)).toFloat()
            fftReal[i] = mono * hann
            fftImag[i] = 0f
        }

        fft(fftReal, fftImag)

        val bars = VisualizerBus.BAR_COUNT
        val next = FloatArray(bars)
        val binHz = sampleRate.toFloat() / FFT_SIZE
        val minHz = 40f
        val maxHz = minOf(16_000f, sampleRate / 2f * 0.92f)
        val ratio = maxHz / minHz
        for (bar in 0 until bars) {
            val f0 = minHz * ratio.pow(bar.toFloat() / bars)
            val f1 = minHz * ratio.pow((bar + 1).toFloat() / bars)
            val bin0 = (f0 / binHz).toInt().coerceIn(1, FFT_SIZE / 2 - 2)
            val bin1 = ((f1 / binHz).toInt() + 1).coerceIn(bin0 + 1, FFT_SIZE / 2 - 1)
            var peak = 0f
            for (bin in bin0 until bin1) {
                val re = fftReal[bin]
                val im = fftImag[bin]
                val magnitude = sqrt(re * re + im * im) * 4f / FFT_SIZE
                if (magnitude > peak) peak = magnitude
            }
            val db = 20f * log10(peak + 1e-6f)
            // 不额外加“高频衰减”斜率，避免所有频段连成一条平滑下降的斜线；
            // 用更宽的 dB 窗 + gamma 保留低频到高频的自然差异。
            val level = (((db + 50f) / 50f).coerceIn(0f, 1f)).pow(1.55f)
            next[bar] = (smoothed[bar] * 0.42f + level * 0.58f).coerceIn(0f, 1f)
        }
        smoothed = next
        VisualizerBus.publish(next.copyOf())
    }

    /** 原地迭代 radix-2 FFT。 */
    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wCos = cos(angle).toFloat()
            val wSin = sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var curCos = 1f
                var curSin = 0f
                val half = len / 2
                for (k in 0 until half) {
                    val a = i + k
                    val b = a + half
                    val vR = real[b] * curCos - imag[b] * curSin
                    val vI = real[b] * curSin + imag[b] * curCos
                    val uR = real[a]
                    val uI = imag[a]
                    real[a] = uR + vR
                    imag[a] = uI + vI
                    real[b] = uR - vR
                    imag[b] = uI - vI
                    val nextCos = curCos * wCos - curSin * wSin
                    curSin = curSin * wCos + curCos * wSin
                    curCos = nextCos
                }
                i += len
            }
            len = len shl 1
        }
    }

    override fun onFlush() {
        // 切歌 / seek 时旧电平不要残留太久。
        smoothed = FloatArray(smoothed.size)
        VisualizerBus.reset()
    }

    override fun onReset() {
        smoothed = FloatArray(smoothed.size)
        VisualizerBus.reset()
    }
}
