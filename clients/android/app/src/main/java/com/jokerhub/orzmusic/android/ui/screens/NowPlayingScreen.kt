package com.jokerhub.orzmusic.android.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.jokerhub.orzmusic.android.ui.BrandBadge
import com.jokerhub.orzmusic.android.ui.FormatTag
import com.jokerhub.orzmusic.android.ui.OrzButton
import com.jokerhub.orzmusic.android.ui.OrzRoundButton
import com.jokerhub.orzmusic.android.ui.PlayIcon
import com.jokerhub.orzmusic.android.ui.PauseIcon
import com.jokerhub.orzmusic.android.ui.PreviousIcon
import com.jokerhub.orzmusic.android.ui.NextIcon
import com.jokerhub.orzmusic.android.ui.VolumeIcon
import com.jokerhub.orzmusic.android.ui.BackIcon
import com.jokerhub.orzmusic.android.ui.OrzColors
import com.jokerhub.orzmusic.android.ui.orzButtonColors
import com.jokerhub.orzmusic.android.ui.SeekBar
import com.jokerhub.orzmusic.android.ui.VisualizerBars
import com.jokerhub.orzmusic.android.ui.VolumeSlider
import com.jokerhub.orzmusic.android.ui.formatTime
import com.jokerhub.orzmusic.core.model.PlayStrategy
import com.jokerhub.orzmusic.core.model.Song
import com.jokerhub.orzmusic.core.model.strategy

/**
 * 全屏“正在播放”。网页端的底栏 + 声场面板在这里合并成一页：
 * 进度（点击/拖动/D-pad）、前后 5/15 秒、播放控制、音量、队列入口、装饰声场。
 *
 * 声场只是动画，不读取音频采样，因此不需要 RECORD_AUDIO 权限。
 */
@Composable
fun NowPlayingScreen(
    song: Song,
    isPlaying: Boolean,
    decoding: Boolean,
    notice: String?,
    positionMs: Long,
    durationMs: Long,
    volume: Float,
    levels: FloatArray,
    queueCount: Int,
    serverUrl: String,
    onBack: () -> Unit,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onVolume: (Float) -> Unit,
    onMute: () -> Unit,
    onQueue: () -> Unit,
    onLocate: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val unsupported = song.strategy == PlayStrategy.WasmDecode && !decoding

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(OrzColors.Bg)
            .background(
                Brush.radialGradient(
                    listOf(OrzColors.Mint.copy(alpha = 0.11f), OrzColors.Bg.copy(alpha = 0f)),
                    radius = 1100f
                )
            )
            // 覆盖层不能把点击透给下面的曲库；空白区域也要吃掉事件。
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        // 800dp 平板竖屏用单栏更舒服；900dp 以上（平板横屏/TV/Pico）才双栏。
        val wide = maxWidth >= 900.dp
        val tablet = maxWidth >= 600.dp
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = if (wide) 26.dp else 18.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OrzButton(
                    onClick = onBack,
                    modifier = Modifier.focusRequester(focusRequester)
                ) { Text("返回", fontSize = 12.5.sp) }
                Spacer(Modifier.weight(1f))
                FormatTag(song.fileFormat)
                Spacer(Modifier.width(6.dp))
                OrzButton(onClick = onLocate) { Text("定位", fontSize = 12.5.sp) }
                Spacer(Modifier.width(6.dp))
                OrzButton(onClick = onQueue) { Text("队列 $queueCount", fontSize = 12.5.sp) }
            }

            if (wide) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .widthIn(max = 1080.dp)
                        .align(Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(56.dp)
                ) {
                    NowPlayingArtwork(
                        song = song,
                        levels = levels,
                        isPlaying = isPlaying,
                        modifier = Modifier.weight(0.82f),
                        coverSize = 238.dp
                    )
                    NowPlayingDetails(
                        song = song,
                        isPlaying = isPlaying,
                        decoding = decoding,
                        notice = notice,
                        unsupported = unsupported,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        volume = volume,
                        serverUrl = serverUrl,
                        onToggle = onToggle,
                        onPrevious = onPrevious,
                        onNext = onNext,
                        onSeek = onSeek,
                        onSeekBy = onSeekBy,
                        onVolume = onVolume,
                        onMute = onMute,
                        context = context,
                        compact = false,
                        modifier = Modifier.weight(1.18f)
                    )
                }
            } else {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(10.dp))
                    NowPlayingArtwork(
                        song = song,
                        levels = levels,
                        isPlaying = isPlaying,
                        coverSize = if (tablet) 180.dp else 128.dp
                    )
                    Spacer(Modifier.height(12.dp))
                    NowPlayingDetails(
                        song = song,
                        isPlaying = isPlaying,
                        decoding = decoding,
                        notice = notice,
                        unsupported = unsupported,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        volume = volume,
                        serverUrl = serverUrl,
                        onToggle = onToggle,
                        onPrevious = onPrevious,
                        onNext = onNext,
                        onSeek = onSeek,
                        onSeekBy = onSeekBy,
                        onVolume = onVolume,
                        onMute = onMute,
                        context = context,
                        compact = true,
                        tablet = tablet,
                        modifier = Modifier.fillMaxWidth().widthIn(max = 540.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "OrzMusic · ${serverUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')}",
                        color = OrzColors.MutedDim,
                        fontSize = 10.sp
                    )
                }
            }

            if (wide) {
                Text(
                    text = "OrzMusic · ${serverUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')}",
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
                    color = OrzColors.MutedDim,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun NowPlayingArtwork(
    song: Song,
    levels: FloatArray,
    isPlaying: Boolean,
    coverSize: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(coverSize * 1.35f)
                    .background(
                        Brush.radialGradient(
                            listOf(OrzColors.Mint.copy(alpha = 0.20f), OrzColors.Mint.copy(alpha = 0f))
                        ),
                        androidx.compose.foundation.shape.CircleShape
                    )
            )
            BrandBadge(format = song.fileFormat, size = coverSize)
        }
        Spacer(Modifier.height(if (coverSize > 160.dp) 18.dp else 10.dp))
        VisualizerBars(
            levels = levels,
            active = isPlaying,
            maxBars = if (coverSize > 160.dp) 24 else 16,
            // 头显里 38dp 的柱子太矮,一眼看不出在动;放大到 56dp 仍不抢封面。
            modifier = Modifier.width(coverSize * 0.90f).height(if (coverSize > 160.dp) 56.dp else 34.dp)
        )
    }
}

