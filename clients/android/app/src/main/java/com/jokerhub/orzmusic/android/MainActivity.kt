package com.jokerhub.orzmusic.android

import android.Manifest
import android.content.pm.PackageManager
import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import com.jokerhub.orzmusic.android.ui.AppRoot
import com.jokerhub.orzmusic.android.ui.OrzTheme
import com.jokerhub.orzmusic.android.ui.WindowFocusEvents

/** Pico 射线/指针最后落点（Activity 窗口坐标）。 */
/**
 * 射线/鼠标在窗口内的最新落点。
 *
 * `dispatchGenericMotionEvent` / `dispatchHoverEvent` 每收到一次 hover 就更新 ——
 * 比让每个控件自己从 Compose 指针流里捞位置可靠：Pico 的扳机是「先 hover 再发
 * DPAD_CENTER」（见 [MainActivity.confirmAtPointer]），控件在处理按键时读这里
 * 就知道「射线现在指着我的哪个位置」。
 */
internal object RayPointer {
    @Volatile
    var x: Float = -1f
        private set

    @Volatile
    var y: Float = -1f
        private set

    val valid: Boolean get() = x >= 0f && y >= 0f

    fun update(nx: Float, ny: Float) {
        if (nx.isFinite() && ny.isFinite()) {
            x = nx
            y = ny
        }
    }

    /** 落点换算成横向控件内部的 0..1 比例；没 hover 过或落在控件外返回 null。 */
    fun fractionX(leftPx: Float, widthPx: Float): Float? {
        if (!valid || widthPx <= 0f) return null
        val f = (x - leftPx) / widthPx
        return if (f.isFinite() && f >= 0f && f <= 1f) f else null
    }
}

class MainActivity : ComponentActivity() {

