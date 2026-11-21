package com.jokerhub.orzmusic.android.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.Closeable
import kotlin.math.max

/**
 * `AudioTrack` 的 float PCM 流式接收端。
 *
 * 配合 `audio-kit` 的 `PcmStream`：解码线程产 PCM → [write] 阻塞写入硬件缓冲。
 * 与旧路径（整曲转 WAV 再交给 ExoPlayer）相比，首播只等 2 秒缓冲、seek 走
 * `PcmStream.seekTo`、不再落盘。
 *
 * 只做「设备输出」这一件事：不理解曲目、不认识格式、不做队列。
 */
class PcmAudioSink(
    sampleRate: Int,
    channels: Int,
    /** 硬件缓冲时长；太短容易欠载爆音，太长让 seek 变迟钝。 */
    bufferDurationMs: Int = 250
) : Closeable {

    val sampleRate: Int = sampleRate.coerceAtLeast(8_000)
    val channels: Int = channels.coerceIn(1, 2)

    private val channelMask: Int =
        if (this.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO

    private val bytesPerFrame = 4 * this.channels // ENCODING_PCM_FLOAT

    private val bufferBytes: Int = max(
        AudioTrack.getMinBufferSize(this.sampleRate, channelMask, AudioFormat.ENCODING_PCM_FLOAT),
        this.sampleRate * bytesPerFrame * bufferDurationMs / 1000
    )

    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(this.sampleRate)
                .setChannelMask(channelMask)
                .build()
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(bufferBytes)
        .build()

    private var released = false

    // AudioTrack 只有 setVolume(float),没有 getter —— 自己记住当前值。
    private var volumeValue = 1f

    var volume: Float
        get() = volumeValue
        set(value) {
            volumeValue = value.coerceIn(0f, 1f)
            if (!released) track.setVolume(volumeValue)
        }

    val isPlaying: Boolean
        get() = !released && track.playState == AudioTrack.PLAYSTATE_PLAYING

    /** 已播出的帧数（按硬件播放头），用于进度条。 */
    val playedFrames: Long
        get() = if (released) 0L else track.playbackHeadPosition.toLong() and 0xFFFFFFFFL

    val playedMs: Long
        get() = playedFrames * 1000L / sampleRate

    fun play() {
        if (!released && track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
    }

    fun pause() {
        if (!released) track.pause()
    }

    /** 丢弃尚未播出的数据（seek / 切曲时调用）。只能对已 pause 的 track 调用。 */
    fun flush() {
        if (!released) track.flush()
    }

    /**
     * 阻塞写入 [frames] 帧（`[pcm]` 是 interleaved float）。
     *
     * @return 实际写入的帧数；`0` 表示被停止或写入失败。
     */
    fun write(pcm: FloatArray, frames: Int): Int {
        if (released || frames <= 0) return 0
        val floats = frames * channels
        if (pcm.size < floats) return 0
        val written = track.write(pcm, 0, floats, AudioTrack.WRITE_BLOCKING)
        if (written < 0) return 0
        return written / channels
    }

    override fun close() {
        if (released) return
        released = true
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
        runCatching { track.release() }
    }
}
