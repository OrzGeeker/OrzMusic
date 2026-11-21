package com.jokerhub.orzmusic.android.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jokerhub.orzmusic.audiokit.OrzAudioCore
import com.jokerhub.orzmusic.core.playback.DecoderManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设备端冒烟测试 —— 验收「这台设备真的能解 23 个格式」。
 *
 * 为什么必须上设备:JVM 单测(Robolectric)加载不了 `liborzaudio.so`,
 * 只能验清单;真正的能力是运行时 `orz_get_format_count()` / `orz_get_format_info()`
 * 探测出来的,和打包进 APK 的 `.so` 强相关(漏编 decoder、ABI 不对都会露馅)。
 *
 * 跑法:
 *   cd clients/android && ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class OrzAudioCoreDeviceTest {

    @Test
    fun nativeLibraryLoads() {
        assertTrue("liborzaudio.so 没加载起来(ABI 不支持或打包遗漏)", OrzAudioCore.isAvailable)
        // orz_abi_version() 是 (major << 16) | minor,不是裸的 1。
        val abi = OrzAudioCore.abiVersion
        assertEquals("C ABI major", 1, OrzAudioCore.abiVersionMajor)
        assertEquals("C ABI minor", 0, OrzAudioCore.abiVersionMinor)
        assertTrue("abi_version 低 16 位应为 minor:0x%08x".format(abi), abi and 0xffff == 0)
    }

    @Test
    fun buildInfoMatchesLockedSdkVersion() {
        val info = OrzAudioCore.buildInfo()
        assertTrue("orz_build_info() 返回空", info != null)
        // 期望版本取自生成的清单(单一事实来源),不在这里再写一遍版本号。
        val expected = DecoderManifest.SDK_VERSION.substringAfterLast(' ')
        assertTrue("build info 里没有 $expected:$info", info!!.contains("\"version\":\"$expected\""))
        assertTrue(
            "指纹应带 SDK 版本:${OrzAudioCore.cacheFingerprint}",
            OrzAudioCore.cacheFingerprint.startsWith("orzcore-$expected-")
        )
    }

    @Test
    fun decodesMinimalModuleToPcm() {
        // 光有注册表还不够:JNI 的 create/render 链路也得真能出 PCM。
        // 这里手搓一个最小的 4 声道 ProTracker MOD(1 个 2 字节采样、空 pattern),
        // 不依赖任何 fixture 文件。
        val mod = minimalMod()
        assertEquals("mod", OrzAudioCore.probe(mod, "mod")?.format)

        OrzAudioCore.createDecoder(mod, "mod").use { decoder ->
            val info = decoder.info
            assertTrue("采样率异常:${info.sampleRate}", info.sampleRate > 0)
            // 这是**输出**声道数(立体声),不是 MOD 里声明的 4 个声道。
            assertTrue("输出声道数异常:${info.channels}", info.channels in 1..2)
            assertTrue("时长异常:${info.durationMs}", info.durationMs > 0)

            val frames = 1024
            val out = FloatArray(frames * info.channels)
            val rendered = decoder.renderF32(out, frames)
            assertEquals("首块应该能渲染满", frames, rendered)
            assertTrue("应该输出有限值", out.all { it.isFinite() })
        }
    }

    @Test
    fun everyProfileFormatIsDecodable() {
        val manifest = DecoderManifest.formats.map { it.id }.toSet()
        // 清单 ∩ 运行时 = 可用能力;两者必须完全相等,缺一个就是打包或探测有问题。
        assertEquals(
            "运行时缺失的格式:${manifest - OrzAudioCore.nativeFormats}",
            manifest,
            OrzAudioCore.nativeFormats
        )
        assertEquals(23, OrzAudioCore.supportedFormats.size)
    }

    /** 最小的合法 ProTracker MOD:20B 标题 + 31×30B 采样头 + 曲序 + "M.K." + 1 个空 pattern + 2B 采样。 */
    private fun minimalMod(): ByteArray {
        val buf = java.nio.ByteBuffer.allocate(1084 + 1024 + 2).order(java.nio.ByteOrder.BIG_ENDIAN)
        buf.position(20)                      // 标题留空
        // 采样 1:长度 1 word(2 字节)、音量 64、repeat 0..1
        buf.position(20 + 22)
        buf.putShort(1)
        buf.put(0)                            // finetune
        buf.put(64)                           // volume
        buf.putShort(0)                       // repeat start
        buf.putShort(1)                       // repeat length
        buf.position(950)
        buf.put(1)                            // song length
        buf.put(127)                          // restart
        buf.put(0)                            // pattern 表第 0 项 = pattern 0
        buf.position(1080)
        buf.put("M.K.".toByteArray(Charsets.US_ASCII))
        // 1024 字节空 pattern(64 行 × 4 声道 × 4 字节)已经是 0
        buf.position(1084 + 1024)
        buf.put(0); buf.put(0)                // 采样数据
        return buf.array()
    }
}
