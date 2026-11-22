package com.jokerhub.orzmusic.android.ui

import android.content.pm.PackageManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import android.util.Log
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import com.jokerhub.orzmusic.android.SeekBarZones
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonColors
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardBorder
import androidx.tv.material3.CardColors
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.CardScale
import androidx.tv.material3.Text
import com.jokerhub.orzmusic.android.RayPointer
import com.jokerhub.orzmusic.android.player.SEEK_TAG
import com.jokerhub.orzmusic.core.model.Song
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * tv-material3 的 Card 默认用 `surfaceVariant`，在本应用的深色底上会发灰。
 * 统一改成透明容器，由内容自己画背景；焦点态仍由 tv-material3 提供。
 */
/** 统一按钮配色：深色底、薄荷焦点，避免默认主题的橙色/紫色。 */
@Composable
fun orzButtonColors(): ButtonColors =
    ButtonDefaults.colors(
        containerColor = OrzColors.SurfaceHigh,
        contentColor = OrzColors.Text,
        focusedContainerColor = OrzColors.Mint,
        focusedContentColor = OrzColors.Bg,
        pressedContainerColor = OrzColors.MintDim,
        pressedContentColor = OrzColors.Text
    )

@Composable
fun orzCardColors(container: Color = Color.Transparent): CardColors =
    CardDefaults.colors(
        containerColor = container,
        contentColor = OrzColors.Text,
        // 焦点只通过薄荷描边表达；不要再用整块背景或缩放强调，避免列表跳动。
        focusedContainerColor = container,
        focusedContentColor = OrzColors.Text
    )

/** 列表卡片禁止 tv-material3 默认的 1.05x 焦点缩放。 */
fun orzCardScale(): CardScale = CardDefaults.scale(
    scale = 1f,
    focusedScale = 1f,
    pressedScale = 1f
)

/** 焦点态统一成薄荷细环，替代 tv-material3 默认的白色描边。 */
@Composable
fun orzCardBorder(shape: Shape = RoundedCornerShape(10.dp)): CardBorder =
    CardDefaults.border(
        focusedBorder = Border(
            border = BorderStroke(1.5.dp, OrzColors.Mint.copy(alpha = 0.72f)),
            inset = 0.dp,
            shape = shape
        )
    )

/**
 * Pico 射线/鼠标悬停到控件时，把 Compose 焦点也移过去。
 *
 * 这样即使手柄扳机最终发的是 DPAD_CENTER（而不是触摸点击），
 * 当前焦点也正好在指针下的歌曲/按钮上，确认键能直接激活。
 */
/** tv-material3 的 Card/Button 在 Pico 触摸下不触发 onClick；补一个纯指针 tap 手势。 */
fun Modifier.tapGesture(onClick: () -> Unit): Modifier =
    pointerInput(onClick) { detectTapGestures { onClick() } }

@Composable
fun Modifier.focusOnHover(focusRequester: FocusRequester): Modifier {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    LaunchedEffect(hovered) {
        if (hovered) runCatching { focusRequester.requestFocus() }
    }
    return this.hoverable(source).focusRequester(focusRequester)
}

/** 统一按钮：悬停即聚焦，并提供 Pointer 下的原生点击。 */
@Composable
fun OrzButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val focus = remember { FocusRequester() }
    Button(
        onClick = onClick,
        modifier = modifier.tapGesture(onClick).focusOnHover(focus),
        colors = orzButtonColors(),
        content = content
    )
}

