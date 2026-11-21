package com.jokerhub.orzmusic.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.jokerhub.orzmusic.android.ui.ArtistRow
import com.jokerhub.orzmusic.android.ui.EmptyPane
import com.jokerhub.orzmusic.android.ui.OrzButton
import com.jokerhub.orzmusic.android.ui.OrzColors
import com.jokerhub.orzmusic.android.ui.orzButtonColors
import com.jokerhub.orzmusic.android.ui.SearchField
import com.jokerhub.orzmusic.android.ui.SongRow
import com.jokerhub.orzmusic.core.model.Artist
import com.jokerhub.orzmusic.core.model.Playlist
import com.jokerhub.orzmusic.core.model.Song

@Composable
fun ArtistsList(
    artists: List<Artist>,
    loading: Boolean,
    selectedArtistId: String?,
    onArtist: (Artist) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("艺术家", color = OrzColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("${artists.size} 位 · 按名字浏览", color = OrzColors.Muted, fontSize = 11.sp)
        }
        if (loading && artists.isEmpty()) {
            EmptyPane("加载中…")
            return@Column
        }
        if (artists.isEmpty()) {
            EmptyPane("还没有艺术家")
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            items(artists, key = { it.id }) { artist ->
                ArtistRow(
                    name = artist.name,
                    subtitle = "${artist.songCount} 首",
                    selected = artist.id == selectedArtistId,
                    onClick = { onArtist(artist) }
                )
            }
        }
    }
}

@Composable
fun PlaylistsList(
    playlists: List<Playlist>,
    loading: Boolean,
    selectedPlaylistId: String?,
    onPlaylist: (Playlist) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("歌单", color = OrzColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("${playlists.size} 个 · 在队列面板里保存当前队列", color = OrzColors.Muted, fontSize = 11.sp)
        }
        if (loading && playlists.isEmpty()) {
            EmptyPane("加载中…")
            return@Column
        }
        if (playlists.isEmpty()) {
            EmptyPane("还没有歌单。播放一些曲目后，在「队列」里保存。")
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            items(playlists, key = { it.id }) { playlist ->
                ArtistRow(
                    name = playlist.name,
                    subtitle = playlist.description?.takeIf { it.isNotBlank() }
                        ?: "歌单 · ${playlist.songCount ?: 0} 首",
                    selected = playlist.id == selectedPlaylistId,
                    onClick = { onPlaylist(playlist) }
                )
            }
        }
    }
}

@Composable
fun SongListPane(
    title: String,
    subtitle: String?,
    songs: List<Song>,
    loading: Boolean,
    currentId: String?,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onSong: (Song, List<Song>) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OrzButton(onClick = onBack) { Text("‹ 返回") }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = OrzColors.Text,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = subtitle ?: "${songs.size} 首",
                    color = OrzColors.Muted,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
            if (songs.isNotEmpty()) {
                OrzButton(onClick = { onSong(songs.first(), songs) }) { Text("播放全部") }
            }
        }

        if (loading && songs.isEmpty()) {
            EmptyPane("加载中…")
            return@Column
        }
        if (songs.isEmpty()) {
            EmptyPane("这里是空的")
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(songs, key = { it.id }) { song ->
                SongRow(
                    song = song,
                    isCurrent = currentId == song.id,
                    isPlaying = isPlaying && currentId == song.id,
                    onClick = { onSong(song, songs) },
                    showArtist = false,
                    showAlbum = false,
                    showSize = false
                )
            }
        }
    }
}

@Composable
fun SettingsContent(
    serverUrl: String,
    notice: String?,
    version: String,
    onSave: (String) -> Unit,
    onCheck: () -> Unit,
    onShortcuts: () -> Unit,
    modifier: Modifier = Modifier
) {
    var draft by remember(serverUrl) { mutableStateOf(serverUrl) }
    var advanced by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("设置", color = OrzColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(OrzColors.Surface, RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("服务器", color = OrzColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "默认连官方实例。自建实例填你自己的地址；设备必须能访问它。",
                color = OrzColors.Muted,
                fontSize = 11.sp
            )
            SearchField(
                value = draft,
                placeholder = "https://music.jokerhub.cn",
                onValueChange = { draft = it }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrzButton(onClick = { onSave(draft) }) { Text("保存并重载") }
                OrzButton(onClick = onCheck) { Text("测试连接") }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(OrzColors.Surface, RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("播放与界面", color = OrzColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "底部播放坞、播放队列、音量与进度条都已对齐网页端。声场是装饰性动画，不请求录音权限。",
                color = OrzColors.Muted,
                fontSize = 11.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrzButton(onClick = onShortcuts) { Text("快捷键 / 遥控说明") }
                OrzButton(onClick = { advanced = !advanced }) {
                    Text(if (advanced) "收起调试信息" else "显示调试信息")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // GPLv3 §6:应用内必须能拿到许可证与对应源码入口。
                OrzButton(onClick = { showLicenses = true }) { Text("开源许可 / 源码") }
            }
            if (advanced) {
                val formats = com.jokerhub.orzmusic.android.player.RuntimeDecoderManifest.formats
                Text(
                    text = "服务器：$serverUrl\n" +
                        "版本：v$version\n" +
                        "解码：OrzAudioCore 1.2.4（本机可用 ${formats.size} 个格式：" +
                        formats.joinToString("/") { it.id } + "）\n" +
                        "播放：模块/芯片音乐流式解码（AudioTrack），容器音频走 ExoPlayer；" +
                        "后台播放与 MediaSession 已接入。",
                    color = OrzColors.Muted,
                    fontSize = 11.sp
                )
            }
        }

        notice?.let {
            Text(
                text = it,
                color = OrzColors.Mint,
                fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(OrzColors.SurfaceHigh, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            )
        }

        Text(
            text = "OrzMusic Android v$version · 数据来自 OrzGeeker/OrzMusic HTTP API",
            color = OrzColors.MutedDim,
            fontSize = 10.sp
        )
    }

    if (showLicenses) {
        LicensesDialog(
            context = context,
            versionName = version,
            onDismiss = { showLicenses = false }
        )
    }
}