@Composable
private fun NowPlayingDetails(
    song: Song,
    isPlaying: Boolean,
    decoding: Boolean,
    notice: String?,
    unsupported: Boolean,
    positionMs: Long,
    durationMs: Long,
    volume: Float,
    serverUrl: String,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onVolume: (Float) -> Unit,
    onMute: () -> Unit,
    context: android.content.Context,
    compact: Boolean,
    tablet: Boolean = false,
    modifier: Modifier = Modifier
) {
    val fraction = if (durationMs > 0) positionMs.toFloat() / durationMs.toFloat() else 0f
    Column(
        modifier = modifier,
        horizontalAlignment = if (compact) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(if (compact) 9.dp else 11.dp)
    ) {
        Text(
            text = if (decoding) "正在解码" else "正在播放",
            color = OrzColors.Mint,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.6.sp
        )
        Text(
            text = song.title,
            color = OrzColors.Text,
            fontSize = when {
                !compact -> 32.sp
                tablet -> 28.sp
                else -> 23.sp
            },
            fontWeight = FontWeight.Bold,
            textAlign = if (compact) TextAlign.Center else TextAlign.Start,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${song.artistName} · ${song.durationText} · ${strategyLabel(song)}",
            color = OrzColors.Muted,
            fontSize = if (compact && !tablet) 12.sp else 13.sp,
            textAlign = if (compact) TextAlign.Center else TextAlign.Start
        )

        if (decoding) {
            Text(
                text = "正在解码 ${song.fileFormat.uppercase()}…",
                color = OrzColors.Mint,
                fontSize = 12.5.sp
            )
        } else if (unsupported && notice != null) {
            Text(
                text = notice,
                color = OrzColors.Amber,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 620.dp)
            )
            OrzButton(onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(serverUrl))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }) {
                Text("在网页播放器打开", fontSize = 12.sp)
            }
        }

        if (!decoding) {
            Spacer(Modifier.height(if (compact) 2.dp else 8.dp))
            SeekBar(
                fraction = fraction,
                onSeek = { value -> if (durationMs > 0) onSeek((value * durationMs).toLong()) },
                onSeekBy = onSeekBy,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatTime(positionMs), color = OrzColors.Muted, fontSize = 10.5.sp)
                Text(formatTime(durationMs), color = OrzColors.Muted, fontSize = 10.5.sp)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OrzButton(onClick = { onSeekBy(-15_000) }) { Text("−15s", fontSize = 11.sp) }
                OrzButton(onClick = { onSeekBy(-5_000) }) { Text("−5s", fontSize = 11.sp) }
                OrzButton(onClick = { onSeekBy(5_000) }) { Text("+5s", fontSize = 11.sp) }
                OrzButton(onClick = { onSeekBy(15_000) }) { Text("+15s", fontSize = 11.sp) }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OrzRoundButton(
                    onClick = onPrevious,
                    buttonSize = 38.dp,
                    contentDescription = "上一首"
                ) {
                    PreviousIcon(iconSize = 14.dp, tint = OrzColors.Muted)
                }
                OrzRoundButton(
                    onClick = onToggle,
                    buttonSize = 52.dp,
                    primary = true,
                    contentDescription = if (isPlaying) "暂停" else "播放"
                ) {
                    if (isPlaying) {
                        PauseIcon(iconSize = 20.dp, tint = OrzColors.Bg)
                    } else {
                        PlayIcon(iconSize = 20.dp, tint = OrzColors.Bg)
                    }
                }
                OrzRoundButton(
                    onClick = onNext,
                    buttonSize = 38.dp,
                    contentDescription = "下一首"
                ) {
                    NextIcon(iconSize = 14.dp, tint = OrzColors.Muted)
                }
            }

            Row(
                modifier = Modifier.widthIn(max = 430.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                OrzRoundButton(
                    onClick = onMute,
                    buttonSize = 30.dp,
                    contentDescription = if (volume <= 0f) "取消静音" else "静音"
                ) {
                    VolumeIcon(
                        muted = volume <= 0f,
                        tint = if (volume <= 0f) OrzColors.Mint else OrzColors.Muted,
                        iconSize = 14.dp
                    )
                }
                VolumeSlider(volume = volume, onChange = onVolume, modifier = Modifier.weight(1f))
                Text(
                    text = "${(volume * 100).toInt()}%",
                    modifier = Modifier.width(34.dp),
                    color = OrzColors.Muted,
                    fontSize = 10.sp,
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

private fun strategyLabel(song: Song): String = when (song.strategy) {
    PlayStrategy.DirectFile -> "直接播放"
    PlayStrategy.ServerDecode -> "服务端解码"
    PlayStrategy.WasmDecode -> "原生 / WASM 解码"
}
