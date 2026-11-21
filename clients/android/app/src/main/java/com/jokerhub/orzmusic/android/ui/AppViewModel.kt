package com.jokerhub.orzmusic.android.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jokerhub.orzmusic.android.data.ApiFactory
import com.jokerhub.orzmusic.android.data.DEFAULT_SERVER_URL
import com.jokerhub.orzmusic.android.data.MusicRepository
import com.jokerhub.orzmusic.android.player.MediaCache
import com.jokerhub.orzmusic.android.player.PlaybackCommandBridge
import com.jokerhub.orzmusic.android.player.PlaybackController
import com.jokerhub.orzmusic.android.player.RuntimeDecoderManifest
import com.jokerhub.orzmusic.core.model.Artist
import com.jokerhub.orzmusic.core.model.FormatCount
import com.jokerhub.orzmusic.core.model.Playlist
import com.jokerhub.orzmusic.core.model.PlaylistDraft
import com.jokerhub.orzmusic.core.model.Song
import com.jokerhub.orzmusic.core.model.Stats
import com.jokerhub.orzmusic.core.playback.AudioSource
import com.jokerhub.orzmusic.core.playback.PlaybackPolicy
import com.jokerhub.orzmusic.core.playback.PlaybackQueue
import com.jokerhub.orzmusic.core.playback.PlaybackRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tab(val label: String) {
    Library("曲库"),
    Artists("艺术家"),
    Playlists("歌单"),
    Settings("设置")
}

