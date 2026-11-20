package com.jokerhub.orzmusic.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text

private val shortcuts = listOf(
    "空格" to "播放 / 暂停",
    "M" to "静音 / 恢复",
    "N / P" to "下一首 / 上一首",
    "L" to "定位当前曲目",
    "Q" to "打开 / 关闭播放队列",
    "/" to "聚焦搜索框",
    "Esc" to "关闭面板 / 清空搜索",
    "D-pad 左右" to "进度条获得焦点时前后 5 秒",
    "D-pad 上下" to "音量条获得焦点时调整音量"
)

@Composable
fun ShortcutsDialog(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(60)
        runCatching { closeFocus.requestFocus() }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.66f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxWidth(0.9f)
                .background(OrzColors.Surface, RoundedCornerShape(18.dp))
                .border(1.dp, OrzColors.Line.copy(alpha = 0.8f), RoundedCornerShape(18.dp))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("轻松操控", color = OrzColors.Mint, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text("键盘 / 遥控快捷键", color = OrzColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                OrzButton(

                    onClick = onClose,
                    modifier = Modifier.focusRequester(closeFocus)
                ) { Text("关闭") }
            }
            shortcuts.forEach { (key, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(OrzColors.SurfaceHigh, RoundedCornerShape(9.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = key,
                        modifier = Modifier.widthIn(min = 100.dp),
                        color = OrzColors.Mint,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(label, color = OrzColors.Muted, fontSize = 12.sp)
                }
            }
            Text(
                "TV 遥控:方向键移动焦点,OK 确认,BACK 关闭面板。",
                color = OrzColors.MutedDim,
                fontSize = 10.5.sp
            )
        }
    }
}
