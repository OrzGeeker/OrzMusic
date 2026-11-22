package com.jokerhub.orzmusic.android

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 射线指着进度条时，左右键由 Activity 直接 ±5s（不依赖 Compose 焦点）。
 *
 * 真机上进度条拿不到方向键焦点（logcat 里没有 `focus@`），所以左右键一直落在
 * 「移动焦点」上；这里把命中判定做成独立可测的一层。
 */
@RunWith(AndroidJUnit4::class)
class SeekBarZonesTest {

    @Test
    fun rayInsideZoneAdjustsProgress() {
        var captured = 0L
        val zone = SeekBarZones.Zone("test").apply {
            bounds = Rect(100, 700, 900, 740)
            onSeekBy = { captured = it }
        }
        SeekBarZones.register(zone)
        try {
            assertTrue("射线落在进度条上应消费按键", SeekBarZones.seekByAt(500f, 720f, -5_000))
            assertEquals(-5_000L, captured)
            assertTrue(SeekBarZones.seekByAt(120f, 705f, 5_000))
            assertEquals(5_000L, captured)
        } finally {
            SeekBarZones.unregister(zone)
        }
    }

    @Test
    fun rayOutsideZoneLetsFocusTraversalHappen() {
        val zone = SeekBarZones.Zone("test").apply {
            bounds = Rect(100, 700, 900, 740)
            onSeekBy = { error("不该被调用") }
        }
        SeekBarZones.register(zone)
        try {
            assertFalse(SeekBarZones.seekByAt(500f, 300f, -5_000)) // 在别处
            assertFalse(SeekBarZones.seekByAt(950f, 720f, -5_000)) // 超出右边界
        } finally {
            SeekBarZones.unregister(zone)
        }
    }

    @Test
    fun unregisteredZoneIsIgnored() {
        val zone = SeekBarZones.Zone("gone").apply { bounds = Rect(0, 0, 100, 100) }
        SeekBarZones.register(zone)
        SeekBarZones.unregister(zone)
        assertFalse(SeekBarZones.seekByAt(50f, 50f, 5_000))
    }
}
