package com.jokerhub.orzmusic.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * 与 Web 端 `Resources/Public/audio/app.css` 对齐的深色配色。
 *
 * 不自定义 tv-material3 的 ColorScheme（它的参数集和 Material3 不完全一样），
 * 直接用显式颜色常量，避免版本升级时编译不过。
 */
object OrzColors {
    val Bg = Color(0xFF0B0D0C)
    val BgTop = Color(0xFF0E1411)
    val BgBottom = Color(0xFF090B0A)
    val Surface = Color(0xFF101311)
    val SurfaceHigh = Color(0xFF151917)
    val SurfaceHover = Color(0xFF1A211C)
    val SurfaceHighlight = Color(0xFF171E19)
    val Line = Color(0xFF242A26)
    val LineStrong = Color(0xFF3A413D)
    val Mint = Color(0xFF39E58C)
    val MintDim = Color(0xFF2E6B55)
    val MintSoft = Color(0x1F39E58C)
    val Text = Color(0xFFF4F2EA)
    val Muted = Color(0xFF89918B)
    val MutedDim = Color(0xFF626A65)
    val Warm = Color(0xFFF4F2EA)
    val Danger = Color(0xFFFF6B6B)
    val Blue = Color(0xFF8BE9FD)
    val Amber = Color(0xFFFBBF24)
    val Gray = Color(0xFFA1A1AA)
}

@Composable
fun OrzTheme(content: @Composable () -> Unit) {
    // 把 primary 设为品牌薄荷绿：tv-material3 的 Button / 默认焦点色都从它派生，
    // 否则会落到默认的橙色/紫色，与 Web 端不一致。
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = OrzColors.Mint,
            onPrimary = OrzColors.Bg,
            secondary = OrzColors.MintDim,
            onSecondary = OrzColors.Text,
            background = OrzColors.Bg,
            onBackground = OrzColors.Text,
            surface = OrzColors.Surface,
            onSurface = OrzColors.Text,
            surfaceVariant = OrzColors.SurfaceHigh,
            onSurfaceVariant = OrzColors.Muted,
            error = OrzColors.Danger,
            onError = OrzColors.Bg
        ),
        content = content
    )
}
