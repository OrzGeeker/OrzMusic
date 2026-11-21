package com.jokerhub.orzmusic.android.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import com.jokerhub.orzmusic.android.ui.OrzButton
import com.jokerhub.orzmusic.android.ui.OrzColors
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 开源许可 / 对应源码（GPLv3 §6）。
 *
 * 内容来自 APK assets：`licenses/GPL-3.0.txt` 与 `licenses/THIRD_PARTY_NOTICES.md`
 * （构建时从仓库根拷贝，见 app/build.gradle.kts 的 copyLicenseAssets），
 * 所以不会和仓库里的版本漂移。
 *
 * 只读 + 可滚动：TV 遥控器用方向键滚，手机直接手指滑。
 */
@Composable
fun LicensesDialog(
    context: Context,
    versionName: String,
    onDismiss: () -> Unit
) {
    val notices = remember { readAsset(context, "licenses/THIRD_PARTY_NOTICES.md") }
    val gpl = remember { readAsset(context, "licenses/GPL-3.0.txt") }
    val scroll = rememberScrollState()
    val horizontal = rememberScrollState()

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .background(OrzColors.Surface, RoundedCornerShape(16.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "开源许可 / 对应源码",
                color = OrzColors.Text,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "本应用按 GPL-3.0-or-later 分发（解码库静态链接了 GPL 系解码器）。\n" +
                    "对应源码：https://github.com/OrzGeeker/OrzMusic/tree/android-v$versionName",
                color = OrzColors.Muted,
                fontSize = 11.sp
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 430.dp)
                    .background(OrzColors.SurfaceHigh, RoundedCornerShape(10.dp))
                    .padding(12.dp)
                    .verticalScroll(scroll)
                    .horizontalScroll(horizontal)
            ) {
                Text(
                    text = buildString {
                        append(notices.ifBlank { "（缺少 THIRD_PARTY_NOTICES.md）" })
                        append("\n\n──────── GPL-3.0 全文 ────────\n\n")
                        append(gpl.ifBlank { "（缺少 GPL-3.0.txt）" })
                    },
                    color = OrzColors.Muted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrzButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    }
}

private fun readAsset(context: Context, path: String): String = runCatching {
    context.assets.open(path).use { stream ->
        BufferedReader(InputStreamReader(stream)).readText()
    }
}.getOrDefault("")
