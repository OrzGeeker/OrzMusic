package com.jokerhub.orzmusic.audiokit

import android.util.Log
import org.json.JSONObject
import java.io.Closeable
import java.io.File

/** 解码流信息,对齐 Swift 的 `DecoderStreamInfo`。 */
data class DecoderStreamInfo(
    val sampleRate: Int,
    val channels: Int,
    val durationMs: Long,
    val subsongCount: Int,
    val capabilities: Int
) {
    val durationSeconds: Double get() = durationMs / 1000.0
}

/** 当前 profile 编进来的格式信息,由 decoder-manifest.json + profile 生成。 */
data class DecoderFormatInfo(
    val id: String,
    val displayName: String,
    val decoderID: String,
    val decoderVersion: String,
    val category: String,
    val seek: Boolean,
    val subsong: Boolean
)

data class ProbeResult(val format: String, val confidence: Int)

class OrzAudioCoreException(
    val status: Int,
    message: String
) : Exception("$message (status=$status)")

/**
 * Android 版 OrzAudioCore SDK 入口。
 *
 * 与 Swift 的 `AudioDecoder` 对齐:
 *   OrzAudioCore.createDecoder(data, format, subsong) → AudioDecoder
 *   decoder.renderF32(buffer, frames) / seekMs / selectSubsong / close
 *
 * 不做文件、WAV、HTTP、缓存、策略 —— 那些在客户端(WavFile/MediaCache/PlaybackPolicy)。
 */
object OrzAudioCore {

    private const val TAG = "OrzAudioCore"

    private val loadResult: Result<Unit> = runCatching {
        System.loadLibrary("orzaudio")
    }.onFailure {
        Log.w(TAG, "liborzaudio.so 未加载: ${it.message}")
    }

    val isAvailable: Boolean get() = loadResult.isSuccess

    val abiVersion: Int
        get() = if (isAvailable) runCatching { nativeAbiVersion() }.getOrDefault(0) else 0

    fun buildInfo(): String? =
        if (isAvailable) runCatching { nativeBuildInfo() }.getOrNull() else null

    /**
     * `orz_build_info()` 里的 fingerprint;写进 WAV 缓存键。
     * 升级 SDK 后旧缓存自动失效(和服务端 PCM 缓存键同一口径)。
     */
    val cacheFingerprint: String by lazy {
        val fallback = "orzcore-abi-$abiVersion"
        val json = buildInfo() ?: return@lazy fallback
        runCatching {
            JSONObject(json).optString("fingerprint", fallback)
        }.getOrDefault(fallback)
    }

    /**
     * 本机 `.so` 真的编进来的格式 id(运行时真相)。
     *
     * 与 [DecoderManifest] 的区别:清单是「这个 SDK 版本提供哪些格式」,
     * 这里是「当前二进制能解哪些」—— 注册表用弱符号存根过滤缺失解码器。
     * 两者取交集才是可用能力,所以清单先行、二进制后到也不会播错。
     */
    val nativeFormats: Set<String> by lazy {
        if (!isAvailable) return@lazy emptySet<String>()
        val probed = runCatching {
            nativeFormatIds()?.filterNotNull()?.map { it.lowercase() }?.toSet()
        }.getOrNull()
        // 探测失败/空 → 退回清单。一次坏的 JNI 绑定不应该把整个原生播放路径判死。
        if (probed.isNullOrEmpty()) DecoderManifest.supportedIds else probed
    }

    /** 清单 ∩ 运行时 —— 真正可用的格式(生成物,不手写)。 */
    val supportedFormats: List<DecoderFormatInfo>
        get() = DecoderManifest.formats.filter { it.id in nativeFormats }

    fun supports(format: String): Boolean = format.lowercase() in nativeFormats

    fun probe(data: ByteArray, extensionHint: String? = null): ProbeResult? {
        if (!isAvailable || data.size < 32) return null
        val format = runCatching { nativeProbe(data, extensionHint) }.getOrNull() ?: return null
        return ProbeResult(format, 100)
    }

