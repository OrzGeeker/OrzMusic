package com.jokerhub.orzmusic.audiokit

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt

data class WavInfo(
    val sampleRate: Int,
    val channels: Int,
    val dataBytes: Long
) {
    val durationSeconds: Double
        get() = if (sampleRate > 0 && channels > 0) {
            dataBytes.toDouble() / (sampleRate * channels * 2.0)
        } else {
            0.0
        }
}

/**
 * 16-bit PCM WAV 写入器 —— 对齐 Swift 的 `WAVFile.swift`。
 *
 * 放在 Kotlin(而不是 C core)里:容器/文件是平台/客户端关注点;
 * OrzAudioCore 只负责渲染 float PCM。
 */
object WavFile {

    private const val CHUNK_FRAMES = 4096

    fun write(decoder: AudioDecoder, output: File): WavInfo {
        val info = decoder.info
        val rate = info.sampleRate.coerceAtLeast(1)
        val channels = info.channels.coerceAtLeast(1)
        output.parentFile?.mkdirs()

        RandomAccessFile(output, "rw").use { raf ->
            raf.setLength(0)
            raf.write(header(rate, channels, 0L))

            val fbuf = FloatArray(CHUNK_FRAMES * channels)
            val pcm = ByteArray(CHUNK_FRAMES * channels * 2)
            var dataBytes = 0L

            while (true) {
                val rendered = decoder.renderF32(fbuf, CHUNK_FRAMES)
                if (rendered == 0) break
                if (rendered < 0) error("render 失败: $rendered")

                val samples = rendered * channels
                var j = 0
                for (i in 0 until samples) {
                    val s = (fbuf[i].coerceIn(-1f, 1f) * 32767f).roundToInt()
                    pcm[j++] = (s and 0xff).toByte()
                    pcm[j++] = ((s shr 8) and 0xff).toByte()
                }
                raf.write(pcm, 0, samples * 2)
                dataBytes += samples * 2L
                if (dataBytes > 0xFFFFFFFFL - 36L) {
                    error("WAV 超过 4GB")
                }
            }

            raf.seek(0)
            raf.write(header(rate, channels, dataBytes))
            return WavInfo(rate, channels, dataBytes)
        }
    }

    private fun header(sampleRate: Int, channels: Int, dataBytes: Long): ByteArray {
        val h = ByteArray(44)
        putAscii(h, 0, "RIFF")
        putU32(h, 4, 36L + dataBytes)
        putAscii(h, 8, "WAVEfmt ")
        putU32(h, 16, 16L)
        putU16(h, 20, 1) // PCM
        putU16(h, 22, channels)
        putU32(h, 24, sampleRate.toLong())
        putU32(h, 28, sampleRate.toLong() * channels * 2L)
        putU16(h, 32, channels * 2)
        putU16(h, 34, 16)
        putAscii(h, 36, "data")
        putU32(h, 40, dataBytes)
        return h
    }

    private fun putAscii(target: ByteArray, offset: Int, value: String) {
        for (i in value.indices) target[offset + i] = value[i].code.toByte()
    }

    private fun putU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xff).toByte()
        target[offset + 1] = ((value shr 8) and 0xff).toByte()
    }

    private fun putU32(target: ByteArray, offset: Int, value: Long) {
        target[offset] = (value and 0xff).toByte()
        target[offset + 1] = ((value shr 8) and 0xff).toByte()
        target[offset + 2] = ((value shr 16) and 0xff).toByte()
        target[offset + 3] = ((value shr 24) and 0xff).toByte()
    }
}
