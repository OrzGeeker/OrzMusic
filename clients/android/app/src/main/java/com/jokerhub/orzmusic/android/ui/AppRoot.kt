package com.jokerhub.orzmusic.android.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.Text
import com.jokerhub.orzmusic.android.ui.screens.ArtistsList
import com.jokerhub.orzmusic.android.ui.screens.LibraryContent
import com.jokerhub.orzmusic.android.ui.screens.NowPlayingScreen
import com.jokerhub.orzmusic.android.ui.screens.PlaylistsList
import com.jokerhub.orzmusic.android.ui.screens.SettingsContent
import com.jokerhub.orzmusic.android.player.VisualizerBus
import com.jokerhub.orzmusic.android.ui.screens.SongListPane
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 应用根节点 —— 手机 / 平板 / TV / VR 共用同一套 Compose。
 *
 * 信息架构对齐 Web 端:
 *   · 宽屏:左侧「浏览 + 格式分组」栏 + 顶栏搜索 + 主内容 + 底部播放坞
 *   · 窄屏:顶部品牌/搜索/标签 + 主内容 + 底部播放坞
 *   · 队列抽屉、正在播放、快捷键说明都是覆盖层
 *
 * 所有可点元素仍用 tv-material3 的 Card/Button:TV 有焦点态,D-pad 能走,
 * 触屏就是普通点击。
 */
