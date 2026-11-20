package com.jokerhub.orzmusic.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 少量自绘图标：不引入 material-icons-extended（体积大），
 * 也让 TV / Pico 上的播放控制不依赖字体里的 emoji / 几何字符。
 */
@Composable
fun PlayIcon(modifier: Modifier = Modifier, tint: Color = OrzColors.Text, iconSize: Dp = 16.dp) {
    Canvas(modifier.size(iconSize)) {
        val path = Path().apply {
            moveTo(size.width * 0.26f, size.height * 0.16f)
            lineTo(size.width * 0.84f, size.height * 0.50f)
            lineTo(size.width * 0.26f, size.height * 0.84f)
            close()
        }
        drawPath(path, tint)
    }
}

@Composable
fun PauseIcon(modifier: Modifier = Modifier, tint: Color = OrzColors.Text, iconSize: Dp = 16.dp) {
    Canvas(modifier.size(iconSize)) {
        val barW = this.size.width * 0.22f
        val top = this.size.height * 0.18f
        val h = this.size.height * 0.64f
        drawRoundRect(
            color = tint,
            topLeft = Offset(this.size.width * 0.22f, top),
            size = Size(barW, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2f, barW / 2f)
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(this.size.width * 0.56f, top),
            size = Size(barW, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2f, barW / 2f)
        )
    }
}

@Composable
fun PreviousIcon(modifier: Modifier = Modifier, tint: Color = OrzColors.Text, iconSize: Dp = 16.dp) {
    Canvas(modifier.size(iconSize)) {
        val h = this.size.height
        val w = this.size.width
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.18f, h * 0.18f),
            size = Size(w * 0.10f, h * 0.64f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.05f, w * 0.05f)
        )
        val path = Path().apply {
            moveTo(w * 0.82f, h * 0.18f)
            lineTo(w * 0.36f, h * 0.50f)
            lineTo(w * 0.82f, h * 0.82f)
            close()
        }
        drawPath(path, tint)
    }
}

@Composable
fun NextIcon(modifier: Modifier = Modifier, tint: Color = OrzColors.Text, iconSize: Dp = 16.dp) {
    Canvas(modifier.size(iconSize)) {
        val h = this.size.height
        val w = this.size.width
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.72f, h * 0.18f),
            size = Size(w * 0.10f, h * 0.64f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.05f, w * 0.05f)
        )
        val path = Path().apply {
            moveTo(w * 0.18f, h * 0.18f)
            lineTo(w * 0.64f, h * 0.50f)
            lineTo(w * 0.18f, h * 0.82f)
            close()
        }
        drawPath(path, tint)
    }
}

@Composable
fun SearchIcon(modifier: Modifier = Modifier, tint: Color = OrzColors.Muted, iconSize: Dp = 15.dp) {
    Canvas(modifier.size(iconSize)) {
        val stroke = this.size.minDimension * 0.10f
        drawCircle(
            color = tint,
            radius = this.size.minDimension * 0.30f,
            center = Offset(this.size.width * 0.42f, this.size.height * 0.42f),
            style = Stroke(width = stroke)
        )
        drawLine(
            color = tint,
            start = Offset(this.size.width * 0.64f, this.size.height * 0.64f),
            end = Offset(this.size.width * 0.86f, this.size.height * 0.86f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun QueueIcon(modifier: Modifier = Modifier, tint: Color = OrzColors.Text, iconSize: Dp = 15.dp) {
    Canvas(modifier.size(iconSize)) {
        val stroke = this.size.minDimension * 0.10f
        val x0 = this.size.width * 0.16f
        val x1 = this.size.width * 0.84f
        listOf(0.28f, 0.50f, 0.72f).forEachIndexed { i, y ->
            drawLine(
                color = tint,
                start = Offset(x0, this.size.height * y),
                end = Offset(if (i == 2) this.size.width * 0.58f else x1, this.size.height * y),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
fun VolumeIcon(
    muted: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = OrzColors.Text,
    iconSize: Dp = 16.dp
) {
    Canvas(modifier.size(iconSize)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.10f, h * 0.38f)
            lineTo(w * 0.30f, h * 0.38f)
            lineTo(w * 0.52f, h * 0.20f)
            lineTo(w * 0.52f, h * 0.80f)
            lineTo(w * 0.30f, h * 0.62f)
            lineTo(w * 0.10f, h * 0.62f)
            close()
        }
        drawPath(path, tint)
        val stroke = w * 0.08f
        if (muted) {
            drawLine(tint, Offset(w * 0.66f, h * 0.34f), Offset(w * 0.90f, h * 0.66f), stroke, StrokeCap.Round)
            drawLine(tint, Offset(w * 0.90f, h * 0.34f), Offset(w * 0.66f, h * 0.66f), stroke, StrokeCap.Round)
        } else {
            drawArc(
                color = tint,
                startAngle = -55f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = Offset(w * 0.48f, h * 0.28f),
                size = Size(w * 0.36f, h * 0.44f),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = tint,
                startAngle = -50f,
                sweepAngle = 100f,
                useCenter = false,
                topLeft = Offset(w * 0.54f, h * 0.16f),
                size = Size(w * 0.44f, h * 0.68f),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
fun BackIcon(modifier: Modifier = Modifier, tint: Color = OrzColors.Text, iconSize: Dp = 14.dp) {
    Canvas(modifier.size(iconSize)) {
        val path = Path().apply {
            moveTo(size.width * 0.62f, size.height * 0.18f)
            lineTo(size.width * 0.30f, size.height * 0.50f)
            lineTo(size.width * 0.62f, size.height * 0.82f)
        }
        drawPath(path, tint, style = Stroke(width = iconSize.toPx() * 0.11f, cap = StrokeCap.Round))
    }
}

@Composable
fun CloseIcon(modifier: Modifier = Modifier, tint: Color = OrzColors.Text, iconSize: Dp = 13.dp) {
    Canvas(modifier.size(iconSize)) {
        val stroke = iconSize.toPx() * 0.11f
        drawLine(tint, Offset(iconSize.toPx() * 0.22f, iconSize.toPx() * 0.22f), Offset(iconSize.toPx() * 0.78f, iconSize.toPx() * 0.78f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(iconSize.toPx() * 0.78f, iconSize.toPx() * 0.22f), Offset(iconSize.toPx() * 0.22f, iconSize.toPx() * 0.78f), stroke, StrokeCap.Round)
    }
}
