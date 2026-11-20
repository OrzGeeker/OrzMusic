package com.jokerhub.orzmusic.android.data

import com.jokerhub.orzmusic.core.api.OrzMusicTransport
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
import com.jokerhub.orzmusic.core.util.UrlResolver

/**
 * 平台无关契约 [OrzMusicTransport] 的 Android 实现(Retrofit + OkHttp)。
 *
 * 服务器地址可切换;相对 URL 统一用 [UrlResolver] 拼绝对地址。
 */
class MusicRepository(initialBaseUrl: String) : OrzMusicTransport {

    @Volatile
    private var _baseUrl: String = UrlResolver.normalizeBaseUrl(initialBaseUrl, DEFAULT_SERVER_URL)

    override val baseUrl: String get() = _baseUrl

    @Volatile
    private var api: OrzMusicApi = ApiFactory.create(_baseUrl)

    override fun updateBaseUrl(raw: String) {
        _baseUrl = UrlResolver.normalizeBaseUrl(raw, DEFAULT_SERVER_URL)
        api = ApiFactory.create(_baseUrl)
    }

    override fun absolute(path: String): String = UrlResolver.resolve(_baseUrl, path)

    override suspend fun stats(): Stats = api.stats()
    override suspend fun health(): Health = api.health()
    override suspend fun formats(): FormatSummary = api.formats()

    override suspend fun songs(page: Int, per: Int, format: String?): Page<Song> =
        api.songs(page = page, per = per, format = format)

    override suspend fun search(query: String, format: String?): List<Song> =
        api.search(query = query, format = format)

    override suspend fun songLocation(songId: String, per: Int): SongLocation =
        api.songLocation(songId = songId, per = per)

    override suspend fun artists(page: Int, per: Int): Page<Artist> =
        api.artists(page = page, per = per)

    override suspend fun artistSongs(artistId: String, page: Int, per: Int): Page<Song> =
        api.artistSongs(artistId = artistId, page = page, per = per)

    override suspend fun albums(page: Int, per: Int): Page<Album> =
        api.albums(page = page, per = per)

    override suspend fun playlists(): List<Playlist> = api.playlists()

    override suspend fun playlist(id: String): Playlist = api.playlist(id)

    override suspend fun createPlaylist(draft: PlaylistDraft): Playlist =
        api.createPlaylist(draft)

    override suspend fun deletePlaylist(id: String) = api.deletePlaylist(id)
}
