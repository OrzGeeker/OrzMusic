package com.jokerhub.orzmusic.android.data

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
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * OrzMusic HTTP API。
 *
 * 分页参数是 Vapor Fluent 的 `page` / `per`(不是 per_page —— 后端会忽略后者,
 * 实测传 per_page=2 仍然按 per=10 返回)。元信息在响应的 `metadata` 里。
 */
interface OrzMusicApi {

    @GET("api/stats")
    suspend fun stats(): Stats

    @GET("api/health")
    suspend fun health(): Health

    @GET("api/songs/formats")
    suspend fun formats(): FormatSummary

    @GET("api/songs")
    suspend fun songs(
        @Query("page") page: Int = 1,
        @Query("per") per: Int = 50,
        @Query("format") format: String? = null
    ): Page<Song>

    @GET("api/songs/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("format") format: String? = null
    ): List<Song>

    @GET("api/songs/{id}/location")
    suspend fun songLocation(
        @Path("id") songId: String,
        @Query("per") per: Int = 50
    ): SongLocation

    @GET("api/artists")
    suspend fun artists(
        @Query("page") page: Int = 1,
        @Query("per") per: Int = 100
    ): Page<Artist>

    @GET("api/artists/{id}/songs")
    suspend fun artistSongs(
        @Path("id") artistId: String,
        @Query("page") page: Int = 1,
        @Query("per") per: Int = 100
    ): Page<Song>

    @GET("api/albums")
    suspend fun albums(
        @Query("page") page: Int = 1,
        @Query("per") per: Int = 50
    ): Page<Album>

    @GET("api/playlists")
    suspend fun playlists(): List<Playlist>

    @GET("api/playlists/{id}")
    suspend fun playlist(@Path("id") playlistId: String): Playlist

    @POST("api/playlists")
    suspend fun createPlaylist(@Body draft: PlaylistDraft): Playlist

    @DELETE("api/playlists/{id}")
    suspend fun deletePlaylist(@Path("id") playlistId: String)
}
