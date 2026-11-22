package com.jokerhub.orzmusic.android

import android.graphics.Rect
import android.view.KeyEvent
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 「进度条在窗口里的位置」→ 射线落在上面时，左右键直接调整进度。
 *
 * 为什么要绕到 Activity 一层：Compose 的方向键只能发给**当前焦点**节点，而进度条
 * 的焦点来自射线 hover（`focusOnHover`）。真机上实测「射线指着进度条 + 按左右键」
 * 时进度条并没有拿到焦点（logcat 里连 `focus@` 都没有），所以左右键要么在移动焦点、
 * 要么被别的控件吃掉，体感就是「左右键调进度不工作」。
 *
 * 这里沿用 `confirmAtPointer()` 的思路：按键先问「射线现在是不是指着某个进度条」，
 * 是就直接 ±5s 并消费掉按键，否则交回 Compose 做正常的焦点移动。
 */
internal object SeekBarZones {

    class Zone(val tag: String) {
        /** 窗口坐标系里的命中区（比可见轨道高，头显里好按）。 */
        @Volatile
        var bounds: Rect = Rect(0, 0, 0, 0)

        /** 由 Composable 注册的真实回调（`rememberUpdatedState` 包过）。 */
        @Volatile
        var onSeekBy: (Long) -> Unit = {}
    }

    private val zones = CopyOnWriteArrayList<Zone>()

    fun register(zone: Zone) {
        zones.add(zone)
    }

    fun unregister(zone: Zone) {
        zones.remove(zone)
    }

    /** 射线落在某个进度条上时返回该 zone。 */
    fun zoneAt(x: Float, y: Float): Zone? =
        zones.firstOrNull { it.bounds.width() > 0 && it.bounds.contains(x.toInt(), y.toInt()) }

    /** 有进度条在射线落点时，调整进度并返回 true（调用方消费按键）。 */
    fun seekByAt(x: Float, y: Float, deltaMs: Long): Boolean {
        val zone = zoneAt(x, y) ?: return false
        zone.onSeekBy(deltaMs)
        return true
    }

    /**
     * 左右方向键 → ±5s。
     *
     * 两个来源都会走到这里：`dispatchKeyEvent` 里的真·方向键，以及
     * `dispatchGenericMotionEvent` 里把摇杆轴合成出来的方向键（Pico 的十字键就是
     * 后者，而合成时用的是 `sendTapKey`，会绕过按键分发 —— 这是左右键一直不生效的根因）。
     */
    fun seekByDirectionKey(keyCode: Int, x: Float, y: Float): Boolean {
        val delta = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -5_000L
            KeyEvent.KEYCODE_DPAD_RIGHT -> 5_000L
            else -> return false
        }
        return seekByAt(x, y, delta)
    }
}