    /**
     * 创建解码器。调用方负责 [AudioDecoder.close]。
     *
     * @param subsong 0 = 默认;>0 时调用 C ABI 的 select_subsong。
     */
    fun createDecoder(data: ByteArray, format: String, subsong: Int = 0): AudioDecoder {
        if (!isAvailable) {
            throw OrzAudioCoreException(-7, "liborzaudio.so 未加载")
        }
        if (data.size < 32) {
            throw OrzAudioCoreException(-1, "输入数据过小")
        }

        val handle = nativeCreateDecoder(data, format.lowercase(), subsong)
        if (handle == 0L) {
            throw OrzAudioCoreException(-4, "无法创建 $format 解码器(格式不支持或数据损坏)")
        }
        return AudioDecoder(handle)
    }

    /** decode-to-WAV 便利层;正式调用方可以只用 [createDecoder] + [AudioDecoder]。 */
    fun decodeToWav(
        input: File,
        output: File,
        format: String,
        subsong: Int = 0
    ): WavInfo {
        if (!input.isFile || input.length() < 32L) {
            throw IllegalArgumentException("输入文件不存在或过小: ${input.absolutePath}")
        }
        val data = input.readBytes()
        output.parentFile?.mkdirs()
        return try {
            createDecoder(data, format, subsong).use { decoder ->
                WavFile.write(decoder, output)
            }
        } catch (t: Throwable) {
            output.delete()
            throw t
        }
    }

    // JNI 实现见 audio-kit/native/cpp/orz_jni.c
    external fun nativeCreateDecoder(data: ByteArray, format: String, subsong: Int): Long
    external fun nativeStreamInfo(handle: Long): LongArray?
    external fun nativeRenderF32(handle: Long, out: FloatArray, frames: Int): Int
    external fun nativeSeek(handle: Long, positionMs: Long): Int
    external fun nativeSelectSubsong(handle: Long, subsong: Int): Int
    external fun nativeCancel(handle: Long): Int
    external fun nativeDestroy(handle: Long)
    external fun nativeProbe(data: ByteArray, extensionHint: String?): String?
    external fun nativeFormatIds(): Array<String?>?
    external fun nativeAbiVersion(): Int
    external fun nativeBuildInfo(): String
}

/**
 * 一个解码器句柄。同一实例由调用方串行访问;不同实例可以并行。
 * 对齐 Swift 的 `AudioDecoder`。
 */
class AudioDecoder internal constructor(private var handle: Long) : Closeable {

    val info: DecoderStreamInfo = run {
        val values = OrzAudioCore.nativeStreamInfo(handle)
            ?: run {
                OrzAudioCore.nativeDestroy(handle)
                handle = 0L
                throw OrzAudioCoreException(-7, "无法读取 decoder stream info")
            }
        DecoderStreamInfo(
            sampleRate = values[0].toInt(),
            channels = values[1].toInt(),
            durationMs = values[2],
            subsongCount = values[3].toInt(),
            capabilities = values[4].toInt()
        )
    }

    val isOpen: Boolean get() = handle != 0L

    /**
     * 渲染最多 [frames] 帧到 [out](interleaved float32)。
     * 返回实际帧数;0 表示流结束;负值抛 [OrzAudioCoreException]。
     */
    fun renderF32(out: FloatArray, frames: Int): Int {
        check(handle != 0L) { "decoder 已关闭" }
        if (frames <= 0) return 0
        val need = frames * info.channels.coerceAtLeast(1)
        require(out.size >= need) { "buffer 太小: ${out.size} < $need" }
        val rendered = OrzAudioCore.nativeRenderF32(handle, out, frames)
        if (rendered < 0) {
            throw OrzAudioCoreException(rendered, "render 失败")
        }
        return rendered
    }

    fun seekMs(positionMs: Long): Boolean {
        if (handle == 0L || positionMs < 0) return false
        return OrzAudioCore.nativeSeek(handle, positionMs) == 0
    }

    fun selectSubsong(index: Int): Boolean {
        if (handle == 0L || index < 0) return false
        return OrzAudioCore.nativeSelectSubsong(handle, index) == 0
    }

    /**
     * 打断正在进行的 [renderF32]（从其它线程调用）。
     * 流式播放关闭时用它让解码线程尽快退出，再销毁句柄。
     */
    fun cancel() {
        val current = handle
        if (current != 0L) OrzAudioCore.nativeCancel(current)
    }

    override fun close() {
        val current = handle
        if (current != 0L) {
            handle = 0L
            OrzAudioCore.nativeDestroy(current)
        }
    }
}
