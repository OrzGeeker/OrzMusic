package com.jokerhub.orzmusic.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.Text
import com.jokerhub.orzmusic.core.model.Playlist
import com.jokerhub.orzmusic.core.model.Song

/**
 * 网页端 `queue-panel` 的 Android 版。
 *
 * 宽屏是右侧抽屉，手机/Pico 是接近全宽的浮层；内容、顺序、保存歌单的入口
 * 都与 Web 端一致。
 */
@Composable
fun QueueDrawer(
    queue: List<Song>,
    currentId: String?,
    playlists: List<Playlist>,
    onClose: () -> Unit,
    onPlay: (Song) -> Unit,
    onRemove: (String) -> Unit,
    onSavePlaylist: (String) -> Unit,
    onLoadPlaylist: (String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var playlistName by remember { mutableStateOf("") }
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(60)
        runCatching { closeFocus.requestFocus() }
    }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val panelWidth = if (maxWidth < 520.dp) maxWidth else 410.dp
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.58f))
                .clickable(onClick = onClose)
        )
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(panelWidth)
                .fillMaxHeight()
                .background(OrzColors.Surface)
                .border(1.dp, OrzColors.Line.copy(alpha = 0.7f))
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("接下来播放", color = OrzColors.Mint, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                    Text("播放队列", color = OrzColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    text = "${queue.size} 首",
                    color = OrzColors.Muted,
                    fontSize = 12.sp
                )
                Spacer(Modifier.width(8.dp))
                OrzButton(

                    onClick = onClose,
                    modifier = Modifier.focusRequester(closeFocus)
                ) { Text("关闭", fontSize = 12.sp) }
            }
            Spacer(Modifier.height(10.dp))

            if (queue.isEmpty()) {
                EmptyPane("队列还是空的", Modifier.weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(queue, key = { it.id }) { song ->
                        QueueRow(
                            song = song,
                            active = song.id == currentId,
                            onClick = { onPlay(song) },
                            onRemove = { onRemove(song.id) }
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(OrzColors.Bg, RoundedCornerShape(12.dp))
                    .border(1.dp, OrzColors.Line.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text("保存当前队列", color = OrzColors.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                SearchField(
                    value = playlistName,
                    placeholder = "播放列表名称",
                    onValueChange = { playlistName = it }
                )
                OrzButton(

                    onClick = {
                        onSavePlaylist(playlistName)
                        playlistName = ""
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (queue.isEmpty()) "队列为空" else "保存为歌单")
                }
            }

            if (playlists.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("已保存歌单", color = OrzColors.MutedDim, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentPadding = PaddingValues(vertical = 3.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(playlists, key = { it.id }) { playlist ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val listFocus = remember { FocusRequester() }
                            Card(
                                onClick = { onLoadPlaylist(playlist.id) },
                                modifier = Modifier.weight(1f).tapGesture { onLoadPlaylist(playlist.id) }.focusOnHover(listFocus),
                                colors = orzCardColors(),
                                scale = orzCardScale(),
                                border = orzCardBorder(RoundedCornerShape(10.dp))
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = playlist.name,
                                        modifier = Modifier.weight(1f),
                                        color = OrzColors.Text,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${playlist.songCount ?: 0} 首",
                                        color = OrzColors.MutedDim,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                            Text(
                                text = "✕",
                                modifier = Modifier
                                    .clickable { onDeletePlaylist(playlist.id) }
                                    .padding(8.dp),
                                color = OrzColors.MutedDim,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    song: Song,
    active: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    val rowFocus = remember { FocusRequester() }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().tapGesture(onClick).focusOnHover(rowFocus),
        colors = orzCardColors(),
        scale = orzCardScale(),
        border = orzCardBorder(RoundedCornerShape(10.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (active) OrzColors.MintSoft else Color.Transparent)
                .padding(horizontal = 9.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (active) "▶" else "•",
                color = if (active) OrzColors.Mint else OrzColors.MutedDim,
                fontSize = 10.sp
            )
            BrandBadge(song.fileFormat, size = 32.dp, modifier = Modifier.padding(start = 8.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 9.dp)
            ) {
                Text(
                    text = song.title,
                    color = if (active) OrzColors.Mint else OrzColors.Text,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${song.artistName} · ${song.fileFormat.uppercase()}",
                    color = OrzColors.Muted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "✕",
                modifier = Modifier
                    .clickable { onRemove() }
                    .padding(6.dp),
                color = OrzColors.MutedDim,
                fontSize = 11.sp
            )
        }
    }
}