data class UiState(
    val tab: Tab = Tab.Library,

    // 服务器
    val serverUrl: String = DEFAULT_SERVER_URL,

    // 曲库
    val stats: Stats? = null,
    val formats: List<FormatCount> = emptyList(),
    val selectedFormat: String? = null,
    val songs: List<Song> = emptyList(),
    val songPage: Int = 1,
    val songTotal: Int = 0,

    // 艺术家 / 歌单
    val artists: List<Artist> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val detailId: String? = null,
    val detailTitle: String? = null,
    val detailSubtitle: String? = null,
    val detailSongs: List<Song> = emptyList(),

    // 搜索（顶栏全局搜索，不再单独占一个 tab）
    val searchQuery: String = "",
    val searchResults: List<Song> = emptyList(),

    // 播放
    val current: Song? = null,
    val queue: List<Song> = emptyList(),
    val isPlaying: Boolean = false,
    val decoding: Boolean = false,
    val showNowPlaying: Boolean = false,
    val volume: Float = 0.7f,

    // 队列 / 定位 / 快捷键
    val queueOpen: Boolean = false,
    val showShortcuts: Boolean = false,
    val locatedSongId: String? = null,
    val locating: Boolean = false,

    // 通用
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null
) {
    val isSearching: Boolean get() = searchQuery.isNotBlank()
    val visibleSongs: List<Song> get() = if (isSearching) searchResults else songs
    val visibleTotal: Int get() = if (isSearching) searchResults.size else songTotal
    val activeFormatLabel: String get() = selectedFormat?.uppercase() ?: "全部曲目"
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val repository = MusicRepository(
        prefs.getString(KEY_SERVER, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
    )
    private val playback = PlaybackController(app)
    private val mediaCache = MediaCache(app)
    private val playbackQueue = PlaybackQueue()

    private val _state = MutableStateFlow(UiState(serverUrl = repository.baseUrl, volume = DEFAULT_VOLUME))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var playJob: Job? = null
    private var lastVolume: Float = DEFAULT_VOLUME

    init {
        PlaybackCommandBridge.onNext = { playNext() }
        PlaybackCommandBridge.onPrevious = { playPrevious() }
        playback.volume = DEFAULT_VOLUME
        playback.onPlayingChanged = { playing ->
            _state.update { it.copy(isPlaying = playing) }
        }
        playback.onEnded = { playNext() }
        playback.onError = { message ->
            _state.update { it.copy(isPlaying = false, error = message) }
        }
        reloadHome()
    }

    // ── 曲库 ──────────────────────────────────────────────────────

    /** 网络恢复后用户手动重试；失败时不会把已有列表清空。 */
    fun retry() = reloadHome()

    /** 回到前台时如果首屏因网络失败是空的，自动再试一次。 */
    fun retryIfNeeded() {
        val s = _state.value
        if (s.error != null && s.songs.isEmpty() && !s.loading) reloadHome()
    }

    private fun reloadHome() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        val stats = async { repository.stats() }
                        val formats = async { repository.formats() }
                        val page = async {
                            repository.songs(1, PAGE_SIZE, _state.value.selectedFormat)
                        }
                        Triple(stats.await(), formats.await(), page.await())
                    }
                }
            }.onSuccess { (stats, formats, page) ->
                _state.update {
                    it.copy(
                        loading = false,
                        error = null,
                        stats = stats,
                        formats = formats.formats,
                        songs = page.items,
                        songPage = 1,
                        songTotal = page.metadata.total
                    )
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(loading = false, error = "加载失败:${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    fun selectFormat(format: String?) {
        if (_state.value.selectedFormat == format) return
        _state.update {
            it.copy(
                selectedFormat = format,
                songs = emptyList(),
                songPage = 1,
                songTotal = 0,
                loading = true
            )
        }
        val query = _state.value.searchQuery
        if (query.isNotBlank()) {
            search(query)
        } else {
            loadSongPage(1, format, append = false)
        }
    }

    fun loadMoreSongs() {
        val s = _state.value
        if (s.isSearching || s.loading || s.songs.size >= s.songTotal) return
        loadSongPage(s.songPage + 1, s.selectedFormat, append = true)
    }

    private fun loadSongPage(page: Int, format: String?, append: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            runCatching {
                withContext(Dispatchers.IO) { repository.songs(page, PAGE_SIZE, format) }
            }.onSuccess { result ->
                _state.update {
                    it.copy(
                        loading = false,
                        songs = if (append) it.songs + result.items else result.items,
                        songPage = page,
                        songTotal = result.metadata.total
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = "加载失败:${e.message}") }
            }
        }
    }

    // ── 艺术家 / 歌单 ─────────────────────────────────────────────
    fun refreshArtists() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            runCatching {
                withContext(Dispatchers.IO) { repository.artists(1, 200) }
            }.onSuccess { page ->
                _state.update { it.copy(loading = false, artists = page.items) }
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = "加载艺术家失败:${e.message}") }
            }
        }
    }

    fun refreshPlaylists() {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { repository.playlists() }
            }.onSuccess { list ->
                _state.update { it.copy(playlists = list) }
            }.onFailure { e ->
                _state.update { it.copy(error = "加载歌单失败:${e.message}") }
            }
        }
    }

    fun openArtist(artist: Artist) {
        _state.update {
            it.copy(
                detailId = artist.id,
                detailTitle = artist.name,
                detailSubtitle = "艺术家 · ${artist.songCount} 首",
                detailSongs = emptyList(),
                loading = true
            )
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { repository.artistSongs(artist.id, 1, 200) }
            }.onSuccess { page ->
                _state.update { it.copy(loading = false, detailSongs = page.items) }
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = "加载失败:${e.message}") }
            }
        }
    }

    fun openPlaylist(playlist: Playlist) {
        _state.update {
            it.copy(
                detailId = playlist.id,
                detailTitle = playlist.name,
                detailSubtitle = playlist.description?.takeIf { d -> d.isNotBlank() }
                    ?: "歌单 · ${playlist.songCount ?: 0} 首",
                detailSongs = emptyList(),
                loading = true
            )
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { repository.playlist(playlist.id) }
            }.onSuccess { detail ->
                _state.update {
                    it.copy(loading = false, detailSongs = detail.songs.orEmpty())
                }
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = "加载失败:${e.message}") }
            }
        }
    }

    fun closeDetail() {
        _state.update {
            it.copy(detailId = null, detailTitle = null, detailSubtitle = null, detailSongs = emptyList())
        }
    }

    // ── 搜索 ──────────────────────────────────────────────────────
    fun search(query: String) {
        _state.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(searchResults = emptyList()) }
            loadSongPage(1, _state.value.selectedFormat, append = false)
            return
        }
        searchJob = viewModelScope.launch {
            delay(300) // 输入防抖
            _state.update { it.copy(loading = true) }
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.search(trimmed, _state.value.selectedFormat)
                }
            }.onSuccess { results ->
                _state.update { it.copy(loading = false, searchResults = results) }
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = "搜索失败:${e.message}") }
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _state.update { it.copy(searchQuery = "", searchResults = emptyList()) }
        loadSongPage(1, _state.value.selectedFormat, append = false)
    }

    /** 网页端 `locateCurrentSong`:清空筛选,取到默认排序页码后加载那一页。 */
    fun locateCurrentSong() {
        val song = _state.value.current ?: return
        if (_state.value.locating) return
        searchJob?.cancel()
        _state.update {
            it.copy(
                locating = true,
                searchQuery = "",
                searchResults = emptyList(),
                selectedFormat = null,
                loading = true,
                error = null
            )
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val location = repository.songLocation(song.id, PAGE_SIZE)
                    location to repository.songs(location.page, PAGE_SIZE, null)
                }
            }.onSuccess { (location, page) ->
                _state.update {
                    it.copy(
                        loading = false,
                        locating = false,
                        songs = page.items,
                        songPage = location.page,
                        songTotal = page.metadata.total,
                        locatedSongId = song.id
                    )
                }
                delay(1800)
                _state.update {
                    if (it.locatedSongId == song.id) it.copy(locatedSongId = null) else it
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        loading = false,
                        locating = false,
                        error = "定位失败:${e.message ?: e.javaClass.simpleName}"
                    )
                }
            }
        }
    }

    // ── 导航 ──────────────────────────────────────────────────────
    fun selectTab(tab: Tab) {
        _state.update {
            it.copy(
                tab = tab,
                detailId = null,
                detailTitle = null,
                detailSubtitle = null,
                detailSongs = emptyList(),
                showNowPlaying = false,
                queueOpen = false
            )
        }
        when (tab) {
            Tab.Artists -> if (_state.value.artists.isEmpty()) refreshArtists()
            Tab.Playlists -> refreshPlaylists()
            else -> Unit
        }
    }

    // ── 播放 ──────────────────────────────────────────────────────
    fun play(song: Song, list: List<Song>) {
        playJob?.cancel()
        playbackQueue.setFrom(list, song)
        startSong(song)
    }

    /** 与 Web 端 `OrzAudioPlayer.play(song)` 同层:决定走 direct/server/native。 */
    private fun startSong(song: Song) {
        when (val route = PlaybackPolicy.route(song, repository, RuntimeDecoderManifest)) {
            is PlaybackRoute.Remote -> {
                playback.play(
                    AudioSource.Remote(
                        url = route.url,
                        id = song.id,
                        title = song.title,
                        artist = song.artistName,
                        format = song.fileFormat,
                        durationMs = ((song.duration ?: 0.0) * 1000).toLong()
                    )
                )
                _state.update {
                    it.copy(
                        current = song,
                        queue = playbackQueue.items,
                        decoding = false,
                        error = null,
                        notice = null
                    )
                }
            }

            is PlaybackRoute.Native -> startNative(song, route.rawUrl)
            is PlaybackRoute.Unsupported -> showUnsupported(song, route.format)
        }
    }

    private fun showUnsupported(song: Song, format: String) {
        _state.update {
            it.copy(
                current = song,
                queue = playbackQueue.items,
                isPlaying = false,
                decoding = false,
                error = null,
                notice = "「${format.uppercase()}」没有编进原生解码器" +
                    "(当前支持 ${RuntimeDecoderManifest.formats.joinToString("/") { it.id }})。" +
                    "可以先在网页播放器里播放。"
            )
        }
    }

    /**
     * wasmDecode / 本地可解的 serverDecode 曲目：只下载原始文件，
     * 交给播放服务里的 [com.jokerhub.orzmusic.android.player.OrzPlaybackPlayer]
     * 用 `PcmStream` + `AudioTrack` 流式解码播放（不再整曲转 WAV）。
     */
    private fun startNative(song: Song, rawUrl: String) {
        _state.update {
            it.copy(
                current = song,
                queue = playbackQueue.items,
                isPlaying = false,
                decoding = true,
                error = null,
                notice = null
            )
        }

        playJob = viewModelScope.launch {
            try {
                val raw = mediaCache.rawFor(song, rawUrl)
                if (_state.value.current?.id != song.id) return@launch
                playback.play(
                    AudioSource.Local(
                        path = raw.absolutePath,
                        id = song.id,
                        title = song.title,
                        artist = song.artistName,
                        format = song.fileFormat,
                        durationMs = ((song.duration ?: 0.0) * 1000).toLong()
                    )
                )
                _state.update { it.copy(decoding = false, notice = null) }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                if (_state.value.current?.id == song.id) {
                    _state.update {
                        it.copy(
                            decoding = false,
                            error = "准备失败:${e.message ?: e.javaClass.simpleName}"
                        )
                    }
                }
            }
        }
    }

    fun togglePlay() {
        val s = _state.value
        if (s.current == null || s.decoding) return
        playback.toggle()
    }

    fun playNext() {
        val next = playbackQueue.next()
        if (next == null) {
            playback.stop()
            _state.update { it.copy(isPlaying = false) }
            return
        }
        startSong(next)
    }

    fun playPrevious() {
        val previous = playbackQueue.previous() ?: return
        startSong(previous)
    }

    fun openNowPlaying() {
        if (_state.value.current != null) _state.update { it.copy(showNowPlaying = true) }
    }

    fun closeNowPlaying() {
        _state.update { it.copy(showNowPlaying = false) }
    }

    fun clearNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    val playbackPositionMs: Long get() = playback.positionMs
    val playbackDurationMs: Long get() = playback.durationMs

    fun seekTo(positionMs: Long) = playback.seekTo(positionMs.coerceAtLeast(0L))

    fun seekBy(deltaMs: Long) {
        val duration = playback.durationMs
        val target = (playback.positionMs + deltaMs).coerceAtLeast(0L)
        playback.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
    }

    fun setVolume(value: Float) {
        val v = value.coerceIn(0f, 1f)
        if (v > 0f) lastVolume = v
        playback.volume = v
        _state.update { it.copy(volume = v) }
    }

    fun toggleMute() {
        if (_state.value.volume > 0f) setVolume(0f) else setVolume(lastVolume.takeIf { it > 0f } ?: DEFAULT_VOLUME)
    }

    // ── 队列 / 歌单 ───────────────────────────────────────────────
    fun openQueue() {
        _state.update { it.copy(queueOpen = true) }
        if (_state.value.playlists.isEmpty()) refreshPlaylists()
    }

    fun closeQueue() {
        _state.update { it.copy(queueOpen = false) }
    }

    fun removeFromQueue(songId: String) {
        val currentId = _state.value.current?.id
        playbackQueue.remove(songId)
        if (currentId == songId) {
            playback.stop()
            _state.update {
                it.copy(
                    current = null,
                    queue = playbackQueue.items,
                    isPlaying = false,
                    decoding = false,
                    showNowPlaying = false
                )
            }
        } else {
            _state.update { it.copy(queue = playbackQueue.items) }
        }
    }

    fun saveQueueAsPlaylist(name: String) {
        val trimmed = name.trim()
        val queue = _state.value.queue
        if (trimmed.isEmpty() || queue.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.createPlaylist(
                        PlaylistDraft(
                            name = trimmed,
                            description = null,
                            songIds = queue.map { it.id }
                        )
                    )
                }
            }.onSuccess { playlist ->
                refreshPlaylists()
                _state.update {
                    it.copy(notice = "歌单「${playlist.name}」已保存 · ${queue.size} 首")
                }
            }.onFailure { e ->
                _state.update { it.copy(error = "保存歌单失败:${e.message}") }
            }
        }
    }

    fun loadPlaylistIntoQueue(playlistId: String) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { repository.playlist(playlistId) }
            }.onSuccess { detail ->
                val songs = detail.songs.orEmpty()
                if (songs.isEmpty()) {
                    _state.update { it.copy(notice = "这个歌单还是空的") }
                    return@onSuccess
                }
                playbackQueue.setFrom(songs, songs.first())
                _state.update { it.copy(queueOpen = false) }
                startSong(songs.first())
            }.onFailure { e ->
                _state.update { it.copy(error = "加载歌单失败:${e.message}") }
            }
        }
    }

    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { repository.deletePlaylist(playlistId) }
            }.onSuccess {
                refreshPlaylists()
                _state.update { it.copy(notice = "歌单已删除") }
            }.onFailure { e ->
                _state.update { it.copy(error = "删除歌单失败:${e.message}") }
            }
        }
    }

    // ── 面板 / 快捷键 ─────────────────────────────────────────────
    fun toggleShortcuts() {
        _state.update { it.copy(showShortcuts = !it.showShortcuts) }
    }

    fun closeShortcuts() {
        _state.update { it.copy(showShortcuts = false) }
    }

    // ── 设置 ──────────────────────────────────────────────────────
    fun updateServerUrl(raw: String) {
        val normalized = ApiFactory.normalizeBaseUrl(raw)
        repository.updateBaseUrl(normalized)
        prefs.edit().putString(KEY_SERVER, normalized).apply()
        playJob?.cancel()
        playback.stop()
        playbackQueue.clear()
        _state.update {
            it.copy(
                serverUrl = normalized,
                stats = null,
                formats = emptyList(),
                songs = emptyList(),
                songPage = 1,
                songTotal = 0,
                artists = emptyList(),
                playlists = emptyList(),
                detailId = null,
                detailTitle = null,
                detailSongs = emptyList(),
                searchQuery = "",
                searchResults = emptyList(),
                current = null,
                queue = emptyList(),
                isPlaying = false,
                decoding = false,
                showNowPlaying = false,
                queueOpen = false,
                locatedSongId = null,
                error = null,
                notice = "已切换到 $normalized"
            )
        }
        reloadHome()
    }

    fun checkServer() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                withContext(Dispatchers.IO) { repository.health() }
            }.onSuccess { health ->
                _state.update {
                    it.copy(
                        loading = false,
                        notice = "服务器 ${health.status} · v${health.version} · " +
                            "DB ${health.database} · CAS ${health.cas}"
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = "连接失败:${e.message}") }
            }
        }
    }

    override fun onCleared() {
        PlaybackCommandBridge.onNext = null
        PlaybackCommandBridge.onPrevious = null
        searchJob?.cancel()
        playJob?.cancel()
        playback.release()
        super.onCleared()
    }

    companion object {
        private const val PREFS = "orzmusic"
        private const val KEY_SERVER = "server_url"
        const val PAGE_SIZE = 50
        private const val DEFAULT_VOLUME = 0.7f
    }
}
