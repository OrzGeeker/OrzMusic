package com.jokerhub.orzmusic.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.jokerhub.orzmusic.core.model.FormatCount
import com.jokerhub.orzmusic.core.model.Song
import com.jokerhub.orzmusic.android.ui.EmptyPane
import com.jokerhub.orzmusic.android.ui.FormatChip
import com.jokerhub.orzmusic.android.ui.OrzButton
import com.jokerhub.orzmusic.android.ui.OrzColors
import com.jokerhub.orzmusic.android.ui.orzButtonColors
import com.jokerhub.orzmusic.android.ui.SongRow
import com.jokerhub.orzmusic.android.ui.SongTableHeader

/**
 * 曲库主视图 —— 对齐 Web 端 `song-table`。
 *
 * 宽屏显示「标题 / 艺术家 / 专辑 / 格式 / 大小 / 时长」；
 * 手机只保留最有用的列。格式分组在宽屏由左侧栏承担，窄屏才用横向 chips。
 *
 * 刻意保持无状态：截图测试直接喂数据，不碰网络/ViewModel。
 */
@Composable
fun LibraryContent(
    songs: List<Song>,
    formats: List<FormatCount>,
    selectedFormat: String?,
    totalSongs: Int,
    totalArtists: Int,
    loading: Boolean,
    isSearching: Boolean,
    searchQuery: String,
    currentId: String?,
    isPlaying: Boolean,
    locatedSongId: String?,
    onFormat: (String?) -> Unit,
    onSong: (Song, List<Song>) -> Unit,
    onLoadMore: () -> Unit,
    onClearSearch: () -> Unit,
    shellWide: Boolean? = null,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 内容区自己也要判断：侧栏占掉 236dp 后，屏幕宽 ≠ 可用宽。
        val table = maxWidth >= 560.dp
        val wideShell = shellWide ?: (maxWidth >= 760.dp)
        val showAlbum = maxWidth >= 800.dp
        val showSize = maxWidth >= 800.dp
        val pagePadding = if (table) 22.dp else 12.dp
        val listState = rememberLazyListState()

        LaunchedEffect(locatedSongId) {
            val id = locatedSongId ?: return@LaunchedEffect
            val index = songs.indexOfFirst { it.id == id }
            if (index >= 0) listState.animateScrollToItem(index)
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(horizontal = pagePadding, vertical = 8.dp)) {
                if (!wideShell) {
                    Text(
                        text = if (isSearching) "搜索结果" else if (selectedFormat == null) "全部曲目" else selectedFormat.uppercase(),
                        color = OrzColors.Text,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = if (isSearching) {
                        "“$searchQuery” · ${songs.size} 个结果"
                    } else {
                        "$totalSongs 首 · $totalArtists 位艺术家 · ${formats.size} 种格式"
                    },
                    color = OrzColors.Muted,
                    fontSize = 12.sp
                )
            }

            // 手机/平板紧凑布局：格式分组用横向 chips；有侧栏时由侧栏承担。
            if (!wideShell) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = pagePadding),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    item {
                        FormatChip(
                            label = "全部",
                            count = totalSongs.takeIf { it > 0 },
                            selected = selectedFormat == null,
                            onClick = { onFormat(null) }
                        )
                    }
                    items(formats, key = { it.format }) { item ->
                        FormatChip(
                            label = item.format,
                            count = item.count,
                            selected = selectedFormat == item.format,
                            onClick = { onFormat(item.format) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            when {
                loading && songs.isEmpty() -> EmptyPane("正在整理音乐资料库…", Modifier.padding(top = 40.dp))
                songs.isEmpty() -> EmptyPane(
                    if (isSearching) "没有找到曲目，换个关键词试试" else "这个筛选下还没有曲目",
                    Modifier.padding(top = 40.dp)
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = pagePadding, vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (table) {
                        item {
                            SongTableHeader(showAlbum = showAlbum, showSize = showSize)
                        }
                    }
                    items(songs, key = { it.id }) { song ->
                        SongRow(
                            song = song,
                            isCurrent = currentId == song.id,
                            isPlaying = isPlaying && currentId == song.id,
                            onClick = { onSong(song, songs) },
                            showArtist = table,
                            showAlbum = showAlbum,
                            showSize = showSize
                        )
                    }
                    if (!isSearching && songs.size < totalSongs) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                OrzButton(onClick = onLoadMore) {
                                    Text(if (loading) "加载中…" else "加载更多 · ${songs.size}/$totalSongs")
                                }
                            }
                        }
                    }
                }
            }

            if (isSearching) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = pagePadding, vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    OrzButton(onClick = onClearSearch) { Text("清除搜索") }
                }
            }
        }
    }
}