@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val visualizerLevels by VisualizerBus.levels.collectAsStateWithLifecycle()
    val latestState by rememberUpdatedState(state)
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val initialFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val nowPlayingFocus = remember { FocusRequester() }
    var visualizerOpen by remember { mutableStateOf(true) }

    fun dismissInput() {
        runCatching { keyboard?.hide() }
        focusManager.clearFocus(force = true)
    }

    val openQueue = {
        dismissInput()
        vm.openQueue()
    }
    val openNowPlaying = {
        dismissInput()
        vm.openNowPlaying()
    }
    val openShortcuts = {
        dismissInput()
        vm.toggleShortcuts()
    }

    fun restoreFocus(nowPlaying: Boolean) {
        // Pico 在 IME / PicoXR_CustomDialog 关闭后，原焦点节点会被清掉。
        // 先让 Compose 的焦点系统重新选一个可聚焦节点，再显式请求目标节点。
        focusManager.clearFocus(force = true)
        focusManager.moveFocus(FocusDirection.Next)
        scope.launch {
            delay(260)
            runCatching {
                if (nowPlaying) nowPlayingFocus.requestFocus() else initialFocus.requestFocus()
            }
        }
    }

    // 系统 IME 由系统自己管理，不要在这里 hide / clearFocus —— 否则搜索框
    // 刚获得焦点就被强制收起。回到前台只处理网络重试。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.retryIfNeeded()
    }

    // 等首帧把侧栏/标签组合出来再请求焦点,否则 LazyColumn 的 item 还没注册,
    // 焦点会落到顶栏搜索框,电视上一启动就弹输入法。
    // 覆盖层打开/关闭时也要把焦点交还给仍在显示的层。
    LaunchedEffect(state.queueOpen, state.showNowPlaying, state.showShortcuts) {
        if (state.queueOpen || state.showShortcuts) return@LaunchedEffect
        delay(160)
        runCatching {
            if (state.showNowPlaying) nowPlayingFocus.requestFocus()
            else initialFocus.requestFocus()
        }
    }

    // 系统 BACK / 遥控返回键：优先关闭最上层覆盖层。
    // 只靠 onKeyEvent 在 Pico 上不稳定（焦点不在 Compose 时收不到）。
    BackHandler(
        enabled = state.queueOpen || state.showShortcuts ||
            state.showNowPlaying || state.isSearching
    ) {
        when {
            state.showShortcuts -> {
                dismissInput()
                vm.closeShortcuts()
                restoreFocus(nowPlaying = false)
            }
            state.queueOpen -> {
                val keepNowPlaying = state.showNowPlaying
                dismissInput()
                vm.closeQueue()
                restoreFocus(keepNowPlaying)
            }
            state.showNowPlaying -> {
                dismissInput()
                vm.closeNowPlaying()
                restoreFocus(nowPlaying = false)
            }
            state.isSearching -> {
                dismissInput()
                vm.clearSearch()
                restoreFocus(nowPlaying = false)
            }
        }
    }

    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    // 换歌的瞬间就把进度归零：轮询要等下一次采样，否则会短暂把上一首的进度
    // 画在新歌上（真机反馈「进度带到下一首」）。
    LaunchedEffect(state.current?.id) {
        positionMs = 0L
        durationMs = ((state.current?.duration ?: 0.0) * 1000).toLong()
    }
    LaunchedEffect(state.current?.id, state.isPlaying, state.decoding) {
        while (isActive) {
            positionMs = vm.playbackPositionMs
            durationMs = vm.playbackDurationMs
            delay(400)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(OrzColors.BgTop, OrzColors.Bg, OrzColors.BgBottom))
            )
            .systemBarsPadding()
            .imePadding()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.Spacebar -> { vm.togglePlay(); true }
                    Key.M -> { vm.toggleMute(); true }
                    Key.N, Key.MediaNext -> { vm.playNext(); true }
                    Key.P, Key.MediaPrevious -> { vm.playPrevious(); true }
                    Key.L -> {
                        vm.closeNowPlaying()
                        vm.closeQueue()
                        vm.locateCurrentSong()
                        true
                    }
                    Key.Q -> { if (state.queueOpen) vm.closeQueue() else vm.openQueue(); true }
                    Key.Slash -> { runCatching { searchFocus.requestFocus() }; true }
                    Key.MediaPlayPause -> { vm.togglePlay(); true }
                    Key.Escape, Key.Back -> when {
                        state.showShortcuts -> { dismissInput(); vm.closeShortcuts(); restoreFocus(false); true }
                        state.queueOpen -> {
                            val keepNowPlaying = state.showNowPlaying
                            dismissInput()
                            vm.closeQueue()
                            restoreFocus(keepNowPlaying)
                            true
                        }
                        state.showNowPlaying -> { dismissInput(); vm.closeNowPlaying(); restoreFocus(false); true }
                        state.isSearching -> { dismissInput(); vm.clearSearch(); restoreFocus(false); true }
                        else -> false
                    }
                    else -> false
                }
            }
    ) {
        // 840dp 以下用「手机/平板竖屏」的顶部导航；840 以上才上侧栏。
        val wide = maxWidth >= 840.dp

        if (wide) {
            Row(modifier = Modifier.fillMaxSize()) {
                Sidebar(
                    state = state,
                    onTab = vm::selectTab,
                    onFormat = { format ->
                        vm.selectTab(Tab.Library)
                        vm.selectFormat(format)
                    },
                    onShortcuts = vm::toggleShortcuts,
                    initialFocus = initialFocus
                )
                Box(Modifier.width(1.dp).fillMaxHeight().background(OrzColors.Line.copy(alpha = 0.55f)))
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    TopBar(
                        state = state,
                        wide = true,
                        searchFocus = searchFocus,
                        onSearch = vm::search,
                        onQueue = openQueue,
                        onNowPlaying = openNowPlaying,
                        onShortcuts = openShortcuts
                    )
                    StatusArea(state, vm)
                    Box(modifier = Modifier.weight(1f)) {
                        ContentArea(state, vm, wide = true)
                    }
                    if (state.current != null) {
                        if (visualizerOpen) {
                            VisualizerPanel(
                                levels = visualizerLevels,
                                active = state.isPlaying,
                                compact = false,
                                onCollapse = { visualizerOpen = false }
                            )
                        } else {
                            CollapsedVisualizer(onExpand = { visualizerOpen = true })
                        }
                    }
                    PlayerDock(
                        state, vm, positionMs, durationMs,
                        onQueue = openQueue,
                        onNowPlaying = openNowPlaying
                    )
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                TopBar(
                    state = state,
                    wide = false,
                    searchFocus = searchFocus,
                    onSearch = vm::search,
                    onQueue = openQueue,
                    onNowPlaying = openNowPlaying,
                    onShortcuts = openShortcuts
                )
                CompactTabs(state.tab, vm::selectTab, initialFocus)
                StatusArea(state, vm)
                Box(modifier = Modifier.weight(1f)) {
                    ContentArea(state, vm, wide = false)
                }
                if (state.current != null) {
                    if (visualizerOpen) {
                        VisualizerPanel(
                            levels = visualizerLevels,
                            active = state.isPlaying,
                            compact = true,
                            onCollapse = { visualizerOpen = false }
                        )
                    } else {
                        CollapsedVisualizer(onExpand = { visualizerOpen = true })
                    }
                }
                PlayerDock(
                    state, vm, positionMs, durationMs,
                    onQueue = openQueue,
                    onNowPlaying = openNowPlaying
                )
            }
        }

        val current = state.current
        if (state.showNowPlaying && current != null) {
            NowPlayingScreen(
                song = current,
                isPlaying = state.isPlaying,
                decoding = state.decoding,
                notice = state.notice,
                positionMs = positionMs,
                durationMs = durationMs,
                volume = state.volume,
                levels = visualizerLevels,
                queueCount = state.queue.size,
                serverUrl = state.serverUrl,
                onBack = {
                    dismissInput()
                    vm.closeNowPlaying()
                },
                onToggle = vm::togglePlay,
                onPrevious = vm::playPrevious,
                onNext = vm::playNext,
                onSeek = vm::seekTo,
                onSeekBy = vm::seekBy,
                onVolume = vm::setVolume,
                onMute = vm::toggleMute,
                onQueue = openQueue,
                focusRequester = nowPlayingFocus,
                onLocate = {
                    dismissInput()
                    vm.closeNowPlaying()
                    vm.locateCurrentSong()
                }
            )
        }

        if (state.queueOpen) {
            QueueDrawer(
                queue = state.queue,
                currentId = state.current?.id,
                playlists = state.playlists,
                onClose = {
                    dismissInput()
                    vm.closeQueue()
                },
                onPlay = { song -> vm.play(song, state.queue) },
                onRemove = vm::removeFromQueue,
                onSavePlaylist = vm::saveQueueAsPlaylist,
                onLoadPlaylist = vm::loadPlaylistIntoQueue,
                onDeletePlaylist = vm::deletePlaylist
            )
        }

        if (state.showShortcuts) {
            ShortcutsDialog(onClose = {
                dismissInput()
                vm.closeShortcuts()
            })
        }

    }

    // Pico VRSHELL 抢走焦点后，用户点回 App 时窗口焦点会回来，但 Compose
    // 内部焦点不会自动恢复；补一次 FocusRequester。
    LaunchedEffect(Unit) {
        WindowFocusEvents.events.collect {
            delay(120)
            runCatching {
                when {
                    latestState.queueOpen || latestState.showShortcuts -> Unit
                    latestState.showNowPlaying -> nowPlayingFocus.requestFocus()
                    else -> initialFocus.requestFocus()
                }
            }
        }
    }
}

