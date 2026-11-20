package com.jokerhub.orzmusic.core.api

import com.jokerhub.orzmusic.core.model.Album
import com.jokerhub.orzmusic.core.model.Artist
import com.jokerhub.orzmusic.core.model.FormatSummary
import com.jokerhub.orzmusic.core.model.Health
import com.jokerhub.orzmusic.core.model.Page
import com.jokerhub.orzmusic.core.model.Playlist
import com.jokerhub.orzmusic.core.model.PlaylistDraft
import com.jokerhub.orzmusic.core.model.Song
import com.jokerhub.orzmusic.core.model.SongLocation
import com.jokerhub.orzmusic.core.model.Stats

/**
 * 平台无关的 OrzMusic HTTP 契约。
 *
 * Android 用 Retrofit 实现;Apple / Desktop 可以分别用 URLSession / Ktor 实现,
 * 但模型与调用形态保持一致。后端 API 由 `GET /api/openapi.json` 定义。
 */
interface OrzMusicTransport {

    val baseUrl: String

    fun updateBaseUrl(raw: String)

    /** 后端返回的 streamUrl / rawUrl 是相对路径,统一在这里拼绝对地址。 */
    fun absolute(path: String): String

    suspend fun stats(): Stats
    suspend fun health(): Health
    suspend fun formats(): FormatSummary

    suspend fun songs(page: Int, per: Int = 50, format: String? = null): Page<Song>

    suspend fun search(query: String, format: String? = null): List<Song>

    suspend fun artists(page: Int = 1, per: Int = 100): Page<Artist>

    suspend fun artistSongs(artistId: String, page: Int = 1, per: Int = 100): Page<Song>

    suspend fun albums(page: Int = 1, per: Int = 50): Page<Album>

    suspend fun playlists(): List<Playlist>

    suspend fun playlist(id: String): Playlist

    /** 当前曲目在默认排序中的位置;用于“定位当前曲目”。 */
    suspend fun songLocation(songId: String, per: Int = 50): SongLocation

    suspend fun createPlaylist(draft: PlaylistDraft): Playlist

    suspend fun deletePlaylist(id: String)
}