/** 圆形图标按钮：PlayerDock / 正在播放页的播放控制。 */
@Composable
fun OrzRoundButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 36.dp,
    primary: Boolean = false,
    contentDescription: String? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val background = when {
        primary -> OrzColors.Mint
        focused -> OrzColors.SurfaceHover
        else -> OrzColors.SurfaceHigh
    }
    Box(
        modifier = modifier
            .size(buttonSize)
            .clip(CircleShape)
            .background(background)
            .border(
                width = if (focused && !primary) 1.5.dp else 1.dp,
                color = when {
                    focused -> OrzColors.Mint
                    primary -> Color.Transparent
                    else -> OrzColors.Line
                },
                shape = CircleShape
            )
            .focusOnHover(focus)
            .semantics {
                if (contentDescription != null) this.contentDescription = contentDescription
            }
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (event.key == Key.DirectionCenter || event.key == Key.Enter) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/** 声场面板的展开/收起按钮：Web `.viz-toggle` / `.viz-expand-btn` 的 Android 版。 */
@Composable
fun VizToggleButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .height(26.dp)
            .background(
                if (focused) OrzColors.MintSoft else OrzColors.SurfaceHigh,
                RoundedCornerShape(8.dp)
            )
            .border(
                1.dp,
                if (focused) OrzColors.Mint.copy(alpha = 0.65f) else OrzColors.Line,
                RoundedCornerShape(8.dp)
            )
            .focusOnHover(focus)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (event.key == Key.DirectionCenter || event.key == Key.Enter) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (focused) OrzColors.Mint else OrzColors.Muted,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/** 品牌波形标记（Web 端 logo 的极简化）。 */
@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        listOf(7, 15, 22, 11, 18).forEach { h ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(h.dp)
                    .background(OrzColors.Mint, RoundedCornerShape(2.dp))
            )
        }
    }
}