    private val isPico = Build.MANUFACTURER.equals("Pico", ignoreCase = true) ||
        Build.MODEL.contains("A8110", ignoreCase = true)

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 拒绝也不阻塞播放；只是没有通知栏图标 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 深色应用:系统栏用深色背景 + 浅色图标,避免 edge-to-edge 下图标看不见
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            OrzTheme {
                AppRoot()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) WindowFocusEvents.notifyFocus()
    }

    /** 记录 Pico 射线/触摸最后落点，供确认键做 hover + DPAD_CENTER。 */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        RayPointer.update(ev.x, ev.y)
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Pico 上系统 BACK 会同时触发 VRSHELL 的面板焦点回收，导致 Compose
     * 收不到下一次按键。这里在 Activity 层先消费 BACK，交给
     * OnBackPressedDispatcher / Compose BackHandler。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Pico / 手柄的扳机、面键、肩键可能落在任意 BUTTON_* 上。
        // 只要是“确认类”按键，就激活指针落点下的控件；B 留给返回。
        val gamepadSource = (event.source and InputDevice.SOURCE_GAMEPAD) != 0 ||
            (event.source and InputDevice.SOURCE_JOYSTICK) != 0
        val gamepadConfirm = (
            event.keyCode in KeyEvent.KEYCODE_BUTTON_A..KeyEvent.KEYCODE_BUTTON_MODE &&
                event.keyCode != KeyEvent.KEYCODE_BUTTON_B
            ) || event.keyCode in KeyEvent.KEYCODE_BUTTON_1..KeyEvent.KEYCODE_BUTTON_16 ||
            // Pico 的扳机在 Generic.kl 里没有映射，可能以 KEYCODE_UNKNOWN 送达。
            (gamepadSource && event.keyCode == KeyEvent.KEYCODE_UNKNOWN)
        if (gamepadConfirm) {
            if (event.action == KeyEvent.ACTION_DOWN) confirmAtPointer()
            return true
        }
        when (event.keyCode) {
            // 射线指着进度条时，左右键直接 ±5s（不依赖 Compose 焦点，见 SeekBarZones）。
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN && RayPointer.valid &&
                    SeekBarZones.seekByDirectionKey(event.keyCode, RayPointer.x, RayPointer.y)
                ) {
                    Log.i("OrzSeek", "key seek ${event.keyCode} at (${RayPointer.x},${RayPointer.y})")
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
            // Pico 扳机有时就是 ENTER（source 带 GAMEPAD）；只在这个来源下映射，
            // 否则会抢走系统输入法/外接键盘的 Enter 键。
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (!gamepadSource) return super.dispatchKeyEvent(event)
                if (event.action == KeyEvent.ACTION_DOWN) confirmAtPointer()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                // Pico 的 VRSHELL 会抢 BACK，必须在 Activity 先消费；
                // TV 摇控器/系统 IME 需要系统默认的 BACK 分发，不能在这里拦。
                if (!isPico) return super.dispatchKeyEvent(event)
                if (event.action == KeyEvent.ACTION_UP) onBackPressedDispatcher.onBackPressed()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_B -> {
                if (event.action == KeyEvent.ACTION_DOWN) onBackPressedDispatcher.onBackPressed()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 部分 Pico 手柄把摇杆作为 SOURCE_JOYSTICK 的 ABS_X/Y 送进来，
     * 不会自动生成 DPAD 焦点事件。这里在 App 层做一次死区/冷却映射。
     */
    private var lastStickNavAt = 0L
    private var lastTriggerAt = 0L

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_JOYSTICK) == 0) {
            RayPointer.update(event.x, event.y)
            return super.dispatchGenericMotionEvent(event)
        }
        val now = SystemClock.uptimeMillis()
        // 模拟扳机 / 数字扳机：有些 Pico 固件只给轴值，不给 BUTTON_A。
        val trigger = maxOf(
            event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_GAS)
        )
        if (trigger > 0.65f) {
            if (now - lastTriggerAt > 320L) {
                lastTriggerAt = now
                confirmAtPointer()
                return true
            }
            return false
        }
        if (now - lastStickNavAt < 220L) return true
        val x = event.getAxisValue(MotionEvent.AXIS_X) + event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val y = event.getAxisValue(MotionEvent.AXIS_Y) + event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val key = when {
            kotlin.math.abs(x) < 0.62f && kotlin.math.abs(y) < 0.62f -> null
            kotlin.math.abs(x) > kotlin.math.abs(y) ->
                if (x > 0f) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
            else -> if (y > 0f) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
        }
        if (key != null) {
            lastStickNavAt = now
            // ⚠️ 这里必须自己做 seek 判定：下面 sendTapKey 直接调 super.dispatchKeyEvent，
            // 会绕过 dispatchKeyEvent 里「射线指着进度条 → ±5s」那条分支。Pico 的十字键
            // 正是以摇杆轴送进来的，所以左右键调进度一直不生效。
            if (RayPointer.valid &&
                SeekBarZones.seekByDirectionKey(key, RayPointer.x, RayPointer.y)
            ) {
                Log.i("OrzSeek", "stick key seek $key at (${RayPointer.x},${RayPointer.y})")
                return true
            }
            sendTapKey(key)
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    /**
     * 射线落点确认：先合成 hover 把 Compose 焦点移到控件，再发 DPAD_CENTER。
     * 没有落点（纯 D-pad）时直接确认当前焦点。
     *
     * 不能直接合成 ACTION_DOWN/UP：在 Pico 的 pvr-virtual-input 上实测
     * dispatch 返回 true，但 Compose 的 clickable 不会触发 onClick。
     */
    private fun confirmAtPointer() {
        if (!RayPointer.valid) {
            sendTapKey(KeyEvent.KEYCODE_DPAD_CENTER)
            return
        }
        val x = RayPointer.x
        val y = RayPointer.y
        val now = SystemClock.uptimeMillis()
        // 先发一次 hover，让 focusOnHover 把 Compose 焦点移到指针下的控件；
        // 再发 DPAD_CENTER 激活它。直接合成 DOWN/UP 在 Pico 的虚拟触摸屏上
        // 会被 Compose 的点击检测丢掉（实测 handled=true 但不触发 onClick）。
        val hover = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, x, y, 0)
        hover.source = InputDevice.SOURCE_MOUSE
        try {
            super.dispatchGenericMotionEvent(hover)
        } finally {
            hover.recycle()
        }
        window.decorView.postDelayed({
            sendTapKey(KeyEvent.KEYCODE_DPAD_CENTER)
        }, 80L)
    }

    // `super.dispatchKeyEvent` 被 lint 标记 RestrictedApi（只有 androidx 内部能用）。
    // 这里必须用它：合成的 DPAD_CENTER 要按正常的 activity 按键分发走下去，
    // `window.decorView.dispatchKeyEvent` 会绕过 IME/焦点链路，实测无效。
    @SuppressLint("RestrictedApi")
    private fun sendTapKey(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        super.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        super.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }
}