@Composable
private fun Sidebar(
    state: UiState,
    onTab: (Tab) -> Unit,
    onFormat: (String?) -> Unit,
    onShortcuts: () -> Unit,
    initialFocus: FocusRequester
) {
    val groups = remember(state.formats) { FormatCatalog.groups(state.formats) }
    LazyColumn(
        modifier = Modifier
            .width(236.dp)
            .fillMaxHeight()
            .background(OrzColors.Surface)
            .padding(horizontal = 10.dp),
        contentPadding = PaddingValues(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        item {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                BrandMark()
                Column {
                    Text("OrzMusic", color = OrzColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "CHIP AUDIO",
                        color = OrzColors.MutedDim,
                        fontSize = 7.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                }
            }
        }
        item { SectionLabel("浏览", Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) }
        item {
            NavItem(
                label = "全部曲目",
                selected = state.tab == Tab.Library && state.selectedFormat == null,
                onClick = { onFormat(null) },
                modifier = Modifier.focusRequester(initialFocus),
                trailing = state.stats?.totalSongs?.toString()
            )
        }
        items(listOf(Tab.Artists, Tab.Playlists, Tab.Settings), key = { it.name }) { tab ->
            NavItem(
                label = tab.label,
                selected = state.tab == tab,
                onClick = { onTab(tab) }
            )
        }

        if (groups.isNotEmpty()) {
            item { SectionLabel("格式", Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) }
        }
        groups.forEach { group ->
            item(key = "g-${group.id}") {
                Text(
                    text = group.label.uppercase(),
                    modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 3.dp),
                    color = OrzColors.MutedDim,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
            items(group.items, key = { "f-${it.id}" }) { meta ->
                FormatNavItem(
                    meta = meta,
                    selected = state.tab == Tab.Library && state.selectedFormat == meta.id,
                    onClick = { onFormat(meta.id) }
                )
            }
        }
        item {
            Spacer(Modifier.height(6.dp))
            NavItem(
                label = "快捷键",
                selected = false,
                onClick = onShortcuts,
                trailing = "?"
            )
        }
    }
}

@Composable
private fun TopBar(
    state: UiState,
    wide: Boolean,
    searchFocus: FocusRequester,
    onSearch: (String) -> Unit,
    onQueue: () -> Unit,
    onNowPlaying: () -> Unit,
    onShortcuts: () -> Unit
) {
    val title = when {
        state.isSearching -> "搜索结果"
        state.tab == Tab.Library -> state.activeFormatLabel
        else -> state.tab.label
    }
    val eyebrow = when (state.tab) {
        Tab.Library -> "音乐资料库"
        Tab.Artists -> "浏览艺术家"
        Tab.Playlists -> "我的歌单"
        Tab.Settings -> "偏好设置"
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (wide) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(modifier = Modifier.widthIn(min = 170.dp, max = 240.dp)) {
                    Text(
                        text = eyebrow,
                        color = OrzColors.Mint,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp
                    )
                    Text(
                        text = title,
                        color = OrzColors.Text,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
                SearchField(
                    value = state.searchQuery,
                    placeholder = "搜索曲目或艺术家",
                    onValueChange = onSearch,
                    focusRequester = searchFocus,
                    modifier = Modifier.weight(1f).widthIn(max = 560.dp)
                )
                Spacer(Modifier.weight(0.06f))
                if (state.current != null) {
                    OrzButton(onClick = onNowPlaying) {
                        Text("正在播放", fontSize = 12.5.sp)
                    }
                }
                OrzButton(onClick = onQueue) {
                    Text("队列 ${state.queue.size}", fontSize = 12.5.sp)
                }
                OrzButton(onClick = onShortcuts) {
                    Text("?", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrandMark()
                    Spacer(Modifier.width(9.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("OrzMusic", color = OrzColors.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(eyebrow, color = OrzColors.MutedDim, fontSize = 9.sp, letterSpacing = 1.sp)
                    }
                    if (state.current != null) {
                        // 窄屏只有图标没有文字:必须补 contentDescription,否则
                        // uiautomator / TalkBack 看到的是一个无名可点节点。
                        OrzButton(
                            onClick = onNowPlaying,
                            modifier = Modifier.semantics { contentDescription = "正在播放" }
                        ) {
                            PlayIcon(iconSize = 13.dp, tint = OrzColors.Mint)
                        }
                    }
                    Spacer(Modifier.width(7.dp))
                    OrzButton(onClick = onQueue) {
                        Text("队列 ${state.queue.size}", fontSize = 11.5.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                SearchField(
                    value = state.searchQuery,
                    placeholder = "搜索曲目或艺术家",
                    onValueChange = onSearch,
                    focusRequester = searchFocus
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(OrzColors.Line.copy(alpha = 0.7f)))
    }
}

@Composable
private fun CompactTabs(selected: Tab, onSelect: (Tab) -> Unit, initialFocus: FocusRequester) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(Tab.entries, key = { it.name }) { tab ->
            val tabFocus = remember { FocusRequester() }
            Card(
                onClick = { onSelect(tab) },
                modifier = (if (tab == Tab.entries.first()) Modifier.focusRequester(initialFocus) else Modifier)
                    .tapGesture { onSelect(tab) }
                    .focusOnHover(tabFocus),
                colors = orzCardColors(),
                scale = orzCardScale(),
                border = orzCardBorder(RoundedCornerShape(10.dp))
            ) {
                Text(
                    text = tab.label,
                    modifier = Modifier
                        .background(if (selected == tab) OrzColors.MintSoft else OrzColors.SurfaceHigh, RoundedCornerShape(10.dp))
                        .padding(horizontal = 13.dp, vertical = 7.dp),
                    color = if (selected == tab) OrzColors.Mint else OrzColors.Muted,
                    fontSize = 12.sp,
                    fontWeight = if (selected == tab) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun StatusArea(state: UiState, vm: AppViewModel) {
    state.error?.let { message ->
        StatusBanner(
            text = message,
            accent = OrzColors.Danger,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            OrzButton(onClick = vm::retry) { Text("重试") }
            OrzButton(onClick = vm::clearError) { Text("关闭") }
        }
    }
    state.notice?.let { message ->
        StatusBanner(
            text = message,
            accent = OrzColors.Mint,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            OrzButton(onClick = vm::clearNotice) { Text("关闭") }
        }
    }
}

/** 与 Web 版 `.viz-panel` 对齐：固定在播放坞上方，而不是塞进播放坞或详情页。 */
@Composable
private fun VisualizerPanel(
    levels: FloatArray,
    active: Boolean,
    compact: Boolean,
    onCollapse: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) 96.dp else 132.dp)
            .background(
                Brush.verticalGradient(
                    listOf(OrzColors.BgBottom, OrzColors.BgTop, OrzColors.BgBottom)
                )
            )
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(OrzColors.Line))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "全息声场",
                color = OrzColors.Mint,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
            Spacer(Modifier.weight(1f))
            Text("PCM · 实时", color = OrzColors.MutedDim, fontSize = 9.sp)
            Spacer(Modifier.width(10.dp))
            VizToggleButton(label = "收起", onClick = onCollapse)
        }
        VisualizerBars(
            levels = levels,
            active = active,
            maxBars = if (compact) 32 else 48,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 18.dp)
                .padding(bottom = 10.dp)
        )
    }
}

/** 收起后的入口，对齐 Web `.viz-expand-btn`。 */
@Composable
private fun CollapsedVisualizer(onExpand: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
        VizToggleButton(label = "▸ 声场", onClick = onExpand)
    }
}

@Composable
private fun PlayerDock(
    state: UiState,
    vm: AppViewModel,
    positionMs: Long,
    durationMs: Long,
    onQueue: () -> Unit,
    onNowPlaying: () -> Unit
) {
    val song = state.current
    val fraction = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(OrzColors.SurfaceHigh.copy(alpha = 0.92f), OrzColors.Surface)
                )
            )
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(OrzColors.Line))
        if (song != null) {
            SeekBar(
                fraction = fraction,
                onSeek = { value ->
                    Log.i("OrzSeek", "dock.onSeek fraction=$value duration=$durationMs")
                    if (durationMs > 0) vm.seekTo((value * durationMs).toLong())
                },
                onSeekBy = vm::seekBy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                tag = "dock"
            )
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            val compact = maxWidth < 760.dp
            val showVolume = maxWidth >= 980.dp
            if (song == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NowPlayingMini(null, state, onOpen = onNowPlaying, modifier = Modifier.weight(1f))
                    if (!compact) {
                        Text(
                            text = "从曲库选一首，或按 / 搜索",
                            color = OrzColors.MutedDim,
                            fontSize = 11.sp
                        )
                    }
                }
            } else if (compact) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NowPlayingMini(song, state, onOpen = onNowPlaying, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    Transport(state, vm, time = null, compact = true)
                }
            } else {
                // 三等分：左曲目 / 中播放控制 / 右队列+音量。
                // 只有左右同宽，中间的「上一首 · 播放 · 下一首 · 时长」才会真正落在
                // 面板几何中心；否则会被左侧曲目卡片的 weight 推偏。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f)) {
                        NowPlayingMini(
                            song = song,
                            state = state,
                            onOpen = onNowPlaying,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Transport(
                            state,
                            vm,
                            time = "${formatTime(positionMs)} / ${formatTime(durationMs)}",
                            compact = false
                        )
                    }
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            OrzRoundButton(
                                onClick = onQueue,
                                buttonSize = 32.dp,
                                contentDescription = "播放队列"
                            ) {
                                QueueIcon(iconSize = 13.dp, tint = OrzColors.Muted)
                            }
                            if (showVolume) {
                                VolumeControl(state.volume, vm::setVolume, vm::toggleMute)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NowPlayingMini(
    song: com.jokerhub.orzmusic.core.model.Song?,
    state: UiState,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (song == null) {
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            BrandBadge("—", size = 38.dp)
            Column(modifier = Modifier.padding(start = 10.dp)) {
                Text("选择一首曲目开始播放", color = OrzColors.Muted, fontSize = 13.sp)
                Text("支持原生 · 服务端 · 直接播放", color = OrzColors.MutedDim, fontSize = 10.sp)
            }
        }
        return
    }
    val miniFocus = remember { FocusRequester() }
    Card(
        onClick = onOpen,
        modifier = modifier.tapGesture(onOpen).focusOnHover(miniFocus),
        colors = orzCardColors(),
        scale = orzCardScale(),
        border = orzCardBorder(RoundedCornerShape(10.dp))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BrandBadge(song.fileFormat, size = 38.dp)
            Column(modifier = Modifier.padding(start = 10.dp).weight(1f, fill = false)) {
                Text(
                    text = song.title,
                    color = OrzColors.Text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${song.artistName} · ${song.fileFormat.uppercase()}" +
                        if (state.decoding) " · 解码中…" else "",
                    color = OrzColors.Muted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun Transport(state: UiState, vm: AppViewModel, time: String?, compact: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 7.dp)
    ) {
        OrzRoundButton(
            onClick = vm::playPrevious,
            buttonSize = if (compact) 30.dp else 33.dp,
            contentDescription = "上一首"
        ) {
            PreviousIcon(iconSize = 12.dp, tint = OrzColors.Muted)
        }
        OrzRoundButton(
            onClick = vm::togglePlay,
            buttonSize = if (compact) 40.dp else 44.dp,
            primary = true,
            contentDescription = if (state.isPlaying) "暂停" else "播放"
        ) {
            if (state.isPlaying) {
                PauseIcon(iconSize = 16.dp, tint = OrzColors.Bg)
            } else {
                PlayIcon(iconSize = 16.dp, tint = OrzColors.Bg)
            }
        }
        OrzRoundButton(
            onClick = vm::playNext,
            buttonSize = if (compact) 30.dp else 33.dp,
            contentDescription = "下一首"
        ) {
            NextIcon(iconSize = 12.dp, tint = OrzColors.Muted)
        }
        if (time != null) {
            Text(
                text = time,
                modifier = Modifier.width(76.dp).padding(start = 4.dp),
                color = OrzColors.Muted,
                fontSize = 9.5.sp,
                textAlign = TextAlign.Start
            )
        }
    }
}

@Composable
private fun VolumeControl(volume: Float, onChange: (Float) -> Unit, onMute: () -> Unit) {
    Row(
        modifier = Modifier.width(190.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
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
        VolumeSlider(volume = volume, onChange = onChange, modifier = Modifier.width(88.dp))
        Text(
            text = "${(volume * 100).toInt()}%",
            modifier = Modifier.width(34.dp),
            color = OrzColors.MutedDim,
            fontSize = 9.5.sp,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun ContentArea(state: UiState, vm: AppViewModel, wide: Boolean) {
    when (state.tab) {
        Tab.Library -> LibraryContent(
            songs = state.visibleSongs,
            formats = state.formats,
            selectedFormat = state.selectedFormat,
            totalSongs = state.songTotal.takeIf { it > 0 } ?: state.stats?.totalSongs ?: 0,
            totalArtists = state.stats?.totalArtists ?: 0,
            loading = state.loading,
            isSearching = state.isSearching,
            searchQuery = state.searchQuery,
            currentId = state.current?.id,
            isPlaying = state.isPlaying,
            locatedSongId = state.locatedSongId,
            onFormat = vm::selectFormat,
            onSong = vm::play,
            onLoadMore = vm::loadMoreSongs,
            onClearSearch = vm::clearSearch,
            shellWide = wide,
            modifier = Modifier.fillMaxSize()
        )

        Tab.Artists -> {
            if (wide) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.width(320.dp).fillMaxHeight()) {
                        ArtistsList(
                            artists = state.artists,
                            loading = state.loading,
                            selectedArtistId = state.detailId,
                            onArtist = vm::openArtist
                        )
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        if (state.detailTitle != null) {
                            SongListPane(
                                title = state.detailTitle ?: "",
                                subtitle = state.detailSubtitle,
                                songs = state.detailSongs,
                                loading = state.loading,
                                currentId = state.current?.id,
                                isPlaying = state.isPlaying,
                                onBack = vm::closeDetail,
                                onSong = vm::play
                            )
                        } else {
                            EmptyPane("选择一位艺术家", Modifier.fillMaxSize())
                        }
                    }
                }
            } else if (state.detailTitle != null) {
                SongListPane(
                    title = state.detailTitle ?: "",
                    subtitle = state.detailSubtitle,
                    songs = state.detailSongs,
                    loading = state.loading,
                    currentId = state.current?.id,
                    isPlaying = state.isPlaying,
                    onBack = vm::closeDetail,
                    onSong = vm::play
                )
            } else {
                ArtistsList(
                    artists = state.artists,
                    loading = state.loading,
                    selectedArtistId = null,
                    onArtist = vm::openArtist
                )
            }
        }

        Tab.Playlists -> {
            if (wide) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.width(320.dp).fillMaxHeight()) {
                        PlaylistsList(
                            playlists = state.playlists,
                            loading = state.loading,
                            selectedPlaylistId = state.detailId,
                            onPlaylist = vm::openPlaylist
                        )
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        if (state.detailTitle != null) {
                            SongListPane(
                                title = state.detailTitle ?: "",
                                subtitle = state.detailSubtitle,
                                songs = state.detailSongs,
                                loading = state.loading,
                                currentId = state.current?.id,
                                isPlaying = state.isPlaying,
                                onBack = vm::closeDetail,
                                onSong = vm::play
                            )
                        } else {
                            EmptyPane("选择一个歌单", Modifier.fillMaxSize())
                        }
                    }
                }
            } else if (state.detailTitle != null) {
                SongListPane(
                    title = state.detailTitle ?: "",
                    subtitle = state.detailSubtitle,
                    songs = state.detailSongs,
                    loading = state.loading,
                    currentId = state.current?.id,
                    isPlaying = state.isPlaying,
                    onBack = vm::closeDetail,
                    onSong = vm::play
                )
            } else {
                PlaylistsList(
                    playlists = state.playlists,
                    loading = state.loading,
                    selectedPlaylistId = null,
                    onPlaylist = vm::openPlaylist
                )
            }
        }

        Tab.Settings -> SettingsContent(
            serverUrl = state.serverUrl,
            notice = state.notice,
            version = com.jokerhub.orzmusic.android.BuildConfig.VERSION_NAME,
            onSave = vm::updateServerUrl,
            onCheck = vm::checkServer,
            onShortcuts = vm::toggleShortcuts,
            modifier = Modifier.fillMaxSize()
        )
    }
}