/** 格式徽标：后端没有专辑封面，用带格式色的 badge 代替。 */
@Composable
fun BrandBadge(
    format: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp
) {
    val meta = FormatCatalog.meta(format)
    Box(
        modifier = modifier
            .size(size)
            .background(
                brush = Brush.linearGradient(
                    listOf(meta.color.copy(alpha = 0.95f), meta.color.copy(alpha = 0.55f), OrzColors.SurfaceHigh)
                ),
                shape = RoundedCornerShape(if (size >= 80.dp) 24.dp else 12.dp)
            )
            .border(
                width = if (size >= 80.dp) 1.5.dp else 1.dp,
                color = meta.color.copy(alpha = 0.30f),
                shape = RoundedCornerShape(if (size >= 80.dp) 24.dp else 12.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = meta.label.take(4).uppercase(),
            color = OrzColors.Bg,
            fontSize = when {
                size >= 160.dp -> 30.sp
                size >= 80.dp -> 18.sp
                else -> 9.sp
            },
            fontWeight = FontWeight.Black,
            maxLines = 1
        )
    }
}

@Composable
fun FormatTag(format: String, modifier: Modifier = Modifier) {
    val meta = FormatCatalog.meta(format)
    Text(
        text = meta.label.uppercase(),
        modifier = modifier
            .background(meta.color.copy(alpha = 0.11f), RoundedCornerShape(6.dp))
            .border(1.dp, meta.color.copy(alpha = 0.24f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 4.dp),
        color = meta.color,
        fontSize = 9.5.sp,
        fontWeight = FontWeight.Black,
        maxLines = 1
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        color = OrzColors.MutedDim,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp
    )
}

/**
 * 歌曲行。信息层级对齐网页端表格：标题 / 艺术家 / 专辑 / 格式 / 大小 / 时长。
 * 窄屏只保留标题、艺术家、格式、时长，避免横向滚动。
 */
@Composable
fun SongRow(
    song: Song,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showArtist: Boolean = true,
    showAlbum: Boolean = false,
    showSize: Boolean = false
) {
    val rowFocus = remember { FocusRequester() }
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(onClick) { detectTapGestures { onClick() } }
            .focusOnHover(rowFocus),
        colors = orzCardColors(),
        scale = orzCardScale(),
        border = orzCardBorder(RoundedCornerShape(10.dp))
    ) {
        Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isCurrent) OrzColors.MintSoft else Color.Transparent)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.width(30.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isCurrent && isPlaying) {
                    PlayBars()
                } else {
                    if (isCurrent) {
                        Text("●", color = OrzColors.Mint, fontSize = 9.sp)
                    } else {
                        PlayIcon(iconSize = 9.dp, tint = OrzColors.MutedDim)
                    }
                }
            }
            BrandBadge(song.fileFormat, size = 38.dp)
            Column(
                modifier = Modifier
                    .weight(2f)
                    .padding(start = 12.dp)
            ) {
                Text(
                    text = song.title,
                    color = if (isCurrent) OrzColors.Mint else OrzColors.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (showArtist) {
                        song.album?.title?.takeIf { it.isNotBlank() } ?: song.artistName
                    } else {
                        song.artistName
                    },
                    color = OrzColors.Muted,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (showArtist) {
                Text(
                    text = song.artistName,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 14.dp),
                    color = OrzColors.Muted,
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (showAlbum) {
                Text(
                    text = song.album?.title?.takeIf { it.isNotBlank() } ?: "—",
                    modifier = Modifier
                        .weight(1.1f)
                        .padding(start = 14.dp),
                    color = OrzColors.Muted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            FormatTag(song.fileFormat, Modifier.padding(start = 12.dp))
            if (showSize) {
                Text(
                    text = song.sizeText,
                    modifier = Modifier
                        .width(70.dp)
                        .padding(start = 12.dp),
                    color = OrzColors.Muted,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
            Text(
                text = song.durationText,
                modifier = Modifier
                    .width(48.dp)
                    .padding(start = 10.dp),
                color = OrzColors.Muted,
                fontSize = 11.sp,
                maxLines = 1
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(OrzColors.Line.copy(alpha = 0.55f))
        )
        }
    }
}

@Composable
fun SongTableHeader(
    showAlbum: Boolean,
    showSize: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(30.dp))
        Spacer(Modifier.width(38.dp))
        Text("标题", Modifier.weight(2f).padding(start = 12.dp), color = OrzColors.MutedDim, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
        Text("艺术家", Modifier.weight(1f).padding(start = 14.dp), color = OrzColors.MutedDim, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
        if (showAlbum) {
            Text("专辑", Modifier.weight(1.1f).padding(start = 14.dp), color = OrzColors.MutedDim, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
        }
        Text("格式", Modifier.padding(start = 12.dp), color = OrzColors.MutedDim, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
        if (showSize) {
            Text(
                "大小",
                Modifier.width(70.dp).padding(start = 12.dp),
                color = OrzColors.MutedDim,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            "时长",
            Modifier.width(48.dp).padding(start = 10.dp),
            color = OrzColors.MutedDim,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun PlayBars(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        listOf(5, 9, 6, 11, 7).forEach { h ->
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(h.dp)
                    .background(OrzColors.Mint, CircleShape)
            )
        }
    }
}

@Composable
fun ArtistRow(
    name: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false
) {
    val rowFocus = remember { FocusRequester() }
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().tapGesture(onClick).focusOnHover(rowFocus),
        colors = orzCardColors(),
        scale = orzCardScale(),
        border = orzCardBorder(RoundedCornerShape(10.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (selected) OrzColors.MintSoft else Color.Transparent)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(OrzColors.SurfaceHigh, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = name.take(1).uppercase(),
                    color = OrzColors.Mint,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Text(
                    text = name,
                    color = if (selected) OrzColors.Mint else OrzColors.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = OrzColors.Muted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text("›", color = OrzColors.MutedDim, fontSize = 20.sp)
        }
    }
}

@Composable
fun NavItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: String? = null
) {
    val rowFocus = remember { FocusRequester() }
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().tapGesture(onClick).focusOnHover(rowFocus),
        colors = orzCardColors(),
        scale = orzCardScale(),
        border = orzCardBorder(RoundedCornerShape(10.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (selected) OrzColors.MintSoft else Color.Transparent,
                    RoundedCornerShape(10.dp)
                )
                .padding(horizontal = 11.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(17.dp)
                    .background(if (selected) OrzColors.Mint else Color.Transparent, CircleShape)
            )
            Text(
                text = label,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
                color = if (selected) OrzColors.Text else OrzColors.Muted,
                fontSize = 13.5.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1
            )
            if (trailing != null) {
                Text(trailing, color = if (selected) OrzColors.Mint else OrzColors.MutedDim, fontSize = 10.5.sp)
            }
        }
    }
}

@Composable
fun FormatNavItem(
    meta: FormatMeta,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rowFocus = remember { FocusRequester() }
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().tapGesture(onClick).focusOnHover(rowFocus),
        colors = orzCardColors(),
        scale = orzCardScale(),
        border = orzCardBorder(RoundedCornerShape(9.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (selected) OrzColors.MintSoft else Color.Transparent,
                    RoundedCornerShape(9.dp)
                )
                .padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .background(meta.color, CircleShape)
            )
            Text(
                text = meta.label,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
                color = if (selected) OrzColors.Text else OrzColors.Muted,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1
            )
            Text(
                text = meta.count.toString(),
                color = if (selected) meta.color else OrzColors.MutedDim,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
fun FormatChip(
    label: String,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit,
    color: Color = OrzColors.Mint
) {
    val chipFocus = remember { FocusRequester() }
    Card(
        onClick = onClick,
        modifier = Modifier.tapGesture(onClick).focusOnHover(chipFocus),
        colors = orzCardColors(),
        scale = orzCardScale(),
        border = orzCardBorder(RoundedCornerShape(10.dp))
    ) {
        Row(
            modifier = Modifier
                .background(if (selected) color.copy(alpha = 0.14f) else Color.Transparent, RoundedCornerShape(10.dp))
                .border(1.dp, if (selected) color.copy(alpha = 0.45f) else OrzColors.Line, RoundedCornerShape(10.dp))
                .padding(horizontal = 11.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = label.uppercase(),
                color = if (selected) color else OrzColors.Muted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            if (count != null) {
                Text(text = count.toString(), color = OrzColors.MutedDim, fontSize = 9.sp)
            }
        }
    }
}

/** 顶栏搜索框；对 TV 先把焦点落进去，再让系统弹输入法。 */
@Composable
fun SearchField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val view = LocalView.current
    val isTelevision = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
    }
    // TV 遥控器：搜索框在“浏览态”只做展示，方向键正常走焦点；
    // 按 OK / 点击后才进入编辑态并拉起系统 IME。
    var editing by remember { mutableStateOf(false) }
    var restoreFocusAfterEdit by remember { mutableStateOf(false) }
    val fieldFocus = focusRequester ?: remember { FocusRequester() }

    LaunchedEffect(isTelevision, editing) {
        if (!isTelevision) return@LaunchedEffect
        if (editing) {
            delay(80)
            runCatching { fieldFocus.requestFocus() }
            runCatching { keyboard?.show() }
            delay(500)
            while (isActive) {
                delay(200)
                val imeVisible = ViewCompat.getRootWindowInsets(view)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                if (!imeVisible) {
                    restoreFocusAfterEdit = true
                    editing = false
                    break
                }
            }
        } else {
            runCatching { keyboard?.hide() }
        }
    }

    LaunchedEffect(editing) {
        if (isTelevision && !editing && restoreFocusAfterEdit) {
            delay(60)
            restoreFocusAfterEdit = false
            runCatching { fieldFocus.requestFocus() }
        }
    }

    if (isTelevision && !editing) {
        TvSearchCard(
            value = value,
            placeholder = placeholder,
            onClick = { editing = true },
            modifier = modifier,
            focusRequester = fieldFocus
        )
        return
    }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        interactionSource = interaction,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
            onSearch = {
                runCatching { keyboard?.hide() }
                focusManager.clearFocus(force = true)
            }
        ),
        textStyle = TextStyle(color = OrzColors.Text, fontSize = 13.sp),
        cursorBrush = SolidColor(OrzColors.Mint),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { state ->
                if (!isTelevision && state.isFocused) runCatching { keyboard?.show() }
            }
            .focusRequester(fieldFocus),
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(OrzColors.SurfaceHigh, RoundedCornerShape(11.dp))
                    .border(
                        1.dp,
                        if (focused) OrzColors.Mint.copy(alpha = 0.60f) else OrzColors.Line,
                        RoundedCornerShape(11.dp)
                    )
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SearchIcon(iconSize = 15.dp)
                Box(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    if (value.isEmpty()) {
                        Text(placeholder, color = OrzColors.MutedDim, fontSize = 12.5.sp, maxLines = 1)
                    }
                    inner()
                }
                if (value.isNotEmpty()) {
                    Box(
                        modifier = Modifier.clickable { onValueChange("") }.padding(5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CloseIcon(iconSize = 11.dp, tint = OrzColors.Muted)
                    }
                }
            }
        }
    )
}

/** TV 浏览态的搜索展示卡：D-pad 可以正常移入/移出，不会自动弹 IME。 */
@Composable
private fun TvSearchCard(
    value: String,
    placeholder: String,
    onClick: () -> Unit,
    modifier: Modifier,
    focusRequester: FocusRequester
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .tapGesture(onClick)
            .focusOnHover(focusRequester),
        colors = orzCardColors(OrzColors.SurfaceHigh),
        scale = orzCardScale(),
        border = orzCardBorder(RoundedCornerShape(11.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SearchIcon(iconSize = 15.dp)
            Text(
                text = value.ifEmpty { placeholder },
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                color = if (value.isEmpty()) OrzColors.MutedDim else OrzColors.Text,
                fontSize = 12.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text("OK", color = OrzColors.MutedDim, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** 无 material3 依赖的进度条。 */
@Composable
fun ProgressTrack(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
    color: Color = OrzColors.Mint
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(OrzColors.Line, CircleShape)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(height)
                .background(color, CircleShape)
        )
    }
}

/**
 * 可点击 / 可拖动 / 可用 D-pad 左右键 seek 的进度条。
 * Web 端是 `pointermove` 预览 + 点击 seek；Android 上同时照顾触屏与遥控。
 *
 * Pico 上没有真正的触摸：扳机是「射线 hover 到这里 → 发 DPAD_CENTER」（见
 * `MainActivity.confirmAtPointer`），而 `detectTapGestures` 只认真实的按下事件，
 * 所以**必须**把 DPAD_CENTER 也当成「跳到射线落点」。0.8.2 之前只处理了
 * DirectionLeft/Right，头显里就表现为「进度条点不动、也没法 seek」。
 */
@Composable
fun SeekBar(
    fraction: Float,
    onSeek: (Float) -> Unit,
    onSeekBy: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** 只用于日志区分是哪个进度条（`dock` / `nowplaying`）。 */
    tag: String = "seekbar"
) {
    val focus = remember { FocusRequester() }
    // 射线/鼠标的横向落点（0..1）。-1 表示这次没有任何指针悬停过，
    // 纯 D-pad 用户据此退回只看左右键。
    var pointerFraction by remember { mutableFloatStateOf(-1f) }
    // 控件在窗口里的位置：射线坐标是窗口坐标系，要换算成控件内部比例。
    var barLeft by remember { mutableFloatStateOf(0f) }
    var barWidth by remember { mutableFloatStateOf(0f) }
    // 拖动/点击后的本地预览位置：先跟手画，等播放器真追上来再交回去。
    var prefersFocus by remember { mutableStateOf(false) }
    // 把命中区注册给 Activity：射线指着进度条时左右键就能 ±5s（不依赖 Compose 焦点）。
    val zone = remember(tag) { SeekBarZones.Zone(tag) }
    SideEffect { zone.onSeekBy = onSeekBy }
    DisposableEffect(zone) {
        SeekBarZones.register(zone)
        onDispose { SeekBarZones.unregister(zone) }
    }
    var previewFraction by remember { mutableFloatStateOf(-1f) }
    val shownFraction = if (previewFraction >= 0f) previewFraction else fraction
    // 播放器位置追上来了（或 1.2s 都没追上）就把预览交回给真实位置。
    LaunchedEffect(previewFraction, fraction) {
        if (previewFraction < 0f) return@LaunchedEffect
        if (kotlin.math.abs(fraction - previewFraction) < 0.02f) {
            previewFraction = -1f
        } else {
            kotlinx.coroutines.delay(1_200)
            previewFraction = -1f
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .onGloballyPositioned { coords ->
                val b = coords.positionInWindow()
                val w = coords.size.width.toFloat()
                val h = coords.size.height.toFloat()
                barLeft = b.x
                barWidth = w
                zone.bounds = android.graphics.Rect(
                    b.x.toInt(),
                    b.y.toInt(),
                    (b.x + w).toInt(),
                    (b.y + h).toInt()
                )
            }
            .focusOnHover(focus)
            .onFocusChanged { st ->
                prefersFocus = st.isFocused
                if (st.isFocused) Log.i(SEEK_TAG, "focus@$tag")
            }
            // ⚠️ onKeyEvent 必须在 focusable 之前：Compose 把按键冒泡给
            // 「焦点节点的祖先 modifier」，反过来写（focusable().onKeyEvent{}）
            // 处理器在焦点节点内部，收不到任何按键 —— 0.8.2 的左右键 ±5s 就是这样失效的。
            .onKeyEvent { event ->
                // 左右键只在「进度条自己有焦点」时才调整；没焦点时按键要留给
                // Compose 做焦点移动，否则整个界面的方向键导航会坏掉。
                Log.i(SEEK_TAG, "key@$tag ${event.key} type=${event.type}")
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        Log.i(SEEK_TAG, "seekBy@$tag -5000")
                        onSeekBy(-5_000)
                        true
                    }
                    Key.DirectionRight -> {
                        Log.i(SEEK_TAG, "seekBy@$tag +5000")
                        onSeekBy(5_000)
                        true
                    }
                    // 射线指到哪，确认就跳到哪。优先读 RayPointer（窗口坐标，
                    // hover 一发生就有），退回本控件自己跟踪的指针比例。
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        val target = RayPointer.fractionX(barLeft, barWidth)
                            ?: pointerFraction.takeIf { it >= 0f }
                        if (target == null) {
                            Log.i(SEEK_TAG, "confirm on seekbar ignored: no ray position")
                            false
                        } else {
                            Log.i(SEEK_TAG, "confirm on seekbar -> fraction=$target")
                            onSeek(target)
                            true
                        }
                    }
                    else -> false
                }
            }
            .focusable()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press ||
                            event.type == PointerEventType.Release
                        ) {
                            Log.i(SEEK_TAG, "pointer@$tag ${event.type}")
                            continue
                        }
                        if (event.type != PointerEventType.Move &&
                            event.type != PointerEventType.Enter
                        ) continue
                        val x = event.changes.firstOrNull()?.position?.x ?: continue
                        val width = size.width
                        if (width > 0) {
                            pointerFraction = (x / width.toFloat()).coerceIn(0f, 1f)
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val f = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    Log.i(SEEK_TAG, "tap@$tag fraction=$f")
                    previewFraction = f
                    onSeek(f)
                }
            }
            .pointerInput(Unit) {
                // 拖动必须「本地预览 + 松手才 seek 一次」：每个 move 都 seek 会让
                // 主线程被 pause/flush/解码器 seek 堵住，头显里就是一卡一卡。
                detectDragGestures(
                    onDragStart = { offset ->
                        val f = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        previewFraction = f
                        Log.i(SEEK_TAG, "dragStart@$tag fraction=$f")
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        previewFraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        val f = previewFraction
                        Log.i(SEEK_TAG, "dragEnd@$tag fraction=$f")
                        if (f >= 0f) onSeek(f)
                    },
                    onDragCancel = { previewFraction = -1f }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val trackHeight = (if (prefersFocus) 6.dp else 4.dp).toPx()
            val y = size.height / 2f
            val radius = trackHeight / 2f
            val trackWidth = size.width
            val progress = (trackWidth * shownFraction.coerceIn(0f, 1f))
            drawRoundRect(
                color = OrzColors.Line,
                topLeft = Offset(0f, y - radius),
                size = Size(trackWidth, trackHeight),
                cornerRadius = CornerRadius(radius, radius)
            )
            if (progress > 0f) {
                drawRoundRect(
                    color = OrzColors.Mint,
                    topLeft = Offset(0f, y - radius),
                    size = Size(progress, trackHeight),
                    cornerRadius = CornerRadius(radius, radius)
                )
            }
            val thumbRadius = (if (prefersFocus) 8.dp else 6.dp).toPx()
            val thumbCenter = Offset(
                progress.coerceIn(thumbRadius, trackWidth - thumbRadius),
                y
            )
            drawCircle(color = OrzColors.Text, radius = thumbRadius, center = thumbCenter)
            drawCircle(
                color = OrzColors.Mint,
                radius = thumbRadius,
                center = thumbCenter,
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}

/** 音量滑条：不依赖系统媒体音量，直接改 ExoPlayer 的 volume。 */
@Composable
fun VolumeSlider(
    volume: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val focus = remember { FocusRequester() }
    Box(
        modifier = modifier
            .height(24.dp)
            .focusOnHover(focus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft, Key.DirectionDown -> { onChange(volume - 0.05f); true }
                    Key.DirectionRight, Key.DirectionUp -> { onChange(volume + 0.05f); true }
                    else -> false
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onChange((offset.x / size.width.toFloat()).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    onChange((change.position.x / size.width.toFloat()).coerceIn(0f, 1f))
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(OrzColors.Line, CircleShape)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(volume.coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(OrzColors.Mint, CircleShape)
            )
        }
    }
}

@Composable
fun EmptyPane(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("∿", color = OrzColors.MutedDim, fontSize = 34.sp)
            Text(text = text, color = OrzColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
fun StatusBanner(
    text: String,
    accent: Color,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OrzColors.SurfaceHigh, RoundedCornerShape(12.dp))
            .border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(text = text, color = accent, fontSize = 12.sp)
        action?.invoke()
    }
}

fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}


/**
 * 真实 PCM 声场柱状图（数据来自 [com.jokerhub.orzmusic.android.player.VisualizerBus]）。
 *
 * 没有数据时保持低平柱，不伪造随机波形；这样“没接上”一眼能看出来。
 */
@Composable
fun VisualizerBars(
    levels: FloatArray,
    active: Boolean,
    modifier: Modifier = Modifier,
    color: Color = OrzColors.Mint,
    maxBars: Int = 0
) {
    Canvas(modifier = modifier) {
        val sourceBars = levels.size.coerceAtLeast(1)
        val bars = if (maxBars > 0) maxBars.coerceAtLeast(1) else sourceBars
        val gap = if (bars <= 12) 2.dp.toPx() else if (bars >= 60) 2.dp.toPx() else 3.dp.toPx()
        val barWidth = ((size.width - gap * (bars - 1)) / bars).coerceAtLeast(1f)
        val middle = size.height / 2f
        for (i in 0 until bars) {
            // 源柱数可能少于目标柱数（Web 桌面是 72 根），做线性插值而不是整块重复。
            val pos = if (bars <= 1) 0f else i * (sourceBars - 1f) / (bars - 1f)
            val i0 = pos.toInt().coerceIn(0, sourceBars - 1)
            val i1 = (i0 + 1).coerceAtMost(sourceBars - 1)
            val frac = pos - i0
            val level = (
                levels.getOrElse(i0) { 0f } * (1f - frac) +
                    levels.getOrElse(i1) { 0f } * frac
                ).coerceIn(0f, 1f)
            val h = size.height * (0.07f + 0.93f * level)
            val x = i * (barWidth + gap)
            drawRoundRect(
                color = if (active) color.copy(alpha = 0.62f) else OrzColors.MintDim.copy(alpha = 0.35f),
                topLeft = Offset(x, middle - h / 2f),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}
