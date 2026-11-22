package com.jokerhub.orzmusic.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.jokerhub.orzmusic.android.RayPointer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * 进度条交互回归。
 *
 * Pico 上没有真实触摸：扳机是「射线 hover 到控件 → 发 DPAD_CENTER」
 * （`MainActivity.confirmAtPointer`）。0.8.3 之前 `SeekBar` 只处理左右键，
 * 头显里就表现为「点不动、拖不动」。这里用 `RayPointer` 直接摆出射线落点，
 * 断言确认键会把落点换算成 0..1 的进度。
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SeekBarInteractionTest {

    @get:Rule
    val rule = createComposeRule()

    private var seeked: Float = -1f
    private var seekCount = 0

    private fun content() {
        rule.setContent {
            Box(Modifier.size(320.dp, 40.dp)) {
                SeekBar(
                    fraction = 0.2f,
                    onSeek = { seeked = it; seekCount++ },
                    modifier = Modifier
                        .size(320.dp, 40.dp)
                        .testTag("seekbar")
                )
            }
        }
    }

    /** 进度条实际宽度（`fillMaxWidth` 会盖掉外部给的大小）。 */
    private fun barWidthPx(): Float =
        rule.onNodeWithTag("seekbar").fetchSemanticsNode().size.width.toFloat()

    @Test
    fun confirmKeySeeksToRayPosition() {
        content()
        val width = barWidthPx()
        RayPointer.update(width * 0.75f, 10f)
        rule.onNodeWithTag("seekbar").confirmWithRay()

        assertTrue("确认键没有触发 seek（seeked=$seeked, width=$width）", seeked >= 0f)
        assertEquals(0.75f, seeked, 0.02f)
    }

    /**
     * 拖动必须只在**松手时** seek 一次：每个 move 都 seek 会堵住主线程，
     * 头显里就是「一卡一卡」（0.8.5 的真机反馈）。
     */
    @Test
    fun dragSeeksOnlyOnceOnRelease() {
        content()
        val width = barWidthPx()
        rule.onNodeWithTag("seekbar").performTouchInput {
            swipe(
                start = Offset(width * 0.2f, height / 2f),
                end = Offset(width * 0.8f, height / 2f),
                durationMillis = 200
            )
        }
        assertEquals("拖动过程中不该反复 seek", 1, seekCount)
        assertEquals(0.8f, seeked, 0.06f)
    }

    @Test
    fun confirmKeyDoesNothingWhenRayWasNeverSeen() {
        content()
        // 没有 hover 过（纯 D-pad 用户）：不能瞎跳，交给左右键。
        rule.onNodeWithTag("seekbar").confirmWithRay()
        assertEquals(-1f, seeked, 0f)
    }

    @Test
    fun rayOutsideTheBarIsIgnored() {
        content()
        RayPointer.update(barWidthPx() + 50f, 20f) // 落在控件之外
        rule.onNodeWithTag("seekbar").confirmWithRay()
        assertEquals(-1f, seeked, 0f)
    }
}

/** 等价于「射线悬停到控件上再按确认键」：先聚焦（hover 会聚焦），再发 CENTER。 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
private fun SemanticsNodeInteraction.confirmWithRay() {
    requestFocus()
    performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionCenter) }
}
