package com.jokerhub.orzmusic.android.debug

import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme

/**
 * 输入法探针 —— **只在 debug 构建里存在**(注册在 `src/debug/AndroidManifest.xml`)。
 *
 * 它回答一个在电视上很难绕过去的问题:
 * **实体键盘的按键,到底有没有被交给输入法(IME)?**
 *
 * 判据(把 IME 切到中文态,然后用实体键盘敲 `nihao`):
 *
 * | 输入框里出现 | 含义 |
 * |---|---|
 * | `你好` | 实体键走了 IME ⇒ PC 那种输入体验在这种界面上成立 |
 * | `nihao` | 实体键被应用直收 ⇒ IME 没参与 |
 *
 * 背景:Termux 的终端视图属于后者(实测,见 docs/09-tv-remote-dev.md),
 * 但 Termux 那是它自己的设计。**普通的 EditText/TextField 是否也这样,
 * 取决于固件有没有保留 AOSP 的这条路** —— 所以需要一个干净的原生对照。
 *
 * 之所以做成独立 Activity 而不是塞进 DeviceInspector:那个界面是 D-pad
 * 焦点演示,焦点元素的数量本身就是被测对象,不能被多出来的输入框污染。
 *
 * 启动方式:
 * ```
 * adb shell am start -n com.jokerhub.orzmusic.android/.debug.ImeProbeActivity
 * ```
 */
class ImeProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { ImeProbeScreen() }
        }
    }
}

@Composable
private fun ImeProbeScreen() {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    val curIme = remember {
        runCatching {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        }.getOrNull().orEmpty().ifEmpty { "(读不到)" }
    }
    val enabled = remember {
        runCatching {
            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .enabledInputMethodList.joinToString("  |  ") { it.id }
        }.getOrNull().orEmpty().ifEmpty { "(读不到)" }
    }

    // 进界面就把焦点给输入框 —— 省掉"先点一下"这一步
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F16))
            .padding(horizontal = 48.dp, vertical = 32.dp)
    ) {
        Text(
            text = "输入法探针",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF7FD4FF)
        )
        Text(
            text = "把 IME 切到中文,然后用实体键盘敲 nihao",
            fontSize = 14.sp,
            color = Color(0xFF8FA3BF)
        )
        Text(text = "当前 IME: $curIme", fontSize = 12.sp, color = Color(0xFF8FA3BF))
        Text(text = "已启用: $enabled", fontSize = 12.sp, color = Color(0xFF8FA3BF))

        BasicTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .focusRequester(focusRequester)
                .testTag("ime-probe-input")
                .background(Color(0xFF101826))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            textStyle = TextStyle(color = Color.White, fontSize = 24.sp),
            decorationBox = { inner ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (text.isEmpty()) {
                        Text(
                            text = "（点这里聚焦,然后打字）",
                            fontSize = 20.sp,
                            color = Color(0xFF5C6B80)
                        )
                    }
                    inner()
                }
            }
        )

        Text(
            text = "已收到 ${text.length} 个字符",
            fontSize = 14.sp,
            color = Color(0xFF8FA3BF),
            modifier = Modifier.padding(top = 10.dp)
        )
    }
}
